package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nidoham.bondhu.data.repository.message.MessageRepository
import com.nidoham.bondhu.data.repository.user.UserRepository
import com.nidoham.bondhu.presentation.component.profile.ProfileUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nidoham.server.domain.model.ConversationType
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository  // Fixed: injected, was missing entirely
) : ViewModel() {

    // ─────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _isTargetOnline = MutableStateFlow(false)
    val isTargetOnline: StateFlow<Boolean> = _isTargetOnline.asStateFlow()

    /** UID of the profile currently displayed. Null means "own profile". */
    private var currentProfileId: String? = null

    /**
     * Tracks the active presence-observation coroutine so we can cancel the
     * previous one before starting a new one (avoids multiple collectors leaking
     * whenever [loadProfile] / [refreshProfile] is called).
     */
    private var presenceJob: Job? = null

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Entry point called by the screen.
     * [profileUserId] null or blank → own profile, otherwise another user's profile.
     */
    fun loadProfile(profileUserId: String?) {
        val resolvedId = profileUserId?.takeIf { it.isNotBlank() }
        currentProfileId = resolvedId
        fetchProfile(resolvedId)
    }

    /** Re-fetches the currently displayed profile (used on error retry). */
    fun refreshProfile() = fetchProfile(currentProfileId)

    /** Marks the signed-in user as "just seen" in Firestore (own profile only). */
    fun updateLastActive() {
        viewModelScope.launch {
            userRepository.updateProfile(mapOf("lastActiveAt" to System.currentTimeMillis()))
        }
    }

    /** Follow / unfollow the currently displayed user. */
    fun toggleFollow() {
        val targetId = _uiState.value.user?.uid ?: return
        if (_uiState.value.isFollowLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isFollowLoading = true) }
            val result = if (_uiState.value.isFollowing) {
                userRepository.unfollow(targetId)
            } else {
                userRepository.follow(targetId)
            }

            result.fold(
                onSuccess = {
                    val nowFollowing = !_uiState.value.isFollowing
                    _uiState.update { state ->
                        state.copy(
                            isFollowing     = nowFollowing,
                            isFollowLoading = false,
                            followersCount  = state.followersCount + if (nowFollowing) 1 else -1
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isFollowLoading = false,
                            error           = error.message ?: "Failed to update follow status."
                        )
                    }
                }
            )
        }
    }

    /**
     * Finds an existing private conversation between the signed-in user and
     * [targetUserId]. If none exists, creates one. Calls [onConversationReady]
     * with the resolved conversationId so the caller can open ChatActivity.
     *
     * Flow:
     *   1. Guard against double-tap / re-entry via [isMessageLoading].
     *   2. Resolve the current user's UID from [userRepository].
     *   3. Call [messageRepository.createConversation] which internally:
     *        a. Checks for an existing PRIVATE conversation (find-or-create).
     *        b. Creates a new one if none exists.
     *   4. Deliver the conversationId via [onConversationReady].
     *   5. On any failure, surface the error in the snackbar and reset loading.
     */
    fun startConversation(targetUserId: String, onConversationReady: (String) -> Unit) {
        // Fixed: was missing the loading guard reset on failure and had a TODO placeholder
        if (_uiState.value.isMessageLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMessageLoading = true, error = null) }

            runCatching {
                // Step 1 — Resolve current user
                val currentUserId = userRepository.getCurrentUserId()
                    ?: throw IllegalStateException("Not signed in.")

                // Step 2 — Resolve target user's display name for conversation title.
                //           Falls back to a sensible default so the title is never blank.
                val targetUser = userRepository.getUserProfile(targetUserId)
                val title = targetUser?.displayName?.takeIf { it.isNotBlank() }
                    ?: targetUser?.username?.takeIf { it.isNotBlank() }
                    ?: "Chat"

                // Step 3 — Find existing private conversation or create a new one.
                //           MessageRepository.createConversation deduplicates automatically.
                val conversation = messageRepository.createConversation(
                    creatorId             = currentUserId,
                    title                 = title,
                    type                  = ConversationType.PRIVATE,
                    initialParticipantIds = listOf(currentUserId, targetUserId)
                ).getOrThrow()

                // Step 4 — Deliver conversationId to the caller
                onConversationReady(conversation.conversationId)
            }.onSuccess {
                _uiState.update { it.copy(isMessageLoading = false) }
            }.onFailure { error ->
                // Fixed: loading flag was never reset inside the old TODO block on failure
                _uiState.update {
                    it.copy(
                        isMessageLoading = false,
                        error = error.message ?: "Could not open conversation."
                    )
                }
            }
        }
    }

    /** Clears the current error so the Snackbar dismisses. */
    fun clearError() = _uiState.update { it.copy(error = null) }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private fun fetchProfile(profileUserId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatching {
                val currentUid = userRepository.getCurrentUserId()
                val isOwner    = profileUserId == null || profileUserId == currentUid

                val user = if (isOwner) {
                    userRepository.getCurrentUserProfile()
                } else {
                    userRepository.getUserProfile(profileUserId)
                } ?: throw NoSuchElementException("User not found.")

                val isFollowing = if (!isOwner && currentUid != null) {
                    userRepository.isFollowing(user.uid)
                } else false

                // Cancel any previous presence collector before starting a fresh one.
                observeOnlineStatus(user.uid)

                _uiState.update { state ->
                    state.copy(
                        isLoading      = false,
                        user           = user,
                        isOwner        = isOwner,
                        isFollowing    = isFollowing,
                        followersCount = user.followerCount,
                        followingCount = user.followingCount,
                        error          = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error     = error.message ?: "Something went wrong."
                    )
                }
            }
        }
    }

    /**
     * Observes the real-time online/offline status for [uid] via [PresenceManager].
     *
     * Uses [userRepository.observeUserPresence] (backed by Firebase Realtime Database)
     * instead of the Firestore user-doc observer, which is not designed for presence tracking.
     *
     * The previous [presenceJob] is cancelled before starting a new collector so that
     * switching profiles never leaves a stale coroutine running in the background.
     */
    private fun observeOnlineStatus(uid: String) {
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch {
            userRepository.observeUserPresence(uid).collect { status ->
                _isTargetOnline.value = status.online
            }
        }
    }
}