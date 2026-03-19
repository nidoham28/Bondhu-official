package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.participant.Participant
import com.nidoham.server.domain.participant.User
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
import timber.log.Timber
import javax.inject.Inject

data class ProfileUiState(
    val isLoading: Boolean = true,
    val user: User? = null,
    val isOwner: Boolean = false,
    val isFollowing: Boolean = false,
    val isFollowLoading: Boolean = false,
    val isMessageLoading: Boolean = false,
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    val postsCount: Long = 0,
    val error: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    companion object {
        private const val TAG = "ProfileViewModelLog"
    }

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _isTargetOnline = MutableStateFlow(false)
    val isTargetOnline: StateFlow<Boolean> = _isTargetOnline.asStateFlow()

    private var currentProfileId: String? = null
    private var presenceJob: Job? = null

    fun loadProfile(profileUserId: String?) {
        val resolvedId = profileUserId?.takeIf { it.isNotBlank() }

        if (currentProfileId == resolvedId && _uiState.value.user != null) {
            Timber.tag(TAG).d("loadProfile: Already loaded for $resolvedId")
            return
        }

        currentProfileId = resolvedId
        fetchProfile(resolvedId)
    }

    fun refreshProfile() = fetchProfile(currentProfileId)

    fun updateLastActive() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        viewModelScope.launch {
            userRepository.updateUser(uid, mapOf("lastActiveAt" to System.currentTimeMillis()))
        }
    }

    fun toggleFollow() {
        val targetId = _uiState.value.user?.uid ?: return
        val currentId = firebaseAuth.currentUser?.uid ?: return
        if (_uiState.value.isFollowLoading) return

        val wasFollowing = _uiState.value.isFollowing

        viewModelScope.launch {
            _uiState.update { it.copy(isFollowLoading = true) }

            runCatching {
                if (wasFollowing) userRepository.unfollowUser(currentId, targetId).getOrThrow()
                else userRepository.followUser(currentId, targetId).getOrThrow()
            }.onSuccess {
                val nowFollowing = !wasFollowing
                _uiState.update { state ->
                    state.copy(
                        isFollowing = nowFollowing,
                        isFollowLoading = false,
                        followersCount = maxOf(0, state.followersCount + if (nowFollowing) 1 else -1)
                    )
                }
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Failed to toggle follow")
                _uiState.update {
                    it.copy(
                        isFollowLoading = false,
                        error = error.message ?: "Failed to update follow status."
                    )
                }
            }
        }
    }

    /**
     * Finds or creates a personal conversation.
     * FIXED: Now adds BOTH participants (current user and target user) to the conversation.
     */
    fun startConversation(targetUserId: String, onConversationReady: (String) -> Unit) {
        if (_uiState.value.isMessageLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isMessageLoading = true, error = null) }

            runCatching {
                val currentUserId = firebaseAuth.currentUser?.uid
                    ?: throw IllegalStateException("Not signed in.")

                Timber.tag(TAG).i("Start Conversation: Current=$currentUserId, Target=$targetUserId")

                // 1. Check for existing conversation
                val existingId = messageRepository
                    .fetchSharedParentId(currentUserId, targetUserId)
                    .getOrNull()

                if (!existingId.isNullOrBlank()) {
                    Timber.tag(TAG).d("Existing conversation found: $existingId")
                    existingId
                } else {
                    Timber.tag(TAG).d("Creating new conversation...")
                    val targetUser = userRepository.fetchUser(targetUserId)
                        .getOrThrow()
                        ?: throw NoSuchElementException("Target user not found.")

                    val title = targetUser.displayName.takeIf { it.isNotBlank() }
                        ?: targetUser.username.takeIf { it.isNotBlank() }
                        ?: "Chat"

                    // 2. Create the conversation document
                    val conversationId = messageRepository.createConversation(
                        Conversation(
                            creatorId = currentUserId,
                            title = title,
                            type = ParticipantType.PERSONAL.value
                        )
                    ).getOrThrow()

                    Timber.tag(TAG).d("Conversation created with ID: $conversationId")

                    // 3. Add Target Participant
                    messageRepository.addParticipant(
                        parentId = conversationId,
                        uid = targetUserId,
                        participant = Participant(
                            uid = targetUserId,
                            parentId = conversationId,
                            role = ParticipantRole.MEMBER.value,
                            type = ParticipantType.PERSONAL.value
                        )
                    ).getOrThrow()

                    // 4. FIX: Add Current User Participant (Ensures chat shows up in current user's list)
                    messageRepository.addParticipant(
                        parentId = conversationId,
                        uid = currentUserId,
                        participant = Participant(
                            uid = currentUserId,
                            parentId = conversationId,
                            role = ParticipantRole.ADMIN.value, // Creator is usually Admin
                            type = ParticipantType.PERSONAL.value
                        )
                    ).getOrThrow()

                    conversationId
                }
            }.onSuccess { conversationId ->
                Timber.tag(TAG).i("Conversation ready: $conversationId")
                _uiState.update { it.copy(isMessageLoading = false) }
                onConversationReady(conversationId)
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Failed to start conversation")
                _uiState.update {
                    it.copy(
                        isMessageLoading = false,
                        error = error.message ?: "Could not open conversation."
                    )
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun fetchProfile(profileUserId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val currentUid = firebaseAuth.currentUser?.uid
            val isOwner = profileUserId == null || profileUserId == currentUid

            runCatching {
                val uid = when {
                    isOwner -> currentUid ?: throw NoSuchElementException("User not found.")
                    else -> profileUserId ?: throw NoSuchElementException("User not found.")
                }

                val user = userRepository.fetchUser(uid)
                    .getOrThrow()
                    ?: throw NoSuchElementException("User not found.")

                val isFollowing = if (!isOwner && currentUid != null) {
                    userRepository.isFollowing(currentUid, user.uid).getOrElse { false }
                } else false

                user to isFollowing
            }.onSuccess { (user, isFollowing) ->
                observeOnlineStatus(user.uid)
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        user = user,
                        isOwner = isOwner,
                        isFollowing = isFollowing,
                        followersCount = user.followerCount,
                        followingCount = user.followingCount,
                        error = null
                    )
                }
            }.onFailure { error ->
                Timber.tag(TAG).e(error, "Fetch profile failed")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Something went wrong."
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