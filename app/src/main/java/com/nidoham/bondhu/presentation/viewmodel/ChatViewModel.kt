package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.server.domain.message.Message
import com.nidoham.server.domain.message.MessagePreview
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

// ─────────────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────────────

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isSendError: Boolean = false,
    val peerName: String = "",
    val peerAvatarUrl: String = "",
    val isPeerOnline: Boolean = false,
    val lastSeen: String = "",
    val isPeerTyping: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    // ── Thresholds ────────────────────────────────────────────────────────────

    private val onlineStalenessMs: Long    = TimeUnit.MINUTES.toMillis(15)
    private val selfTypingIdleStopMs: Long = 5_000L

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid ?: ""

    // ── Session tracking ──────────────────────────────────────────────────────

    private var currentConversationId: String? = null
    private var currentPeerId: String? = null

    // ── Coroutine jobs ────────────────────────────────────────────────────────

    private var chatObservationJob: Job? = null
    private var peerProfileJob: Job? = null
    private var peerPresenceJob: Job? = null
    private var peerTypingJob: Job? = null
    private var selfTypingStopJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Entry Point
    // ─────────────────────────────────────────────────────────────────────────

    fun initChat(conversationId: String) {
        if (currentConversationId == conversationId) return

        // Cancel all peer-level jobs from any previous session before the
        // conversation ID changes, so stale streams do not bleed into the
        // new session.
        cancelPeerJobs()
        stopSelfTypingInternal()

        currentConversationId = conversationId
        currentPeerId         = null

        chatObservationJob?.cancel()
        chatObservationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Stream 1: resolve the peer UID from the participants sub-collection.
            launch {
                messageRepository.observeParticipants(conversationId)
                    .catch { e ->
                        Timber.w(e, "ChatViewModel: participant stream error for $conversationId")
                    }
                    .collect { participants ->
                        val peerId = participants
                            .firstOrNull { it.uid != currentUserId }
                            ?.uid

                        if (peerId == null) {
                            Timber.w("ChatViewModel: could not resolve peer UID for $conversationId")
                            return@collect
                        }

                        if (peerId == currentPeerId) return@collect
                        currentPeerId = peerId

                        cancelPeerJobs()
                        peerProfileJob  = launch { loadPeerProfile(peerId) }
                        peerPresenceJob = launch { observePeerPresence(peerId) }
                        peerTypingJob   = launch { observePeerTyping(conversationId, peerId) }
                    }
            }

            // Stream 2: live conversation metadata — reserved for future use.
            launch {
                messageRepository.observeConversation(conversationId)
                    .collect { /* metadata-driven UI updates go here */ }
            }

            // Stream 3: live message list.
            launch {
                messageRepository.observeMessages(conversationId)
                    .catch { e ->
                        Timber.e(e, "ChatViewModel: message stream error for $conversationId")
                        _uiState.update { it.copy(isLoading = false, isSendError = true) }
                    }
                    .collect { incoming ->
                        _uiState.update { it.copy(messages = incoming.reversed(), isLoading = false) }
                        markUndeliveredMessages(conversationId, incoming)
                    }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Peer Profile
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun loadPeerProfile(peerId: String) {
        val user = userRepository.fetchUser(peerId)
            .getOrElse { e ->
                Timber.w(e, "ChatViewModel: failed to load peer profile for $peerId")
                return
            } ?: return

        _uiState.update { state ->
            state.copy(
                peerName      = user.displayName.takeIf { it.isNotBlank() }
                    ?: user.username.takeIf { it.isNotBlank() }
                    ?: "Unknown",
                peerAvatarUrl = user.photoUrl ?: ""
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Peer Presence
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun observePeerPresence(peerId: String) {
        userRepository.observeUserStatus(peerId).collect { status ->
            val now               = System.currentTimeMillis()
            val lastSeenMs        = status.lastSeen ?: 0L
            val effectivelyOnline = status.online && (now - lastSeenMs) < onlineStalenessMs

            _uiState.update { state ->
                state.copy(
                    isPeerOnline = effectivelyOnline,
                    isPeerTyping = if (!effectivelyOnline) false else state.isPeerTyping,
                    lastSeen     = if (effectivelyOnline) "online" else formatLastSeen(lastSeenMs)
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Peer Typing
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun observePeerTyping(conversationId: String, peerId: String) {
        messageRepository.observeTyping(conversationId)
            .catch { _uiState.update { it.copy(isPeerTyping = false) } }
            .collect { typingState ->
                _uiState.update { it.copy(isPeerTyping = peerId in typingState.typingUserIds) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Undelivered Message Promotion
    // ─────────────────────────────────────────────────────────────────────────

    private fun markUndeliveredMessages(conversationId: String, messages: List<Message>) {
        // Only promote messages from the peer that are still in PENDING status.
        // Use MessageStatus.PENDING.value to match the serialized Firestore field value.
        val undeliveredIds = messages
            .filter { msg ->
                msg.senderId != currentUserId &&
                        msg.status   == MessageStatus.PENDING.value
            }
            .map { it.messageId }

        if (undeliveredIds.isEmpty()) return

        viewModelScope.launch {
            messageRepository.updateMessageStatusBatch(
                conversationId = conversationId,
                messageIds     = undeliveredIds,
                status         = MessageStatus.DELIVERED
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI Events
    // ─────────────────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        val conversationId = currentConversationId ?: return
        _uiState.update { it.copy(inputText = text, isSendError = false) }

        if (text.isNotBlank()) {
            viewModelScope.launch { messageRepository.setTyping(conversationId, true) }
            selfTypingStopJob?.cancel()
            selfTypingStopJob = viewModelScope.launch {
                delay(selfTypingIdleStopMs)
                messageRepository.setTyping(conversationId, false)
            }
        } else {
            stopSelfTypingInternal()
        }
    }

    fun sendMessage() {
        val text           = _uiState.value.inputText.trim()
        val conversationId = currentConversationId
        if (text.isEmpty() || conversationId == null) return

        _uiState.update { it.copy(inputText = "", isSendError = false) }
        stopSelfTypingInternal()

        viewModelScope.launch {
            val message = Message(
                parentId = conversationId,
                senderId = currentUserId,
                content  = text,
                type     = MessageType.TEXT.value
            )

            messageRepository.sendMessage(conversationId, message)
                .onSuccess { messageId ->
                    // Build the preview from the values we already have in scope.
                    // timestamp is null here because @ServerTimestamp is resolved
                    // by Firestore after the write — the preview stores whatever
                    // the server assigns, so null is the correct client-side value
                    // at this point and will be overwritten on the next read.
                    val preview = MessagePreview(
                        messageId = messageId,
                        parentId = conversationId,
                        senderId = currentUserId,
                        content = text,
                        type = MessageType.TEXT.value
                    )

                    // These two calls can fail independently without affecting the
                    // already-committed message. Log failures but do not surface
                    // them to the user — the message itself was sent successfully.
                    messageRepository.updateLastMessage(conversationId, preview)
                        .onFailure { e ->
                            Timber.w(e, "ChatViewModel: failed to update lastMessage for $conversationId")
                        }

                    messageRepository.incrementMessageCount(conversationId)
                        .onFailure { e ->
                            Timber.w(e, "ChatViewModel: failed to increment messageCount for $conversationId")
                        }
                }
                .onFailure {
                    _uiState.update { it.copy(inputText = text, isSendError = true) }
                }
        }
    }

    fun isMine(message: Message): Boolean =
        message.senderId == currentUserId

    /**
     * Returns true if the peer has promoted this message's status to [MessageStatus.READ].
     * Relies on [MessageStatus.READ.value] to match the serialized Firestore field value
     * rather than the enum constant name.
     */
    fun isReadByPeer(message: Message): Boolean =
        message.senderId == currentUserId &&
                message.status   == MessageStatus.READ.value   // fixed: was .name.lowercase()

    fun clearError() = _uiState.update { it.copy(isSendError = false) }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatLastSeen(lastSeenMs: Long): String =
        if (lastSeenMs == 0L) "" else lastSeenMs.toTimeAgo()

    private fun cancelPeerJobs() {
        peerProfileJob?.cancel()
        peerPresenceJob?.cancel()
        peerTypingJob?.cancel()
        peerProfileJob  = null
        peerPresenceJob = null
        peerTypingJob   = null
    }

    /**
     * Cancels the idle-stop timer and sends an explicit stop-typing signal.
     * Must not call [viewModelScope.launch] after [onCleared] has invoked
     * [super.onCleared], so the coroutine is only launched when [viewModelScope]
     * is still active.
     */
    private fun stopSelfTypingInternal() {
        selfTypingStopJob?.cancel()
        selfTypingStopJob = null
        val conversationId = currentConversationId ?: return
        viewModelScope.launch {
            messageRepository.setTyping(conversationId, false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        // stopSelfTypingInternal() must be called before super.onCleared() because
        // super.onCleared() cancels viewModelScope, after which the coroutine
        // launched inside stopSelfTypingInternal() would silently no-op.
        stopSelfTypingInternal()
        cancelPeerJobs()
        chatObservationJob?.cancel()
        super.onCleared()
    }
}