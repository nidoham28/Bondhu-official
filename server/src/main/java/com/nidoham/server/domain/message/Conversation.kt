package com.nidoham.server.domain.message

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import com.nidoham.server.util.ParticipantType

/**
 * Represents a conversation entity — either a direct (personal) message thread,
 * a group conversation, or a channel.
 *
 * The [parentId] field doubles as the Firestore document ID and the key used by
 * [ParticipantManager] to locate the participant sub-collection at
 * `participant/{parentId}/members/{uid}`.
 *
 * @property parentId        Firestore document ID; also serves as the participant parent key.
 * @property creatorId       Firebase UID of the user who created the conversation.
 * @property title           Display name for group conversations and channels; null for personal threads.
 * @property subtitle        Optional tagline or description.
 * @property photoUrl        imgbb-hosted photo URL for the conversation avatar.
 * @property type            Conversation type. Defaults to [ParticipantType.PERSONAL].
 * @property lastMessage     Denormalized preview of the most recent message.
 * @property createdAt       Server-assigned timestamp of when the conversation was created.
 * @property updatedAt       Server-assigned timestamp of the most recent write to this document.
 * @property subscriberCount Total number of participants in the conversation.
 * @property messageCount    Total number of messages sent in the conversation.
 * @property translated      Whether automatic translation is enabled for this conversation.
 */
data class Conversation(
    @DocumentId
    var parentId: String = "",
    var creatorId: String? = null,
    var title: String? = null,
    var subtitle: String? = null,
    var photoUrl: String? = null,
    var type: String = ParticipantType.PERSONAL.value,
    var lastMessage: MessagePreview? = null,
    @ServerTimestamp
    var createdAt: Timestamp? = null,
    @ServerTimestamp
    var updatedAt: Timestamp? = null,
    var subscriberCount: Long = 0L,
    var messageCount: Long = 0L,
    var translated: Boolean = false
)