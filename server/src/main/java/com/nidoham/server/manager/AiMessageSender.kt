package com.nidoham.server.manager

import com.nidoham.ai.api.zai.GenerativeAI
import com.nidoham.server.domain.message.Message
import com.nidoham.server.domain.message.MessagePreview
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.util.AiProvider
import com.nidoham.server.util.MessageType
import ai.z.openapi.service.model.ChatMessage
import ai.z.openapi.service.model.ChatMessageRole
import ai.z.openapi.service.model.ChatThinking
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget pipeline that drives a single AI reply turn.
 *
 * The caller passes all session parameters to [send] and discards the
 * coroutine. There are no return values and no callbacks. Every possible
 * outcome — a successful reply, a "coming soon" notice for an inactive
 * provider, or any exception — results in a [Message] written to Firestore
 * with [targetId] as the sender. Typing state is written to the conversation's
 * typing node under [targetId] so the peer's [observePeerTyping] stream
 * picks it up transparently.
 *
 * **Required repository extension:**
 * The existing `setTyping(conversationId, typing)` writes under the
 * current FirebaseAuth user's UID. To write typing under an arbitrary UID
 * (the AI bot's [targetId]), add the following overload to [MessageRepository]:
 * ```
 * suspend fun setTypingAs(
 *     conversationId: String,
 *     uid: String,
 *     typing: Boolean,
 * ): Result<Unit>
 * ```
 * All other surface area of this class is self-contained.
 */
@Singleton
class AiMessageSender @Inject constructor(
    private val messageRepository: MessageRepository,
) {

    // ── DB content strings ────────────────────────────────────────────────────

    private fun comingSoonText(provider: AiProvider) =
        "${provider.label} is coming soon. Stay tuned!"

    private val errorText = "Something went wrong. Please try again."

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes the full AI reply pipeline for one user turn.
     *
     * Designed to be launched as a fire-and-forget coroutine:
     * ```
     * viewModelScope.launch {
     *     aiMessageSender.send(userId, conversationId, targetId, text, provider, apiKey)
     * }
     * ```
     *
     * Pipeline:
     * 1. Write `targetId` typing = true via [MessageRepository.setTypingAs].
     * 2. Call the AI backend (ZAI only; all others skip to step 3 immediately).
     * 3. Push the result — reply text, coming-soon notice, or error — to
     *    Firestore as a [Message] with `senderId = targetId`.
     * 4. Write `targetId` typing = false in the `finally` block.
     *
     * @param userId         UID of the local user. Used to label turns in the
     *                       ZAI conversation history.
     * @param conversationId Firestore conversation document ID.
     * @param targetId       UID of the AI participant. Stamped on all written
     *                       messages as `senderId`.
     * @param userInput      Trimmed text the user just sent.
     * @param provider       AI backend to invoke.
     * @param apiKey         Forwarded to the active provider. Ignored when
     *                       [AiProvider.isLive] is false.
     */
    suspend fun send(
        userId: String,
        conversationId: String,
        targetId: String,
        userInput: String,
        provider: AiProvider,
        apiKey: String,
    ) {
        // Step 1 — mark the AI participant as typing.
        messageRepository.setTyping(
            conversationId = conversationId,
            uid            = targetId,
            typing         = true,
        ).onFailure { e ->
            // Non-fatal; the conversation still works without the indicator.
            Timber.w(e, "AiMessageSender[$conversationId]: could not set typing=true for $targetId")
        }

        try {
            // Step 2 — resolve reply text.
            val replyText: String = if (provider.isLive) {
                callZai(
                    userId         = userId,
                    conversationId = conversationId,
                    userInput      = userInput,
                    apiKey         = apiKey,
                )
            } else {
                Timber.d("AiMessageSender[$conversationId]: ${provider.label} not live — pushing coming-soon message")
                comingSoonText(provider)
            }

            // Step 3 — push reply to DB.
            pushMessage(
                conversationId = conversationId,
                senderId       = targetId,
                content        = replyText,
            )

        } catch (e: Exception) {
            // Step 3 (error branch) — push a safe error message so the user
            // is never left with an empty response and no feedback.
            Timber.e(e, "AiMessageSender[$conversationId]: pipeline failed, pushing error message")
            pushMessage(
                conversationId = conversationId,
                senderId       = targetId,
                content        = errorText,
            )

        } finally {
            // Step 4 — always clear typing regardless of outcome.
            messageRepository.setTyping(
                conversationId = conversationId,
                uid            = targetId,
                typing         = false,
            ).onFailure { e ->
                Timber.w(e, "AiMessageSender[$conversationId]: could not clear typing for $targetId")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ZAI call
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Calls the ZAI backend and returns the cleaned reply string.
     *
     * Conversation history is fetched fresh via [MessageRepository.observeMessages]
     * (single emission via [first]) so the model always has full context
     * without the caller needing to pass any additional state.
     *
     * [GenerativeAI] is stateless; history must be injected before every
     * turn via [GenerativeAI.setHistory]. A new instance is created per call
     * because the class is a lightweight value type backed by a reusable
     * [ZaiClient] configured in the companion factory.
     *
     * @throws Exception on network failure, API error, or empty choices,
     *         propagated to the caller's `catch` block for DB error write.
     */
    private suspend fun callZai(
        userId: String,
        conversationId: String,
        userInput: String,
        apiKey: String,
    ): String {
        // One-shot history snapshot. observeMessages emits a Flow; .first()
        // collects exactly one emission and cancels the upstream immediately.
        val history: List<Message> = try {
            messageRepository.observeMessages(conversationId).first()
        } catch (e: Exception) {
            Timber.w(e, "AiMessageSender[$conversationId]: failed to load history, proceeding without context")
            emptyList()
        }

        // Build the ZAI ChatMessage list from domain Message objects.
        val chatHistory: List<ChatMessage> = history.mapNotNull { msg ->

            val content = msg.content.trim()
            if (content.isBlank()) return@mapNotNull null

            val role = if (msg.senderId == userId) {
                ChatMessageRole.USER.value()
            } else {
                ChatMessageRole.ASSISTANT.value()
            }

            ChatMessage.builder()
                .role(role)
                .content(content)
                .build()
        }

        val ai = GenerativeAI.create(apiKey = apiKey)
        ai.setHistory(chatHistory)

        Timber.d(
            "AiMessageSender[$conversationId]: calling ZAI — " +
                    "historySize=${chatHistory.size}, input=\"$userInput\""
        )

        return when (val result = ai.sendMessage(userInput)) {
            is GenerativeAI.Result.Success -> {
                val raw     = GenerativeAI.toContent(result.message)
                val cleaned = GenerativeAI.filterContent(raw).trim()
                val text    = cleaned.ifBlank { "…" }
                Timber.d("AiMessageSender[$conversationId]: ZAI reply — \"$text\"")
                text
            }
            is GenerativeAI.Result.ApiError -> {
                // Re-throw so the outer catch writes the error message to DB.
                throw Exception("ZAI API error ${result.code}: ${result.message}")
            }
            is GenerativeAI.Result.ExceptionError -> {
                throw result.exception
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DB write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes [content] as a [Message] to Firestore and updates the conversation
     * preview and count, mirroring the pattern in [ChatViewModel.sendMessage].
     *
     * Failures are logged and not re-thrown; by the time this is called the
     * AI turn has already completed and there is no meaningful recovery path.
     */
    private suspend fun pushMessage(
        conversationId: String,
        senderId: String,
        content: String,
    ) {
        val message = Message(
            parentId = conversationId,
            senderId = senderId,
            content  = content,
            type     = MessageType.TEXT.value,
        )

        messageRepository.sendMessage(conversationId, message)
            .onSuccess { messageId ->
                val preview = MessagePreview(
                    messageId = messageId,
                    parentId  = conversationId,
                    senderId  = senderId,
                    content   = content,
                    type      = MessageType.TEXT.value,
                )

                messageRepository.updateLastMessage(conversationId, preview)
                    .onFailure { e ->
                        Timber.w(e, "AiMessageSender[$conversationId]: failed to update lastMessage")
                    }

                messageRepository.incrementMessageCount(conversationId)
                    .onFailure { e ->
                        Timber.w(e, "AiMessageSender[$conversationId]: failed to increment messageCount")
                    }
            }
            .onFailure { e ->
                Timber.e(e, "AiMessageSender[$conversationId]: failed to push message from $senderId")
            }
    }
}