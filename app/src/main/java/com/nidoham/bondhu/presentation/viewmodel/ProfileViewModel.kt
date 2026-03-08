package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nidoham.bondhu.data.repository.user.UserRepository
import com.nidoham.bondhu.presentation.component.profile.ProfileUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository
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
     * Starts or fetches an existing conversation with the currently viewed user.
     * Calls [onConversationReady] with the conversation / channel ID on success.
     */
    fun startConversation(targetUserId: String, onConversationReady: (String) -> Unit) {
        if (_uiState.value.isMessageLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMessageLoading = true) }
            // TODO: wire up your MessageRepository / ChatRepository here.
            // Example:
            //   val result = messageRepository.getOrCreateConversation(targetUserId)
            //   result.fold(
            //       onSuccess = { conversationId -> onConversationReady(conversationId) },
            //       onFailure = { error -> _uiState.update { it.copy(error = error.message) } }
            //   )
            onConversationReady(targetUserId) // placeholder — remove once real repo is wired
            _uiState.update { it.copy(isMessageLoading = false) }
        }
    }

    /** Clears the current error so the Snackbar dismisses. */ // ← fixed typo
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
                    userRepository.getUserProfile(profileUserId) // ← removed unnecessary !!
                } ?: throw NoSuchElementException("User not found.")

                val isFollowing = if (!isOwner && currentUid != null) {
                    userRepository.isFollowing(user.uid)
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
        viewModelScope.launch {
            userRepository.observeCurrentUser(uid).collect { user ->
                _isTargetOnline.value = user?.isOnline ?: false
            }
        }
    }
}