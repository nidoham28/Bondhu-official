package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import com.nidoham.server.util.ParticipantRole
import com.nidoham.server.util.ParticipantType

/**
 * Represents a participant in a group or personal conversation.
 *
 * @property uid Firestore document ID, populated automatically on deserialization.
 * @property parentId ID of the parent entity (e.g., group or chat) this participant belongs to.
 * @property role Participant's role within the parent entity. Defaults to [ParticipantRole.MEMBER].
 * @property type Indicates whether the participation is personal or group-based. Defaults to [ParticipantType.PERSONAL].
 * @property joinedAt Server-assigned timestamp of when the participant joined.
 */
data class Participant(
    var uid: String = "",
    var parentId: String = "",
    var role: String = ParticipantRole.MEMBER.value,
    var type: String = ParticipantType.PERSONAL.value,
    @ServerTimestamp
    var joinedAt: Timestamp? = null,
)