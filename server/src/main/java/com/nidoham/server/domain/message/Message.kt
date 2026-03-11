package com.nidoham.server.domain.message

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import com.nidoham.server.util.MessageStatus
import com.nidoham.server.util.MessageType

data class Message(
    @PropertyName("message_id")
    @JvmField var messageId: String = "",

    @PropertyName("conversation_id")
    @JvmField var conversationId: String = "",

    @PropertyName("sender_id")
    @JvmField var senderId: String = "",

    @PropertyName("content")
    @JvmField var content: String = "",

    @PropertyName("timestamp")
    @JvmField var timestamp: Timestamp? = null,

    @PropertyName("type")
    @JvmField var type: String = MessageType.TEXT.name.lowercase(),

    @PropertyName("status")
    @JvmField var status: String = MessageStatus.PENDING.name.lowercase(),

    @PropertyName("reply_to")
    @JvmField var replyTo: String? = null,

    @PropertyName("edited_at")
    @JvmField var editedAt: Timestamp? = null
)
