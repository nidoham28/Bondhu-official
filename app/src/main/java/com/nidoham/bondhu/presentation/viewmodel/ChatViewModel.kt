package com.nidoham.bondhu.presentation.viewmodel

import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.nidoham.bondhu.BondhuApp
import com.nidoham.bondhu.data.repository.message.MessageRepository
import com.nidoham.bondhu.data.repository.user.UserRepository
import dagger.hilt.android.internal.Contexts.getApplication
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
    private val firebaseDatabase: FirebaseDatabase      // injected — no singleton call inside VM
) : ViewModel() {

    // ── Thresholds ────────────────────────────────────────────────────────────

    /** Presence heartbeat older than this → treat peer as offline. */
    private val onlineStalenessMs: Long = TimeUnit.MINUTES.toMillis(15)

    /**
     * If we receive typing=true but never a following typing=false (peer crash /
     * network kill / process death), force-clear the indicator after this window.
     * Should be slightly longer than the idle-stop delay so normal flow never hits it.
     */
    private val typingStaleTimeoutMs: Long = 8_000L

    /**
     * After the local user stops changing the input for this long, push typing=false
     * so the peer's indicator doesn't freeze when the user puts down the phone.
     */
    private val selfTypingIdleStopMs: Long = 5_000L

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

    /** Fires if peer's typing=true is never resolved by a typing=false. */
    private var typingWatchdogJob: Job? = null

    /** Clears our own typing signal after the local user goes idle. */
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
        // Leaving the previous conversation — clean up its typing node first.
        tearDownTyping()
        currentConversationId = conversationId

        chatObservationJob?.cancel()
        chatObservationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Stream 1: watch the conversation document to learn the peer UID.
            launch {
                messageRepository.observeConversation(conversationId)
                    .collect { conversation ->
                        val peerId = conversation
                            ?.participantsIds
                            ?.firstOrNull { it != currentUserId }
                            ?: return@collect

                        if (peerId == currentPeerId) return@collect
                        currentPeerId = peerId

                        peerProfileJob?.cancel()
                        peerProfileJob = launch { observePeerProfile(peerId) }

                        peerPresenceJob?.cancel()
                        peerPresenceJob = launch { observePeerPresence(peerId) }

                        // Register our onDisconnect() hook now that we know the conversation
                        // and have a valid currentUserId.
                        registerSelfTypingDisconnectHook(conversationId)

                        peerTypingJob?.cancel()
                        peerTypingJob = launch { observePeerTypingRtdb(conversationId, peerId) }
                    }
            }

            // Stream 2: live message list.
            launch {
                messageRepository.observeLiveMessages(conversationId)
                    .catch { _uiState.update { state -> state.copy(isLoading = false, isSendError = true) } }
                    .collect { incoming ->
                        _uiState.update { it.copy(messages = incoming.reversed(), isLoading = false) }

                        val hasUnread = incoming.any { message ->
                            message.senderId != currentUserId && currentUserId !in message.readBy
                        }
                        if (hasUnread) {
                            messageRepository.markAllDelivered(conversationId, currentUserId)
                            messageRepository.syncReadPosition(conversationId, currentUserId)
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
            val now         = System.currentTimeMillis()
            val lastSeenMs  = status.lastSeen ?: 0L
            val heartbeatAge = now - lastSeenMs
            val effectivelyOnline = status.online && heartbeatAge < onlineStalenessMs

            // Peer going offline → clear any in-flight typing signal immediately.
            // Handles hard disconnects where typing=false was never delivered.
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
    // SECTION 4 — Peer typing  (Firebase RTDB — no repository)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a [Flow] that emits [TypingPayload] every time the peer's RTDB node
     * changes.  Uses [callbackFlow] so the [ValueEventListener] lifecycle is tied
     * directly to the coroutine scope that collects it — no manual removal needed.
     *
     * Path listened to:  typing/{conversationId}/{peerId}
     */
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
                // DatabaseError is not an Exception subclass, so wrap it.
                close(Exception("RTDB typing listener cancelled: ${error.message}"))
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }   // clean up when scope is cancelled
    }

    /**
     * Collects [peerTypingFlow] and updates [ChatUiState.isPeerTyping].
     *
     * Additional safety layers:
     *  • Stale-timestamp guard  — if the node is old (> [typingStaleTimeoutMs])
     *    when we first receive it (e.g. app restarted while peer was "typing"),
     *    we ignore a lingering true and treat the signal as expired.
     *  • Watchdog timer         — restarted on every typing=true event; fires
     *    if typing=false never arrives (peer crash / forced process kill).
     *  • Offline guard          — [observePeerPresence] calls [clearPeerTyping]
     *    the moment the peer is detected offline.
     *  • Flow error safety      — any stream exception calls [clearPeerTyping]
     *    so no ghost indicator is left on screen.
     */
    private suspend fun observePeerTypingRtdb(conversationId: String, peerId: String) {
        peerTypingFlow(conversationId, peerId)
            .catch { clearPeerTyping() }
            .collect { payload ->
                if (payload.isTyping) {
                    // Stale-guard: if the timestamp is suspiciously old (leftover from a
                    // previous session where onDisconnect() didn't fire in time), ignore it.
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

    // ── Watchdog ──────────────────────────────────────────────────────────────

    /**
     * Cancels any previous watchdog and starts a fresh one.
     * If it fires before the next typing event arrives, the indicator is cleared.
     */
    private fun restartTypingWatchdog() {
        typingWatchdogJob?.cancel()
        typingWatchdogJob = viewModelScope.launch {
            delay(typingStaleTimeoutMs)
            clearPeerTyping()
        }
    }

    /** Single source of truth for clearing the peer typing indicator. */
    private fun clearPeerTyping() {
        typingWatchdogJob?.cancel()
        typingWatchdogJob = null
        _uiState.update { it.copy(isPeerTyping = false) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 5 — Self typing  (Firebase RTDB — no repository)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registers an [onDisconnect()] hook on our own typing node so Firebase
     * automatically clears it if the client loses connectivity without calling
     * [pushSelfTyping(false)] explicitly (process kill, network drop, ANR, etc.).
     *
     * Called once when [currentPeerId] is first resolved so we always have a
     * valid [currentUserId] before writing.
     *
     * Path written to:  typing/{conversationId}/{currentUserId}
     */
    private fun registerSelfTypingDisconnectHook(conversationId: String) {
        val uid = currentUserId
        if (uid.isBlank()) return

        val ref = firebaseDatabase
            .getReference("typing")
            .child(conversationId)
            .child(uid)

        selfTypingRef = ref

        // onDisconnect() is a server-side instruction: Firebase executes it
        // automatically when this client's connection drops.
        ref.onDisconnect().setValue(
            mapOf("isTyping" to false, "timestamp" to 0L)
        )
    }

    /**
     * Pushes our own typing state to RTDB.
     *
     * - [isTyping] = true  → writes { isTyping: true,  timestamp: <now> }
     * - [isTyping] = false → writes { isTyping: false, timestamp: 0    }
     *
     * The write is fire-and-forget inside a [viewModelScope] coroutine so it
     * never blocks the UI.  [runCatching] ensures a transient RTDB failure
     * (e.g. momentary offline) does not propagate as an unhandled exception.
     *
     * Offline write safety: Firebase RTDB's disk persistence queues the write
     * locally and flushes it when connectivity is restored (enable
     * [FirebaseDatabase.getInstance().setPersistenceEnabled(true)] in your
     * Application class to activate this).
     */
    private fun pushSelfTyping(isTyping: Boolean) {
        val ref = selfTypingRef ?: return   // hook not yet registered — skip silently
        viewModelScope.launch {
            runCatching {
                val payload = if (isTyping) {
                    mapOf("isTyping" to true, "timestamp" to System.currentTimeMillis())
                } else {
                    mapOf("isTyping" to false, "timestamp" to 0L)
                }
                ref.setValue(payload).await()
            }
            // Failure is intentionally swallowed: typing is a best-effort signal.
            // onDisconnect() acts as the final safety net for the isTyping=true case.
        }
    }

    /**
     * Clears our typing node and cancels the [onDisconnect()] hook.
     * Call when leaving the screen so stale server-side instructions are removed.
     */
    private fun tearDownTyping() {
        val ref = selfTypingRef ?: return
        selfTypingRef = null
        viewModelScope.launch {
            runCatching {
                // Cancel the server-side onDisconnect() instruction — we are disconnecting
                // gracefully, so we don't need Firebase to clean up on our behalf.
                ref.onDisconnect().cancel().await()
                // Explicitly write false so the peer's indicator clears instantly,
                // without waiting for the server to notice we disconnected.
                ref.setValue(mapOf("isTyping" to false, "timestamp" to 0L)).await()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 6 — Last-seen formatter
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converts a Unix-millis timestamp into a human-readable "last seen" string.
     *
     * Rules (all times relative to [nowMs]):
     *   0L           → ""  (unknown — show nothing)
     *   < 60 s       → "last seen just a few moments ago"
     *   < 60 min     → "last seen Xs ago" / "last seen Xm ago"
     *   < 24 h       → "last seen Xh Xm ago"
     *   < 2 days     → "last seen 1 day ago"
     *   < 60 days    → "last seen X days ago"
     *   < 61 days    → "last seen 1 month ago"
     *   ≥ 61 days    → ""  (too old — omit entirely)
     */
    private fun formatLastSeen(lastSeenMs: Long): String {
        if (lastSeenMs == 0L) return ""

        val relative = lastSeenMs.toTimeAgo()
        return relative
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 7 — UI events
    // ─────────────────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text, isSendError = false) }
        if (selfTypingRef == null) return   // conversation not yet initialised

        if (text.isNotBlank()) {
            // Push typing=true and restart the idle-stop timer.
            pushSelfTyping(true)
            selfTypingStopJob?.cancel()
            selfTypingStopJob = viewModelScope.launch {
                delay(selfTypingIdleStopMs)
                pushSelfTyping(false)
            }
        } else {
            // Field cleared → push typing=false immediately, cancel idle timer.
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

        // Cancel idle timer and push typing=false before the network call so the
        // peer's indicator clears the moment the user taps Send.
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
        // Gracefully clear our typing signal and cancel the server-side hook.
        tearDownTyping()
        // Cancel the idle-stop timer so it doesn't fire after the scope is gone.
        selfTypingStopJob?.cancel()
        selfTypingStopJob = null
        // Immediately clear the peer indicator so it doesn't linger if the
        // screen is re-entered while coroutines are still winding down.
        clearPeerTyping()
        chatObservationJob?.cancel()
        peerProfileJob?.cancel()
        peerPresenceJob?.cancel()
        peerTypingJob?.cancel()
    }
}