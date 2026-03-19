package com.nidoham.server.manager

import com.nidoham.ai.api.zai.ZaiChatSession
import com.nidoham.ai.api.zai.extractContent
import com.nidoham.server.domain.message.Message
import com.nidoham.server.domain.message.MessagePreview
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.util.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class AiMessageManager @Inject constructor(
    private val chatSession: ZaiChatSession,
    private val messageRepository: MessageRepository
) {

    /**
     * Sends a user message to AI and writes AI response to conversation.
     * Note: This currently resets the session history for every request.
     * TODO: Consider loading existing conversation history from DB for context retention.
     */
    suspend fun push(
        userMessage: String,
        targetId: String,
        conversationId: String
    ) {
        // Reset history for this request (Stateless behavior)
        chatSession.clearHistory()

        when (val result = chatSession.chat(userMessage)) {

            is ZaiChatSession.ChatResult.Success -> {
                val content = result.message.extractContent()

                sendMessage(
                    content = content,
                    targetId = targetId,
                    conversationId = conversationId
                )
            }

            is ZaiChatSession.ChatResult.ApiError -> {
                Timber.e(
                    "AiMessageManager API error ${result.code} for conversation $conversationId : ${result.message}"
                )
            }

            is ZaiChatSession.ChatResult.ExceptionError -> {
                Timber.e(
                    result.exception,
                    "AiMessageManager exception for conversation $conversationId"
                )
            }
        }
    }

    private suspend fun sendMessage(
        content: String,
        targetId: String,
        conversationId: String
    ) {
        if (content.isBlank()) return

        withContext(Dispatchers.IO) {
            val message = Message(
                parentId = conversationId,
                senderId = targetId,
                content = content,
                type = MessageType.TEXT.value
            )

            messageRepository.sendMessage(conversationId, message)
                .onSuccess { messageId ->
                    val preview = MessagePreview(
                        messageId = messageId,
                        parentId = conversationId,
                        senderId = targetId,
                        content = content,
                        type = MessageType.TEXT.value
                    )

                    messageRepository.updateLastMessage(conversationId, preview)
                        .onFailure { e ->
                            Timber.w(e, "Failed updating last message for $conversationId")
                        }

                    messageRepository.incrementMessageCount(conversationId)
                        .onFailure { e ->
                            Timber.w(e, "Failed incrementing message count for $conversationId")
                        }
                }
                .onFailure { e ->
                    Timber.e(e, "Failed sending message to conversation $conversationId")
                }
        }
    }
}