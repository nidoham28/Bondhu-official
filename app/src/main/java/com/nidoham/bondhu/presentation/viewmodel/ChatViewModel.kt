package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nidoham.bondhu.data.repository.message.MessageRepository
import com.nidoham.bondhu.data.repository.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nidoham.server.domain.model.Message
import org.nidoham.server.domain.model.MessageType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    val lastSeen: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    // Staleness threshold: if the presence heartbeat is older than 15 minutes
    // we treat the peer as offline to prevent ghost-online states.
    private val onlineStalenessMs: Long = TimeUnit.MINUTES.toMillis(15)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val currentUserId: String get() = userRepository.getCurrentUserId() ?: ""

    private var currentConversationId: String? = null
    private var currentPeerId: String? = null

    private var chatObservationJob: Job? = null
    private var peerProfileJob: Job? = null
    private var peerPresenceJob: Job? = null

    // ─── Entry point ──────────────────────────────────────────────────────────

    fun initChat(conversationId: String) {
        if (currentConversationId == conversationId) return
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
                    }
            }

            // Stream 2: live message list.
            launch {
                messageRepository.observeLiveMessages(conversationId)
                    .catch { _uiState.update { state -> state.copy(isLoading = false, isSendError = true) } }
                    .collect { incoming ->
                        // observeLiveMessages returns newest-first; reverse for LazyColumn.
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

    // ─── Peer profile ─────────────────────────────────────────────────────────

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

    // ─── Peer presence ────────────────────────────────────────────────────────

    private suspend fun observePeerPresence(peerId: String) {
        userRepository.observeUserPresence(peerId).collect { status ->
            val now: Long = System.currentTimeMillis()
            // status.lastSeen is Long? — default to 0 if null so arithmetic is safe.
            val lastSeenMs: Long = status.lastSeen ?: 0L
            val heartbeatAge: Long = now - lastSeenMs
            val effectivelyOnline: Boolean = status.online && heartbeatAge < onlineStalenessMs

            _uiState.update { state ->
                state.copy(
                    isPeerOnline = effectivelyOnline,
                    lastSeen     = if (effectivelyOnline) "online" else formatLastSeen(lastSeenMs, now)
                )
            }
        }
    }

    // ─── Last-seen formatter ──────────────────────────────────────────────────

    /**
     * Converts a Unix-millis timestamp into a human-readable "last seen" string.
     *
     * Formatters are created here (not in a companion object) so they always
     * capture the locale at call time rather than at class-load time.
     *
     * Patterns:
     *   0L                   → "last seen: unavailable"
     *   Same calendar day    → "last seen today at 3:45 PM"
     *   Yesterday            → "last seen yesterday at 9:12 AM"
     *   Within the same year → "last seen Jan 14 at 6:00 PM"
     *   Older                → "last seen Mar 5, 2023 at 11:00 PM"
     */
    private fun formatLastSeen(lastSeenMs: Long, nowMs: Long): String {
        if (lastSeenMs == 0L) return "last seen: unavailable"

        val locale = Locale.getDefault()
        val timeFmt         = SimpleDateFormat("h:mm a", locale)
        val dateTimeFmt     = SimpleDateFormat("MMM d 'at' h:mm a", locale)
        val yearDateTimeFmt = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", locale)

        val lastSeenDate = Date(lastSeenMs)
        val millisPerDay = TimeUnit.DAYS.toMillis(1)
        val lastSeenDay  = lastSeenMs / millisPerDay
        val todayDay     = nowMs / millisPerDay

        return when {
            lastSeenDay == todayDay     -> "last seen today at ${timeFmt.format(lastSeenDate)}"
            lastSeenDay == todayDay - 1 -> "last seen yesterday at ${timeFmt.format(lastSeenDate)}"
            isSameCalendarYear(lastSeenDate, Date(nowMs)) -> "last seen ${dateTimeFmt.format(lastSeenDate)}"
            else -> "last seen ${yearDateTimeFmt.format(lastSeenDate)}"
        }
    }

    private fun isSameCalendarYear(a: Date, b: Date): Boolean {
        val cal = Calendar.getInstance()
        cal.time = a; val yearA = cal.get(Calendar.YEAR)
        cal.time = b; val yearB = cal.get(Calendar.YEAR)
        return yearA == yearB
    }

    // ─── UI events ────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text, isSendError = false) }
        val conversationId = currentConversationId ?: return
        viewModelScope.launch {
            messageRepository.setTyping(conversationId, currentUserId, text.isNotBlank())
        }
    }

    fun sendMessage() {
        val text           = _uiState.value.inputText.trim()
        val conversationId = currentConversationId
        if (text.isEmpty() || conversationId == null) return

        _uiState.update { it.copy(inputText = "", isSendError = false) }

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
            messageRepository.setTyping(conversationId, currentUserId, false)
        }
    }

    fun isMine(message: Message): Boolean =
        message.senderId == currentUserId

    fun isReadByPeer(message: Message): Boolean =
        currentPeerId?.let { it in message.readBy } ?: false

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        currentConversationId?.let { conversationId ->
            viewModelScope.launch {
                messageRepository.setTyping(conversationId, currentUserId, false)
            }
        }
        chatObservationJob?.cancel()
        peerProfileJob?.cancel()
        peerPresenceJob?.cancel()
    }
}