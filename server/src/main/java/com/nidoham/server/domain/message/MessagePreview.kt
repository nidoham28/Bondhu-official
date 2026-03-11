package com.nidoham.server.domain.message

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName
import com.nidoham.server.util.MessageType

data class MessagePreview(
    @get:PropertyName("message_id")
    @set:PropertyName("message_id")
    var messageId: String = "",

    @get:PropertyName("content")
    @set:PropertyName("content")
    var content: String = "",

    @get:PropertyName("sender_id") @set:PropertyName("sender_id")
    var senderId: String = "",

    @get:PropertyName("type") @set:PropertyName("type")
    var type: String = MessageType.TEXT.name.lowercase(),

    @get:PropertyName("timestamp") @set:PropertyName("timestamp")
    var timestamp: Timestamp? = null,

    @get:PropertyName("unread_count") @set:PropertyName("unread_count")
    var unreadCount: Int = 0
)
