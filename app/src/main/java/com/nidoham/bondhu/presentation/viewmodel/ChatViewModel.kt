package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.server.domain.message.Message
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
    // FIX: FirebaseAuth replaces the removed userRepository.getCurrentUserId().
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    // ── Thresholds ────────────────────────────────────────────────────────────

    private val onlineStalenessMs: Long    = TimeUnit.MINUTES.toMillis(15)
    private val selfTypingIdleStopMs: Long = 5_000L

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // FIX: getCurrentUserId() did not exist on UserRepository.
    //      Read uid directly from FirebaseAuth at call time so it stays fresh
    //      across auth state changes without a dedicated coroutine.
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
    // SECTION 1 — Entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun initChat(conversationId: String) {
        if (currentConversationId == conversationId) return
        stopSelfTyping()
        currentConversationId = conversationId

        chatObservationJob?.cancel()
        chatObservationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Stream 1: resolve the peer UID via the participants subcollection.
            //
            // FIX: ParticipantManager was previously injected directly into the
            //      ViewModel. All participant access is now routed through
            //      MessageRepository, which already owns ParticipantManager
            //      internally and exposes observeParticipants() as a stable API.
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

                        peerProfileJob?.cancel()
                        peerProfileJob = launch { loadPeerProfile(peerId) }

                        peerPresenceJob?.cancel()
                        peerPresenceJob = launch { observePeerPresence(peerId) }

                        peerTypingJob?.cancel()
                        peerTypingJob = launch { observePeerTyping(conversationId, peerId) }
                    }
            }

            // Stream 2: live conversation metadata (subscriber_count, last_message, etc.)
            launch {
                messageRepository.observeConversation(conversationId)
                    .collect { _ ->
                        // Reserved for future metadata-driven UI updates.
                    }
            }

            // Stream 3: live message list.
            //
            // FIX: observeLiveMessages() did not exist on MessageRepository.
            //      Replaced with observeMessages(), which is the correct API.
            launch {
                messageRepository.observeMessages(conversationId)
                    .catch { _uiState.update { state -> state.copy(isLoading = false, isSendError = true) } }
                    .collect { incoming ->
                        _uiState.update { it.copy(messages = incoming.reversed(), isLoading = false) }

                        // FIX: Message.readBy did not exist on the Message domain model.
                        //      Unread detection is now based on message.status, which
                        //      is the authoritative read/delivery field on Message.
                        //
                        // FIX: markAllDelivered() did not exist on MessageRepository.
                        //      Replaced with updateMessageStatusBatch(), targeting only
                        //      messages from the peer that have not yet been delivered.
                        val undeliveredIds = incoming
                            .filter { msg ->
                                msg.senderId != currentUserId &&
                                        msg.status == MessageStatus.PENDING.name.lowercase()
                            }
                            .map { it.messageId }

                        if (undeliveredIds.isNotEmpty()) {
                            messageRepository.updateMessageStatusBatch(
                                conversationId = conversationId,
                                messageIds     = undeliveredIds,
                                status         = MessageStatus.DELIVERED
                            )
                        }
                    }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 2 — Peer profile
    // ─────────────────────────────────────────────────────────────────────────

    // FIX: observeCurrentUser() did not exist on UserRepository.
    //      Replaced with a one-shot fetchUserById() call. Profile data for a
    //      peer is static within the lifetime of a chat session, so a live
    //      stream is not required here.
    private suspend fun loadPeerProfile(peerId: String) {
        val user = userRepository.fetchUserById(peerId) ?: return
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
    // SECTION 3 — Peer presence
    // ─────────────────────────────────────────────────────────────────────────

    // FIX: observeUserPresence() did not exist on UserRepository.
    //      Replaced with observeUserStatus(), which is the correct API.
    private suspend fun observePeerPresence(peerId: String) {
        userRepository.observeUserStatus(peerId).collect { status ->
            val now               = System.currentTimeMillis()
            val lastSeenMs        = status.lastSeen ?: 0L
            val heartbeatAge      = now - lastSeenMs
            val effectivelyOnline = status.online && heartbeatAge < onlineStalenessMs

            if (!effectivelyOnline) {
                _uiState.update { it.copy(isPeerTyping = false) }
            }

            _uiState.update { state ->
                state.copy(
                    isPeerOnline = effectivelyOnline,
                    lastSeen     = if (effectivelyOnline) "online" else formatLastSeen(lastSeenMs)
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 4 — Peer typing
    // ─────────────────────────────────────────────────────────────────────────

    // FIX: The previous implementation used raw Firebase RTDB calls (DatabaseReference,
    //      ValueEventListener, callbackFlow), duplicating logic that already exists
    //      inside TypingManager, which is owned and exposed by MessageRepository.
    //      The ViewModel should never reach past the repository layer into RTDB directly.
    //
    //      All typing observation is now delegated to messageRepository.observeTyping(),
    //      and all typing writes are delegated to messageRepository.setTyping() /
    //      clearTyping(). This also eliminates:
    //        - FirebaseDatabase injection
    //        - TypingPayload data class
    //        - selfTypingRef DatabaseReference cache
    //        - registerSelfTypingDisconnectHook()
    //        - pushSelfTyping()
    //        - tearDownTyping()
    //        - the typing watchdog job
    //        - peerTypingFlow() callbackFlow wrapper
    //      onDisconnect() cleanup is handled inside TypingManager.
    private suspend fun observePeerTyping(conversationId: String, peerId: String) {
        messageRepository.observeTyping(conversationId)
            .catch { _uiState.update { it.copy(isPeerTyping = false) } }
            .collect { typingState ->
                val isPeerTyping = peerId in typingState.typingUserIds
                _uiState.update { it.copy(isPeerTyping = isPeerTyping) }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 5 — Last-seen formatter
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatLastSeen(lastSeenMs: Long): String {
        if (lastSeenMs == 0L) return ""
        return lastSeenMs.toTimeAgo()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 6 — UI events
    // ─────────────────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        val conversationId = currentConversationId ?: return
        _uiState.update { it.copy(inputText = text, isSendError = false) }

        if (text.isNotBlank()) {
            viewModelScope.launch {
                messageRepository.setTyping(conversationId, true)
            }
            selfTypingStopJob?.cancel()
            selfTypingStopJob = viewModelScope.launch {
                delay(selfTypingIdleStopMs)
                messageRepository.setTyping(conversationId, false)
            }
        } else {
            stopSelfTyping()
        }
    }

    fun sendMessage() {
        val text           = _uiState.value.inputText.trim()
        val conversationId = currentConversationId
        if (text.isEmpty() || conversationId == null) return

        _uiState.update { it.copy(inputText = "", isSendError = false) }
        stopSelfTyping()

        viewModelScope.launch {
            // FIX: sendMessage(conversationId, senderId, content, type) did not match
            //      the MessageRepository API. The correct overload accepts a Message object.
            val message = Message(
                conversationId = conversationId,
                senderId       = currentUserId,
                content        = text,
                type           = MessageType.TEXT.name.lowercase()
            )
            val result = messageRepository.sendMessage(conversationId, message)
            if (result.isFailure) {
                _uiState.update { it.copy(inputText = text, isSendError = true) }
            }
        }
    }

    fun isMine(message: Message): Boolean =
        message.senderId == currentUserId

    // FIX: isReadByPeer() previously checked currentPeerId in message.readBy, but
    //      readBy does not exist on Message. Read state is tracked via message.status.
    //      A message sent by the current user is considered read by the peer when
    //      its status has been promoted to READ.
    fun isReadByPeer(message: Message): Boolean =
        message.senderId == currentUserId &&
                message.status == MessageStatus.READ.name.lowercase()

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 7 — Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun stopSelfTyping() {
        selfTypingStopJob?.cancel()
        selfTypingStopJob = null
        val conversationId = currentConversationId ?: return
        viewModelScope.launch {
            messageRepository.setTyping(conversationId, false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 8 — Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopSelfTyping()
        chatObservationJob?.cancel()
        peerProfileJob?.cancel()
        peerPresenceJob?.cancel()
        peerTypingJob?.cancel()
    }
}