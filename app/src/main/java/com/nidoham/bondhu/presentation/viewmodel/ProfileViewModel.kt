package com.nidoham.bondhu.presentation.viewmodel

import android.nidoham.server.domain.ParticipantType
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
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository
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
        viewModelScope.launch {
            userRepository.updateProfile(mapOf("lastActiveAt" to System.currentTimeMillis()))
        }
    }

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
     * Finds or creates a private conversation between the signed-in user and
     * [targetUserId], then delivers the conversationId via [onConversationReady].
     */
    fun startConversation(targetUserId: String, onConversationReady: (String) -> Unit) {
        if (_uiState.value.isMessageLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMessageLoading = true, error = null) }

            runCatching {
                val currentUserId = userRepository.getCurrentUserId()
                    ?: throw IllegalStateException("Not signed in.")

                val targetUser = userRepository.getUserProfile(targetUserId)
                val title = targetUser?.displayName?.takeIf { it.isNotBlank() }
                    ?: targetUser?.username?.takeIf { it.isNotBlank() }
                    ?: "Chat"

                val conversation = messageRepository.createConversation(
                    creatorId             = currentUserId,
                    title                 = title,
                    type                  = ParticipantType.PERSONAL,
                    initialParticipantIds = listOf(currentUserId, targetUserId)
                ).getOrThrow()

                onConversationReady(conversation.conversationId)
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
            userRepository.observeUserPresence(uid).collect { status ->
                _isTargetOnline.value = status.online
            }
        }
    }
}