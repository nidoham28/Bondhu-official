package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import com.nidoham.server.util.ParticipantRole
import com.nidoham.server.util.ParticipantType

data class Participant(
    @DocumentId
    var uid: String? = null,

    @PropertyName("parent_id")
    var parentId: String? = null,

    @PropertyName("role")
    var role: String = ParticipantRole.MEMBER.value,

    @PropertyName("type")
    var type: String = ParticipantType.PERSONAL.value,

    @ServerTimestamp
    @PropertyName("joined_at")
    var joinedAt: Timestamp? = null,
)