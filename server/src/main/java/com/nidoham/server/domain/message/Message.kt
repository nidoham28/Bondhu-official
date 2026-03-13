package com.nidoham.server.domain.message

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import com.nidoham.server.util.MessageStatus
import com.nidoham.server.util.MessageType

/**
 * Represents a single message within a conversation or channel.
 *
 * @property messageId  Firestore document ID, populated automatically on deserialization.
 * @property parentId   ID of the parent conversation this message belongs to.
 * @property senderId   Firebase UID of the user who sent the message.
 * @property content    Message body; for non-text types this holds the resource URL or payload.
 * @property replyTo    Optional message ID this message is replying to.
 * @property timestamp  Server-assigned timestamp of when the message was written.
 * @property editedAt   Server-assigned timestamp of the last edit, null if never edited.
 * @property type       Message type. Defaults to [MessageType.TEXT].
 * @property status     Delivery status of the message. Defaults to [MessageStatus.PENDING].
 */
data class Message(
    @DocumentId
    var messageId: String = "",
    var parentId: String = "",
    var senderId: String = "",
    var content: String = "",
    var replyTo: String? = null,
    @ServerTimestamp
    var timestamp: Timestamp? = null,
    var editedAt: Timestamp? = null,
    var type: String = MessageType.TEXT.value,
    var status: String = MessageStatus.PENDING.value,
)