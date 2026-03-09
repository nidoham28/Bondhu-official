package org.nidoham.server.domain.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

// ─────────────────────────────────────────────────────────────────────────────
// Lightweight preview stored inside the Conversation document.
// Avoids embedding the full Message object (size limit + schema coupling).
// ─────────────────────────────────────────────────────────────────────────────

data class LastMessagePreview(
    @PropertyName("message_id")
    @JvmField var messageId: String = "",

    @PropertyName("sender_id")
    @JvmField var senderId: String = "",

    // Truncated to 100 chars before saving — safe for display
    @PropertyName("content")
    @JvmField var content: String = "",

    // Mirrors Message.type so the UI can show "📷 Photo" etc. without fetching
    @PropertyName("type")
    @JvmField var type: String = MessageType.TEXT.name.lowercase(),

    @PropertyName("timestamp")
    @JvmField var timestamp: Timestamp? = null,

    // Count of participants who haven't read this message yet
    @PropertyName("unread_count")
    @JvmField var unreadCount: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
// Conversation
// ─────────────────────────────────────────────────────────────────────────────

data class Conversation(
    @PropertyName("conversation_id")
    @JvmField var conversationId: String = "",

    @PropertyName("creator_id")
    @JvmField var creatorId: String = "",

    @PropertyName("title")
    @JvmField var title: String = "",

    @PropertyName("subtitle")
    @JvmField var subtitle: String? = null,

    @PropertyName("photo_url")
    @JvmField var photoUrl: String? = null,

    @PropertyName("type")
    @JvmField var type: String = ConversationType.PRIVATE.name.lowercase(),

    @PropertyName("participants")
    @JvmField var participants: List<Participant> = emptyList(),

    @PropertyName("participants_ids")
    @JvmField var participantsIds: List<String> = emptyList(),

    // Fixed: was Message? — replaced with lightweight preview to avoid
    // 1MB document limit breaches and Message schema coupling.
    @PropertyName("last_message")
    @JvmField var lastMessage: LastMessagePreview? = null,

    @PropertyName("message_pinned")
    @JvmField var messagePinned: List<String> = emptyList(),

    // Fixed: @ServerTimestamp only on createdAt — set once on document creation.
    @ServerTimestamp
    @PropertyName("created_at")
    @JvmField var createdAt: Timestamp? = null,

    // Fixed: removed @ServerTimestamp — updatedAt must be set explicitly
    // by the repository (e.g. when a new message arrives or metadata changes).
    @PropertyName("updated_at")
    @JvmField var updatedAt: Timestamp? = null,

    @PropertyName("subscriber_count")
    @JvmField var subscriberCount: Long = 0L,

    @PropertyName("message_count")
    @JvmField var messageCount: Long = 0L,

    @PropertyName("translated")
    @JvmField var translated: Boolean = false,

    @PropertyName("allow_share_message")
    @JvmField var allowShareMessage: Boolean = true,

    @PropertyName("admin_approval")
    @JvmField var adminApproval: Boolean = false
) {
    @get:Exclude
    val conversationType: ConversationType get() = ConversationType.fromString(type)

    @get:Exclude
    val isGroup: Boolean get() = conversationType == ConversationType.GROUP

    @get:Exclude
    val isChannel: Boolean get() = conversationType == ConversationType.CHANNEL

    @get:Exclude
    val isPrivate: Boolean get() = conversationType == ConversationType.PRIVATE

    fun getParticipant(userId: String): Participant? =
        participants.find { it.uid == userId }

    fun isParticipant(userId: String): Boolean =
        userId in participantsIds

    fun isAdmin(userId: String): Boolean =
        getParticipant(userId)?.toRole()
            ?.let { it == ParticipantRole.ADMIN || it == ParticipantRole.OWNER } ?: false
}

// ─────────────────────────────────────────────────────────────────────────────
// Participant
// ─────────────────────────────────────────────────────────────────────────────

data class Participant(
    @PropertyName("uid")
    @JvmField var uid: String = "",

    @PropertyName("role")
    @JvmField var role: String = ParticipantRole.MEMBER.name.lowercase(),

    @PropertyName("joined_at")
    @JvmField var joinedAt: Timestamp? = null,

    // last_seen here is the participant's last activity inside this conversation
    // (distinct from global online/offline presence handled by your other class).
    @PropertyName("last_seen")
    @JvmField var lastSeen: Timestamp? = null,

    @PropertyName("last_message_count")
    @JvmField var lastMessageCount: Long = 0L,

    @PropertyName("blocked")
    @JvmField var blocked: List<String> = emptyList(),

    @PropertyName("active")
    @JvmField var active: Boolean = true,

    // Fixed: was List<String> — muted scopes to this participant in this
    // conversation, so it's a simple Boolean flag, not a list.
    @PropertyName("muted")
    @JvmField var muted: Boolean = false,

    @PropertyName("read_indicator")
    @JvmField var readIndicator: Boolean = true,

    @PropertyName("typing_indicator")
    @JvmField var typingIndicator: Boolean = true,

    @PropertyName("nickname")
    @JvmField var nickname: List<Nickname> = emptyList()
) {
    @get:Exclude
    val participantRole: ParticipantRole get() = ParticipantRole.fromString(role)

    // Kept for backward compat with repository call sites
    fun toRole(): ParticipantRole = participantRole

    fun getNicknameFor(userId: String): String? =
        nickname.find { it.uid == userId }?.nickname

    // Unread count = total messages in conversation minus what this participant last saw
    fun unreadCount(totalMessageCount: Long): Long =
        (totalMessageCount - lastMessageCount).coerceAtLeast(0)
}

// ─────────────────────────────────────────────────────────────────────────────
// Nickname
// ─────────────────────────────────────────────────────────────────────────────

data class Nickname(
    @PropertyName("uid")
    @JvmField var uid: String = "",

    @PropertyName("nickname")
    @JvmField var nickname: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
// Enums
// ─────────────────────────────────────────────────────────────────────────────

enum class ConversationType {
    PRIVATE, GROUP, CHANNEL;

    companion object {
        fun fromString(value: String): ConversationType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: PRIVATE
    }
}

enum class ParticipantRole {
    OWNER, ADMIN, MEMBER;

    companion object {
        fun fromString(value: String): ParticipantRole =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: MEMBER
    }
}