package org.nidoham.server.domain.model

import android.nidoham.server.domain.ParticipantType
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

// ─────────────────────────────────────────────────────────────────────────────
// Lightweight preview stored inside the Conversation document.
// Avoids embedding the full Message object (size limit + schema coupling).
// ─────────────────────────────────────────────────────────────────────────────

data class LastMessagePreview(
    // FIX #2: replaced @JvmField + @PropertyName with @get/@set targets,
    //         consistent with the Participant pattern and unambiguous for Firestore.
    @get:PropertyName("message_id") @set:PropertyName("message_id")
    var messageId: String = "",

    @get:PropertyName("sender_id") @set:PropertyName("sender_id")
    var senderId: String = "",

    @get:PropertyName("content") @set:PropertyName("content")
    var content: String = "",

    @get:PropertyName("type") @set:PropertyName("type")
    var type: String = MessageType.TEXT.name.lowercase(),

    @get:PropertyName("timestamp") @set:PropertyName("timestamp")
    var timestamp: Timestamp? = null,

    @get:PropertyName("unread_count") @set:PropertyName("unread_count")
    var unreadCount: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// Conversation
// ─────────────────────────────────────────────────────────────────────────────

data class Conversation(
    @field:DocumentId
    @get:PropertyName("conversation_id") @set:PropertyName("conversation_id")
    var conversationId: String = "",

    @get:PropertyName("creator_id") @set:PropertyName("creator_id")
    var creatorId: String = "",

    @get:PropertyName("title") @set:PropertyName("title")
    var title: String = "",

    @get:PropertyName("subtitle") @set:PropertyName("subtitle")
    var subtitle: String? = null,

    @get:PropertyName("photo_url") @set:PropertyName("photo_url")
    var photoUrl: String? = null,

    @get:PropertyName("type") @set:PropertyName("type")
    var type: String = ParticipantType.PERSONAL.value,

    @get:PropertyName("last_message") @set:PropertyName("last_message")
    var lastMessage: LastMessagePreview? = null,

    @field:ServerTimestamp
    @get:PropertyName("created_at") @set:PropertyName("created_at")
    var createdAt: Timestamp? = null,

    @get:PropertyName("updated_at") @set:PropertyName("updated_at")
    var updatedAt: Timestamp? = null,

    @get:PropertyName("subscriber_count") @set:PropertyName("subscriber_count")
    var subscriberCount: Long = 0L,

    @get:PropertyName("message_count") @set:PropertyName("message_count")
    var messageCount: Long = 0L,

    @get:PropertyName("translated") @set:PropertyName("translated")
    var translated: Boolean = false,

    @get:PropertyName("allow_share_message") @set:PropertyName("allow_share_message")
    var allowShareMessage: Boolean = true,

    @get:PropertyName("admin_approval") @set:PropertyName("admin_approval")
    var adminApproval: Boolean = false
) {
    @get:Exclude
    val conversationType: ParticipantType get() = ParticipantType.fromString(type)

    @get:Exclude
    val isGroup: Boolean get() = conversationType == ParticipantType.GROUP

    @get:Exclude
    val isChannel: Boolean get() = conversationType == ParticipantType.CHANNEL

    @get:Exclude
    val isPrivate: Boolean get() = conversationType == ParticipantType.PERSONAL
}