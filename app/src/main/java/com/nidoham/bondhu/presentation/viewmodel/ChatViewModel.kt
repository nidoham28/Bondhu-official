package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.server.domain.message.Message
import com.nidoham.server.domain.message.MessagePreview
import com.nidoham.server.manager.AiMessageManager
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.repository.participant.UserRepository
import com.nidoham.server.util.MessageStatus
import com.nidoham.server.util.MessageType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nidoham.server.util.toTimeAgo
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isSendError: Boolean = false,
    val peerName: String = "",
    val peerAvatarUrl: String = "",
    val isPeerOnline: Boolean = false,
    val lastSeen: String = "",
    val isPeerTyping: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val firebaseAuth: FirebaseAuth,
    private val aiMessageManager: AiMessageManager
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModelLog"
    }

    private val onlineStalenessMs: Long = TimeUnit.MINUTES.toMillis(15)
    private val selfTypingIdleStopMs: Long = 5_000L

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid ?: ""

    // Session tracking
    private var currentConversationId: String? = null
    private var currentPeerId: String? = null
    private var targetId: String = ""
    private var targetAI:  Boolean = false

    // Coroutine jobs
    private var chatObservationJob: Job? = null
    private var peerProfileJob: Job? = null
    private var peerPresenceJob: Job? = null
    private var peerTypingJob: Job? = null
    private var selfTypingStopJob: Job? = null

    /**
     * Entry point for the ViewModel.
     * @param conversationId The ID of the conversation.
     * @param targetId Optional. If present, configures the session for AI interaction immediately.
     */
    fun initChat(conversationId: String, targetId: String? = null, targetAI: Boolean? = false) {
        if (currentConversationId == conversationId) {
            Timber.tag(TAG).d("initChat: Already initialized for $conversationId")
            return
        }

        Timber.tag(TAG).i("────────────────────────────────────────")
        Timber.tag(TAG).i("INIT CHAT START")
        Timber.tag(TAG).i("Conversation ID: $conversationId")
        Timber.tag(TAG).i("Current User ID: $currentUserId")
        Timber.tag(TAG).i("Target ID: $targetId")
        Timber.tag(TAG).i("Target AI: $targetAI")
        Timber.tag(TAG).i("────────────────────────────────────────")

        cancelPeerJobs()
        stopSelfTypingInternal()

        currentConversationId = conversationId
        currentPeerId = null

        // Set AI ID immediately if provided
        targetId?.let { this.targetId = it }
        targetAI?.let { this.targetAI = it }

        chatObservationJob?.cancel()
        chatObservationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Stream 1: Participants
            launch {
                messageRepository.observeParticipants(conversationId)
                    .catch { e ->
                        Timber.tag(TAG).e(e, "Participant stream error for $conversationId")
                    }
                    .collect { participants ->
                        Timber.tag(TAG).d("Participants updated: count=${participants.size}")

                        val peerId = participants.firstOrNull { it.uid != currentUserId }?.uid

                        if (peerId == null) {
                            Timber.tag(TAG).w("Could not resolve peer UID (User might be alone in chat)")
                            return@collect
                        }

                        if (peerId == currentPeerId) return@collect

                        Timber.tag(TAG).i("Peer resolved: $peerId")
                        currentPeerId = peerId

                        cancelPeerJobs()
                        peerProfileJob = launch { loadPeerProfile(peerId) }
                        peerPresenceJob = launch { observePeerPresence(peerId) }
                        peerTypingJob = launch { observePeerTyping(conversationId, peerId) }
                    }
            }

            // Stream 2: Conversation Metadata
            launch {
                messageRepository.observeConversation(conversationId)
                    .collect {
                        // Reserved for future use
                    }
            }

            // Stream 3: Messages
            launch {
                messageRepository.observeMessages(conversationId)
                    .catch { e ->
                        Timber.tag(TAG).e(e, "Message stream error for $conversationId")
                        _uiState.update { it.copy(isLoading = false, isSendError = true) }
                    }
                    .collect { incoming ->
                        Timber.tag(TAG).d("Messages received: count=${incoming.size}")
                        _uiState.update { it.copy(messages = incoming.reversed(), isLoading = false) }
                        markUndeliveredMessages(conversationId, incoming)
                    }
            }
        }
    }

    /**
     * Explicitly configures AI mode after initialization.
     */
    fun configureAi(userId: String) {
        Timber.tag(TAG).i("Configuring AI Mode for User ID: $userId")
        this.targetId = userId
    }

    private suspend fun loadPeerProfile(peerId: String) {
        Timber.tag(TAG).d("Loading profile for peer: $peerId")
        val user = userRepository.fetchUser(peerId)
            .getOrElse { e ->
                Timber.tag(TAG).e(e, "Failed to load peer profile")
                return
            } ?: return

        _uiState.update { state ->
            state.copy(
                peerName = user.displayName.takeIf { it.isNotBlank() }
                    ?: user.username.takeIf { it.isNotBlank() }
                    ?: "Unknown",
                peerAvatarUrl = user.photoUrl ?: ""
            )
        }
    }

    private suspend fun observePeerPresence(peerId: String) {
        userRepository.observeUserStatus(peerId).collect { status ->
            val now = System.currentTimeMillis()
            val lastSeenMs = status.lastSeen ?: 0L
            val effectivelyOnline = status.online && (now - lastSeenMs) < onlineStalenessMs

            _uiState.update { state ->
                state.copy(
                    isPeerOnline = effectivelyOnline,
                    isPeerTyping = if (!effectivelyOnline) false else state.isPeerTyping,
                    lastSeen = if (effectivelyOnline) "online" else formatLastSeen(lastSeenMs)
                )
            }
        }
    }

    private suspend fun observePeerTyping(conversationId: String, peerId: String) {
        messageRepository.observeTyping(conversationId)
            .catch { _uiState.update { it.copy(isPeerTyping = false) } }
            .collect { typingState ->
                _uiState.update { it.copy(isPeerTyping = peerId in typingState.typingUserIds) }
            }
    }

    private fun markUndeliveredMessages(conversationId: String, messages: List<Message>) {
        val undeliveredIds = messages
            .filter { msg ->
                msg.senderId != currentUserId &&
                        msg.status == MessageStatus.PENDING.value
            }
            .map { it.messageId }

        if (undeliveredIds.isEmpty()) return

        viewModelScope.launch {
            messageRepository.updateMessageStatusBatch(
                conversationId = conversationId,
                messageIds = undeliveredIds,
                status = MessageStatus.DELIVERED
            )
        }
    }

    fun onInputChanged(text: String) {
        val conversationId = currentConversationId ?: return
        _uiState.update { it.copy(inputText = text, isSendError = false) }

        if (text.isNotBlank()) {
            viewModelScope.launch { messageRepository.setTyping(conversationId, uid = currentUserId, true) }
            selfTypingStopJob?.cancel()
            selfTypingStopJob = viewModelScope.launch {
                delay(selfTypingIdleStopMs)
                messageRepository.setTyping(conversationId, uid = currentUserId, false)
            }
        } else {
            stopSelfTypingInternal()
        }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val conversationId = currentConversationId

        if (text.isEmpty() || conversationId == null) {
            Timber.tag(TAG).w("SendMessage aborted: Text empty or ConvId null")
            return
        }

        Timber.tag(TAG).i("────────────────────────────────────────")
        Timber.tag(TAG).i("SEND MESSAGE START")
        Timber.tag(TAG).i("Content: $text")
        Timber.tag(TAG).i("Conversation: $conversationId")
        Timber.tag(TAG).i("Sender (Me): $currentUserId")
        Timber.tag(TAG).i("────────────────────────────────────────")

        _uiState.update { it.copy(inputText = "", isSendError = false) }
        stopSelfTypingInternal()

        viewModelScope.launch {
            val userMessage = Message(
                parentId = conversationId,
                senderId = currentUserId,
                content = text,
                type = MessageType.TEXT.value
            )

            messageRepository.sendMessage(conversationId, userMessage)
                .onSuccess { messageId ->
                    Timber.tag(TAG).i("Message sent successfully to DB. ID: $messageId")

                    val preview = MessagePreview(
                        messageId = messageId,
                        parentId = conversationId,
                        senderId = currentUserId,
                        content = text,
                        type = MessageType.TEXT.value
                    )

                    messageRepository.updateLastMessage(conversationId, preview)
                        .onFailure { e -> Timber.tag(TAG).e(e, "Failed to update lastMessage preview") }

                    messageRepository.incrementMessageCount(conversationId)
                        .onFailure { e -> Timber.tag(TAG).e(e, "Failed to increment message count") }

                    // Trigger AI Logic
                    // Change it. it's just for try
                    if (!targetAI) {
                        Timber.tag(TAG).i("AI Mode Active: Requesting reply from AI ($targetId)")
                        launch {
                            aiMessageManager.push(
                                userMessage = text,
                                targetId = targetId,
                                conversationId = conversationId
                            )
                        }
                    } else {
                        Timber.tag(TAG).d("AI Mode Inactive: No AI user ID set.")
                    }
                }
                .onFailure { e ->
                    Timber.tag(TAG).e(e, "Failed to send user message")
                    _uiState.update { it.copy(inputText = text, isSendError = true) }
                }
        }
    }

    fun isMine(message: Message): Boolean = message.senderId == currentUserId

    fun isReadByPeer(message: Message): Boolean =
        message.senderId == currentUserId && message.status == MessageStatus.READ.value

    fun clearError() = _uiState.update { it.copy(isSendError = false) }

    private fun formatLastSeen(lastSeenMs: Long): String =
        if (lastSeenMs == 0L) "" else lastSeenMs.toTimeAgo()

    private fun cancelPeerJobs() {
        peerProfileJob?.cancel()
        peerPresenceJob?.cancel()
        peerTypingJob?.cancel()
        peerProfileJob = null
        peerPresenceJob = null
        peerTypingJob = null
    }

    private fun stopSelfTypingInternal() {
        selfTypingStopJob?.cancel()
        selfTypingStopJob = null
        val conversationId = currentConversationId ?: return

        if (targetId.isNotEmpty()) return

        viewModelScope.launch {
            messageRepository.setTyping(conversationId, uid = currentUserId, typing = false)
        }
    }

    override fun onCleared() {
        Timber.tag(TAG).i("ViewModel Cleared - Cleaning up resources")
        stopSelfTypingInternal()
        cancelPeerJobs()
        chatObservationJob?.cancel()
        super.onCleared()
    }
}
