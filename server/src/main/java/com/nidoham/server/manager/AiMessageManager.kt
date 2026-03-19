package com.nidoham.server.manager

import com.nidoham.ai.GenerativeAIWrapper
import com.nidoham.server.api.API
import com.nidoham.server.domain.message.Message
import com.nidoham.server.domain.message.MessagePreview
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.util.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class AiMessageManager(
    apiKey: String = API.apiKey,
    private val messageRepository: MessageRepository
) {
    private val generativeAI = GenerativeAIWrapper(
        provider = GenerativeAIWrapper.Provider.GLM,
        apiKey = apiKey
    )

    /**
     * Pushes user message to AI and sends the response to the conversation.
     */
    suspend fun push(userMessage: String, targetId: String, conversationId: String) {
        generativeAI.sendMessage(userMessage).fold(
            onSuccess = { response ->
                val content = response.content?.toString() ?: ""
                sendMessage(content, targetId, conversationId)
            },
            onFailure = { exception ->
                val errorMsg = exception.message ?: "AI request failed"
                Timber.e(exception, "AI push failed: $errorMsg")
                sendMessage(errorMsg, targetId, conversationId)
            }
        )
    }

    /**
     * Sends a message to the repository. Must be called from a coroutine.
     */
    private suspend fun sendMessage(content: String, targetId: String, conversationId: String) {
        if (content.isEmpty()) return

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
                            Timber.w(e, "AiMessageManager: failed to update lastMessage for $conversationId")
                        }

                    messageRepository.incrementMessageCount(conversationId)
                        .onFailure { e ->
                            Timber.w(e, "AiMessageManager: failed to increment messageCount for $conversationId")
                        }
                }
                .onFailure { e ->
                    Timber.e(e, "AiMessageManager: failed to send message")
                }
        }
    }
}