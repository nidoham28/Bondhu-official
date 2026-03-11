package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.bondhu.presentation.component.profile.ProfileUiState
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.repository.participant.UserRepository
import com.nidoham.server.util.ParticipantType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    // FIX: getCurrentUserId() did not exist on UserRepository.
    //      FirebaseAuth is the authoritative source for the current user's UID.
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    // ─────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _isTargetOnline = MutableStateFlow(false)
    val isTargetOnline: StateFlow<Boolean> = _isTargetOnline.asStateFlow()

    private var currentProfileId: String? = null
    private var presenceJob: Job? = null

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    fun loadProfile(profileUserId: String?) {
        val resolvedId = profileUserId?.takeIf { it.isNotBlank() }
        currentProfileId = resolvedId
        fetchProfile(resolvedId)
    }

    fun refreshProfile() = fetchProfile(currentProfileId)

    fun updateLastActive() {
        // FIX: getCurrentUserId() did not exist on UserRepository.
        //      FIX: updateProfile() requires (uid, fields) — the uid was missing.
        val uid = firebaseAuth.currentUser?.uid ?: return
        viewModelScope.launch {
            userRepository.updateProfile(uid, mapOf("lastActiveAt" to System.currentTimeMillis()))
        }
    }

    fun toggleFollow() {
        val targetId = _uiState.value.user?.uid ?: return
        val currentId = firebaseAuth.currentUser?.uid ?: return
        if (_uiState.value.isFollowLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isFollowLoading = true) }

            // FIX: followUser() and unfollowUser() both take (currentUid, targetUid)
            //      and return Unit, not Result. Wrapped in runCatching accordingly.
            val result = runCatching {
                if (_uiState.value.isFollowing) {
                    userRepository.unfollowUser(currentId, targetId)
                } else {
                    userRepository.followUser(currentId, targetId)
                }
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
     * Finds or creates a private conversation between the signed-in user and
     * [targetUserId], then delivers the conversation ID via [onConversationReady].
     */
    fun startConversation(targetUserId: String, onConversationReady: (String) -> Unit) {
        if (_uiState.value.isMessageLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMessageLoading = true, error = null) }

            runCatching {
                // FIX: getCurrentUserId() did not exist on UserRepository.
                val currentUserId = firebaseAuth.currentUser?.uid
                    ?: throw IllegalStateException("Not signed in.")

                // FIX: getUserProfile() did not exist on UserRepository.
                //      Replaced with fetchUserById(), which is the correct API.
                val targetUser = userRepository.fetchUserById(targetUserId)
                val title = targetUser?.displayName?.takeIf { it.isNotBlank() }
                    ?: targetUser?.username?.takeIf { it.isNotBlank() }
                    ?: "Chat"

                // FIX: createConversation() accepts a Conversation object, not named
                //      parameters. It returns Result<String> — the new conversation ID —
                //      not a Conversation object, so .conversationId was also wrong.
                val conversationId = messageRepository.createConversation(
                    Conversation(
                        creatorId = currentUserId,
                        title     = title,
                        type      = ParticipantType.PERSONAL.value
                    )
                ).getOrThrow()

                onConversationReady(conversationId)
            }.onSuccess {
                _uiState.update { it.copy(isMessageLoading = false) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isMessageLoading = false,
                        error            = error.message ?: "Could not open conversation."
                    )
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private fun fetchProfile(profileUserId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatching {
                val currentUid = firebaseAuth.currentUser?.uid
                val isOwner    = profileUserId == null || profileUserId == currentUid

                // FIX: the previous ?: throw was bound only to the `else null` branch,
                //      not to the full if/else expression. The compiler saw `null ?: throw`
                //      (useless elvis), inferred the result type as Nothing, and then
                //      reported user.uid as an unresolved reference because Nothing has no
                //      members. Wrapping the entire if/else/null in parentheses before
                //      applying ?: gives user a non-null type of User.
                val user = (if (isOwner && currentUid != null) {
                    userRepository.fetchCurrentUser(currentUid)
                } else if (profileUserId != null) {
                    userRepository.fetchUserById(profileUserId)
                } else null) ?: throw NoSuchElementException("User not found.")

                val isFollowing = if (!isOwner && currentUid != null) {
                    userRepository.isFollowing(currentUid, user.uid)
                } else false

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

    private fun observeOnlineStatus(uid: String) {
        presenceJob?.cancel()
        presenceJob = viewModelScope.launch {
            // FIX: observeUserPresence() did not exist on UserRepository.
            //      Replaced with observeUserStatus(), which is the correct API.
            userRepository.observeUserStatus(uid).collect { status ->
                _isTargetOnline.value = status.online
            }
        }
    }
}