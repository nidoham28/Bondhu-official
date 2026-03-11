package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import com.nidoham.server.util.ParticipantRole
import com.nidoham.server.util.ParticipantType

data class Participant(

    @get:PropertyName("uid")
    @set:PropertyName("uid")
    @DocumentId
    var uid: String? = null,

    @get:PropertyName("id")
    @set:PropertyName("id")
    var id: String? = null,

    @get:PropertyName("role")
    @set:PropertyName("role")
    var role: String = ParticipantRole.MEMBER.value,

    @get:PropertyName("type")
    @set:PropertyName("type")
    var type: String = ParticipantType.PERSONAL.value,

    @ServerTimestamp
    @get:PropertyName("joined_at")
    @set:PropertyName("joined_at")
    var joinedAt: Timestamp? = null
)