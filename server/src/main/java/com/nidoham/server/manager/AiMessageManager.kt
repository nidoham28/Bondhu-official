package com.nidoham.server.manager

import com.nidoham.ai.GenerativeAIWrapper
import com.nidoham.ai.api.zai.GenerativeAI
import com.nidoham.server.api.API
import com.nidoham.server.domain.message.Message
import com.nidoham.server.domain.message.MessagePreview
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.util.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class AiMessageManager(
    apiKey: String = API.apiKey,
    private val messageRepository: MessageRepository,
) {
    private val generativeAI = GenerativeAIWrapper(
        provider = GenerativeAIWrapper.Provider.GLM,
        apiKey = apiKey
    )

    /**
     * Sends [userMessage] to the AI and writes the response into [conversationId]
     * as a message from [targetId]. Failures are logged silently — no error
     * content is ever written to the conversation.
     */
    suspend fun push(userMessage: String, targetId: String, conversationId: String) {
        generativeAI.sendMessage(userMessage).fold(
            onSuccess = { response ->
                val content = GenerativeAI.toContent(response)
                sendMessage(content, targetId, conversationId)
            },
            onFailure = { exception ->
                Timber.e(exception, "AiMessageManager: push failed for conversation $conversationId")
            }
        )
    }

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
                    Timber.e(e, "AiMessageManager: failed to send message to $conversationId")
                }
        }
    }
}