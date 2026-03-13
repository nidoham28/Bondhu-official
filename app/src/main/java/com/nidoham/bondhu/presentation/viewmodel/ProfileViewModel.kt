package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.bondhu.presentation.component.profile.ProfileUiState
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.participant.Participant
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.repository.participant.UserRepository
import com.nidoham.server.util.ParticipantRole
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
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _isTargetOnline = MutableStateFlow(false)
    val isTargetOnline: StateFlow<Boolean> = _isTargetOnline.asStateFlow()

    private var currentProfileId: String? = null
    private var presenceJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun loadProfile(profileUserId: String?) {
        val resolvedId = profileUserId?.takeIf { it.isNotBlank() }
        currentProfileId = resolvedId
        fetchProfile(resolvedId)
    }

    fun refreshProfile() = fetchProfile(currentProfileId)

    fun updateLastActive() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        viewModelScope.launch {
            userRepository.updateProfile(uid, mapOf("lastActiveAt" to System.currentTimeMillis()))
        }
    }

    fun toggleFollow() {
        val targetId  = _uiState.value.user?.uid ?: return
        val currentId = firebaseAuth.currentUser?.uid ?: return
        if (_uiState.value.isFollowLoading) return

        val wasFollowing = _uiState.value.isFollowing

        viewModelScope.launch {
            _uiState.update { it.copy(isFollowLoading = true) }

            runCatching {
                if (wasFollowing) userRepository.unfollowUser(currentId, targetId)
                else              userRepository.followUser(currentId, targetId)
            }.fold(
                onSuccess = {
                    val nowFollowing = !wasFollowing
                    _uiState.update { state ->
                        state.copy(
                            isFollowing     = nowFollowing,
                            isFollowLoading = false,
                            followersCount  = maxOf(0, state.followersCount + if (nowFollowing) 1 else -1)
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
     * Finds or creates a personal conversation between the signed-in user and
     * [targetUserId], then delivers the conversation ID via [onConversationReady].
     *
     * The lookup delegates to [MessageRepository.fetchSharedParentId], which
     * queries the participant sub-collection for the first parentId where both
     * users hold a [ParticipantType.PERSONAL] record. This replaces the previous
     * two-sided [fetchJoinedIds] intersection, reducing the operation to a single
     * collection group query followed by at most one document read per candidate.
     *
     * If no shared conversation exists, a new one is created and both participant
     * records are written atomically before the ID is returned, ensuring that
     * subsequent calls to [fetchSharedParentId] will always locate the existing
     * conversation rather than creating a duplicate.
     */
    fun startConversation(targetUserId: String, onConversationReady: (String) -> Unit) {
        if (_uiState.value.isMessageLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMessageLoading = true, error = null) }

            runCatching {
                val currentUserId = firebaseAuth.currentUser?.uid
                    ?: throw IllegalStateException("Not signed in.")

                // Delegate shared-parentId resolution to the repository layer.
                // fetchSharedParentId queries the participant sub-collection for the
                // first parentId where both users have a PERSONAL participant record,
                // keeping set-intersection logic out of the ViewModel entirely.
                val existingId = messageRepository
                    .fetchSharedParentId(currentUserId, targetUserId)
                    .getOrNull()

                if (!existingId.isNullOrBlank()) {
                    existingId
                } else {
                    val targetUser = userRepository.fetchUserById(targetUserId)
                        ?: throw NoSuchElementException("Target user not found.")

                    val title = targetUser.displayName.takeIf { it.isNotBlank() }
                        ?: targetUser.username.takeIf { it.isNotBlank() }
                        ?: "Chat"

                    // createConversation atomically writes the conversation document
                    // and the current user's ADMIN participant record in a single batch.
                    val conversationId = messageRepository.createConversation(
                        Conversation(
                            creatorId = currentUserId,
                            title     = title,
                            type      = ParticipantType.PERSONAL.value
                        )
                    ).getOrThrow()

                    // Write the target user's participant record so that future
                    // fetchSharedParentId calls locate this conversation from either
                    // user's perspective, preventing duplicate conversations.
                    messageRepository.addParticipant(
                        parentId    = conversationId,
                        uid         = targetUserId,
                        participant = Participant(
                            uid      = targetUserId,
                            parentId = conversationId,
                            role     = ParticipantRole.MEMBER.value,
                            type     = ParticipantType.PERSONAL.value
                        )
                    ).getOrThrow()

                    conversationId
                }
            }.onSuccess { conversationId ->
                _uiState.update { it.copy(isMessageLoading = false) }
                onConversationReady(conversationId)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchProfile(profileUserId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val currentUid = firebaseAuth.currentUser?.uid
            val isOwner    = profileUserId == null || profileUserId == currentUid

            runCatching {
                val user = when {
                    isOwner && currentUid != null -> userRepository.fetchCurrentUser(currentUid)
                    profileUserId != null         -> userRepository.fetchUserById(profileUserId)
                    else                          -> null
                } ?: throw NoSuchElementException("User not found.")

                val isFollowing = if (!isOwner && currentUid != null) {
                    userRepository.isFollowing(currentUid, user.uid)
                } else false

                user to isFollowing
            }.onSuccess { (user, isFollowing) ->
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
            userRepository.observeUserStatus(uid).collect { status ->
                _isTargetOnline.value = status.online
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        presenceJob?.cancel()
        presenceJob = null
    }
}