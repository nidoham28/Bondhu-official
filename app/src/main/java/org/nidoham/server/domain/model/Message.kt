package org.nidoham.server.domain.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

data class Message(
    @PropertyName("message_id")
    @JvmField var messageId: String = "",

    @PropertyName("conversation_id")
    @JvmField var conversationId: String = "",

    @PropertyName("sender_id")
    @JvmField var senderId: String = "",

    @PropertyName("content")
    @JvmField var content: String = "",

    @ServerTimestamp
    @PropertyName("timestamp")
    @JvmField var timestamp: Timestamp? = null,

    @PropertyName("read_by")
    @JvmField var readBy: List<String> = emptyList(),

    @PropertyName("attachments")
    @JvmField var attachments: List<Attachment> = emptyList(),

    @PropertyName("type")
    @JvmField var type: String = MessageType.TEXT.name.lowercase(),

    @PropertyName("status")
    @JvmField var status: String = MessageStatus.PENDING.name.lowercase(),

    @PropertyName("reply_to")
    @JvmField var replyTo: String? = null,

    @PropertyName("edited_at")
    @JvmField var editedAt: Timestamp? = null,

    @PropertyName("deleted")
    @JvmField var deleted: Boolean = false,

    @PropertyName("deleted_at")
    @JvmField var deletedAt: Timestamp? = null,

    @PropertyName("unsent")
    @JvmField var unsent: String = UnsentStatus.NONE.name.lowercase(),

    @PropertyName("unsent_at")
    @JvmField var unsentAt: Timestamp? = null,

    @PropertyName("searchable_content")
    @JvmField var searchableContent: String = ""
) {
    // Derived properties — excluded from Firestore serialization
    @get:Exclude
    val isEdited: Boolean get() = editedAt != null

    @get:Exclude
    val hasAttachments: Boolean get() = attachments.isNotEmpty()

    @get:Exclude
    val readCount: Int get() = readBy.size

    @get:Exclude
    val isUnsent: Boolean get() = unsent != UnsentStatus.NONE.name.lowercase()

    fun toType(): MessageType = MessageType.fromString(type)
    fun toStatus(): MessageStatus = MessageStatus.fromString(status)
    fun toUnsentStatus(): UnsentStatus = UnsentStatus.fromString(unsent)
    fun isReadBy(userId: String): Boolean = userId in readBy

    fun isVisibleTo(userId: String): Boolean {
        if (toUnsentStatus() == UnsentStatus.ALL) return false
        if (toUnsentStatus() == UnsentStatus.ONLY_ME && senderId != userId) return false
        return true
    }

    fun effectiveContent(userId: String): String {
        return if (isVisibleTo(userId)) content else ""
    }

    // Fixed: returns a copy instead of mutating `this`
    fun withSearchableContent(): Message {
        return this.copy(searchableContent = this.content.lowercase())
    }
}

data class Attachment(
    @PropertyName("type")
    @JvmField var type: String = AttachmentType.IMAGE.name.lowercase(),

    @PropertyName("url")
    @JvmField var url: String = "",

    @PropertyName("thumbnail_url")
    @JvmField var thumbnailUrl: String? = null,

    @PropertyName("name")
    @JvmField var name: String? = null,

    @PropertyName("size_bytes")
    @JvmField var sizeBytes: Long? = null,

    @PropertyName("width")
    @JvmField var width: Int? = null,

    @PropertyName("height")
    @JvmField var height: Int? = null,

    @PropertyName("duration_ms")
    @JvmField var durationMs: Long? = null,

    @PropertyName("mime_type")
    @JvmField var mimeType: String? = null
) {
    fun toType(): AttachmentType = AttachmentType.fromString(type)
}

enum class UnsentStatus {
    NONE, ONLY_ME, ALL;

    companion object {
        fun fromString(value: String): UnsentStatus =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: NONE
    }
}

enum class MessageType {
    TEXT, IMAGE;

    companion object {
        fun fromString(value: String): MessageType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: TEXT
    }
}

enum class MessageStatus {
    PENDING, SENDING, SENT, DELIVERED, READ, FAILED;

    companion object {
        fun fromString(value: String): MessageStatus =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}

enum class AttachmentType {
    IMAGE, LOCATION;  // FILE removed — it never existed

    companion object {
        fun fromString(value: String): AttachmentType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: IMAGE  // Fixed: FILE → IMAGE
    }
}