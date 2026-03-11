package com.nidoham.server.domain.message

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import com.nidoham.server.util.ParticipantType

data class Conversation(
    @get:PropertyName("id")
     @set:PropertyName("id")
     @field:DocumentId
     var id: String = "",

    @get:PropertyName("creator_id")
     @set:PropertyName("creator_id")
     var creatorId: String? = null,

    @get:PropertyName("title")
     @set:PropertyName("title")
     var title: String? = null,

    @get:PropertyName("subtitle")
     @set:PropertyName("subtitle")
     var subtitle: String? = null,

    @get:PropertyName("photo_url")
     @set:PropertyName("photo_url")
     var photoUrl: String? = null,

    @get:PropertyName("type")
     @set:PropertyName("type")
     var type: String = ParticipantType.PERSONAL.value,

    @get:PropertyName("last_message")
     @set:PropertyName("last_message")
     var lastMessage: String? = null,

    @field:ServerTimestamp
    @get:PropertyName("created_at")
    @set:PropertyName("created_at")
    var createdAt: Timestamp? = null,

    @get:PropertyName("updated_at")
    @set:PropertyName("updated_at")
    var updatedAt: Timestamp? = null,

    @get:PropertyName("subscriber_count")
    @set:PropertyName("subscriber_count")
    var subscriberCount: Long = 0L,

    @get:PropertyName("message_count")
    @set:PropertyName("message_count")
    var messageCount: Long = 0L,

    @get:PropertyName("translated")
    @set:PropertyName("translated")
    var translated: Boolean = false
)
