package com.nidoham.server.domain.message

import com.google.firebase.Timestamp
import com.nidoham.server.util.MessageType

/**
 * A lightweight, denormalized snapshot of the most recent message in a conversation.
 *
 * Stored as a nested object within [Conversation.lastMessage]; it is not a
 * top-level Firestore document and therefore carries no [DocumentId] annotation.
 * [timestamp] is a plain copy of the original [Message.timestamp] — it must not
 * be annotated with [@ServerTimestamp], which would overwrite it on every parent
 * document write.
 *
 * @property messageId   ID of the source [Message] document.
 * @property parentId    ID of the parent conversation this preview belongs to.
 * @property content     Truncated or full message body for display in conversation lists.
 * @property senderId    Firebase UID of the message sender.
 * @property type        Message type. Defaults to [MessageType.TEXT].
 * @property timestamp   Timestamp copied from the source message at write time.
 * @property unreadCount Running count of unread messages for the current user.
 */
data class MessagePreview(
    var messageId: String = "",
    var parentId: String = "",
    var content: String = "",
    var senderId: String = "",
    var type: String = MessageType.TEXT.value,
    var timestamp: Timestamp? = null,
    var unreadCount: Int = 0,
)