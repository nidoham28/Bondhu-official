package com.nidoham.bondhu.presentation.viewmodel

import android.nidoham.server.repository.ParticipantManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.nidoham.bondhu.data.repository.message.MessageRepository
import com.nidoham.bondhu.data.repository.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.nidoham.server.domain.model.Message
import org.nidoham.server.domain.model.MessageType
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
// RTDB Typing Node Shape
//
// Path:  typing/{conversationId}/{userId}/
//   ├── isTyping  : Boolean   (true / false)
//   └── timestamp : Long      (System.currentTimeMillis — stale-guard on read)
//
// onDisconnect() sets isTyping=false + clears timestamp automatically when the
// client loses connectivity, so the peer's indicator never gets permanently stuck.
// ─────────────────────────────────────────────────────────────────────────────

private data class TypingPayload(
    val isTyping: Boolean = false,
    val timestamp: Long = 0L
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val firebaseDatabase: FirebaseDatabase,
    // FIX: injected to resolve the peer UID via the participants subcollection,
    //      since participantsIds was removed from the Conversation document.
    private val participantRepository: ParticipantManager
) : ViewModel() {

    // ── Thresholds ────────────────────────────────────────────────────────────

    private val onlineStalenessMs: Long      = TimeUnit.MINUTES.toMillis(15)
    private val typingStaleTimeoutMs: Long   = 8_000L
    private val selfTypingIdleStopMs: Long   = 5_000L

    // ── UI state ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val currentUserId: String get() = userRepository.getCurrentUserId() ?: ""

    // ── Session tracking ──────────────────────────────────────────────────────

    private var currentConversationId: String? = null
    private var currentPeerId: String? = null

    // ── Coroutine jobs ────────────────────────────────────────────────────────

    private var chatObservationJob: Job? = null
    private var peerProfileJob: Job? = null
    private var peerPresenceJob: Job? = null
    private var peerTypingJob: Job? = null
    private var typingWatchdogJob: Job? = null
    private var selfTypingStopJob: Job? = null

    // ── RTDB reference cache ──────────────────────────────────────────────────

    /**
     * Cached reference to our own typing node:
     *   typing/{conversationId}/{currentUserId}
     *
     * Kept so onDisconnect() is registered exactly once per conversation open,
     * and so cleanup on close can target the same reference without rebuilding it.
     */
    private var selfTypingRef: DatabaseReference? = null

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 1 — Entry point
    // ─────────────────────────────────────────────────────────────────────────

    fun initChat(conversationId: String) {
        if (currentConversationId == conversationId) return
        tearDownTyping()
        currentConversationId = conversationId

        chatObservationJob?.cancel()
        chatObservationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Stream 1: resolve the peer UID via the participants subcollection.
            //
            // FIX: conversation.participantsIds was removed from the Conversation
            // model. Peer ID is now resolved with a one-shot fetch from
            // ParticipantRepository, which owns the participants subcollection.
            // observeConversation is retained below only for live metadata updates.
            launch {
                val participants = participantRepository
                    .getParticipants(conversationId)
                    .getOrNull()
                    .orEmpty()

                val peerId = participants
                    .firstOrNull { it.uid != currentUserId }
                    ?.uid

                if (peerId == null) {
                    Timber.w("ChatViewModel: could not resolve peer UID for $conversationId")
                    return@launch
                }

                if (peerId == currentPeerId) return@launch
                currentPeerId = peerId as String?

                peerProfileJob?.cancel()
                peerProfileJob = launch { observePeerProfile(peerId) }

                peerPresenceJob?.cancel()
                peerPresenceJob = launch { observePeerPresence(peerId) }

                registerSelfTypingDisconnectHook(conversationId)

                peerTypingJob?.cancel()
                peerTypingJob = launch { observePeerTypingRtdb(conversationId, peerId) }
            }

            // Stream 2: live conversation metadata (subscriber_count, last_message, etc.)
            launch {
                messageRepository.observeConversation(conversationId)
                    .collect { _ ->
                        // Reserved for any future metadata-driven UI updates
                        // (e.g. displaying subscriber count in the toolbar).
                    }
            }

            // Stream 3: live message list.
            launch {
                messageRepository.observeLiveMessages(conversationId)
                    .catch { _uiState.update { state -> state.copy(isLoading = false, isSendError = true) } }
                    .collect { incoming ->
                        _uiState.update { it.copy(messages = incoming.reversed(), isLoading = false) }

                        val hasUnread = incoming.any { message ->
                            message.senderId != currentUserId && currentUserId !in message.readBy
                        }
                        if (hasUnread) {
                            // FIX: syncReadPosition was removed from MessageRepository because
                            // it depended on lastMessageCount embedded in the participant list,
                            // which no longer exists. markAllDelivered is still valid.
                            messageRepository.markAllDelivered(conversationId, currentUserId)
                        }
                    }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 2 — Peer profile
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun observePeerProfile(peerId: String) {
        userRepository.observeCurrentUser(peerId).collect { user ->
            user ?: return@collect
            _uiState.update { state ->
                state.copy(
                    peerName      = user.displayName.takeIf { it.isNotBlank() }
                        ?: user.username.takeIf { it.isNotBlank() }
                        ?: "Unknown",
                    peerAvatarUrl = user.photoUrl ?: ""
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 3 — Peer presence
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun observePeerPresence(peerId: String) {
        userRepository.observeUserPresence(peerId).collect { status ->
            val now              = System.currentTimeMillis()
            val lastSeenMs       = status.lastSeen ?: 0L
            val heartbeatAge     = now - lastSeenMs
            val effectivelyOnline = status.online && heartbeatAge < onlineStalenessMs

            if (!effectivelyOnline) clearPeerTyping()

            _uiState.update { state ->
                state.copy(
                    isPeerOnline = effectivelyOnline,
                    lastSeen     = if (effectivelyOnline) "online" else formatLastSeen(lastSeenMs)
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 4 — Peer typing  (Firebase RTDB)
    // ─────────────────────────────────────────────────────────────────────────

    private fun peerTypingFlow(
        conversationId: String,
        peerId: String
    ): Flow<TypingPayload> = callbackFlow {

        val ref = firebaseDatabase
            .getReference("typing")
            .child(conversationId)
            .child(peerId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isTyping  = snapshot.child("isTyping").getValue(Boolean::class.java) ?: false
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                trySend(TypingPayload(isTyping, timestamp))
            }

            override fun onCancelled(error: DatabaseError) {
                close(Exception("RTDB typing listener cancelled: ${error.message}"))
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private suspend fun observePeerTypingRtdb(conversationId: String, peerId: String) {
        peerTypingFlow(conversationId, peerId)
            .catch { clearPeerTyping() }
            .collect { payload ->
                if (payload.isTyping) {
                    val age = System.currentTimeMillis() - payload.timestamp
                    if (age > typingStaleTimeoutMs * 2) {
                        clearPeerTyping()
                        return@collect
                    }
                    _uiState.update { it.copy(isPeerTyping = true) }
                    restartTypingWatchdog()
                } else {
                    clearPeerTyping()
                }
            }
    }

    private fun restartTypingWatchdog() {
        typingWatchdogJob?.cancel()
        typingWatchdogJob = viewModelScope.launch {
            delay(typingStaleTimeoutMs)
            clearPeerTyping()
        }
    }

    private fun clearPeerTyping() {
        typingWatchdogJob?.cancel()
        typingWatchdogJob = null
        _uiState.update { it.copy(isPeerTyping = false) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 5 — Self typing  (Firebase RTDB)
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerSelfTypingDisconnectHook(conversationId: String) {
        val uid = currentUserId
        if (uid.isBlank()) return

        val ref = firebaseDatabase
            .getReference("typing")
            .child(conversationId)
            .child(uid)

        selfTypingRef = ref
        ref.onDisconnect().setValue(
            mapOf("isTyping" to false, "timestamp" to 0L)
        )
    }

    private fun pushSelfTyping(isTyping: Boolean) {
        val ref = selfTypingRef ?: return
        viewModelScope.launch {
            runCatching {
                val payload = if (isTyping) {
                    mapOf("isTyping" to true, "timestamp" to System.currentTimeMillis())
                } else {
                    mapOf("isTyping" to false, "timestamp" to 0L)
                }
                ref.setValue(payload).await()
            }
        }
    }

    private fun tearDownTyping() {
        val ref = selfTypingRef ?: return
        selfTypingRef = null
        viewModelScope.launch {
            runCatching {
                ref.onDisconnect().cancel().await()
                ref.setValue(mapOf("isTyping" to false, "timestamp" to 0L)).await()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 6 — Last-seen formatter
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatLastSeen(lastSeenMs: Long): String {
        if (lastSeenMs == 0L) return ""
        return lastSeenMs.toTimeAgo()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 7 — UI events
    // ─────────────────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text, isSendError = false) }
        if (selfTypingRef == null) return

        if (text.isNotBlank()) {
            pushSelfTyping(true)
            selfTypingStopJob?.cancel()
            selfTypingStopJob = viewModelScope.launch {
                delay(selfTypingIdleStopMs)
                pushSelfTyping(false)
            }
        } else {
            selfTypingStopJob?.cancel()
            selfTypingStopJob = null
            pushSelfTyping(false)
        }
    }

    fun sendMessage() {
        val text           = _uiState.value.inputText.trim()
        val conversationId = currentConversationId
        if (text.isEmpty() || conversationId == null) return

        _uiState.update { it.copy(inputText = "", isSendError = false) }

        selfTypingStopJob?.cancel()
        selfTypingStopJob = null
        pushSelfTyping(false)

        viewModelScope.launch {
            val result = messageRepository.sendMessage(
                conversationId = conversationId,
                senderId       = currentUserId,
                content        = text,
                type           = MessageType.TEXT
            )
            if (result.isFailure) {
                _uiState.update { it.copy(inputText = text, isSendError = true) }
            }
        }
    }

    fun isMine(message: Message): Boolean =
        message.senderId == currentUserId

    fun isReadByPeer(message: Message): Boolean =
        currentPeerId?.let { it in message.readBy } ?: false

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 8 — Cleanup
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        tearDownTyping()
        selfTypingStopJob?.cancel()
        selfTypingStopJob = null
        clearPeerTyping()
        chatObservationJob?.cancel()
        peerProfileJob?.cancel()
        peerPresenceJob?.cancel()
        peerTypingJob?.cancel()
    }
}