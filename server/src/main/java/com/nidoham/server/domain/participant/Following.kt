package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * Represents a user that the profile owner follows.
 *
 * Stored as a document in the `following` sub-collection of the following user.
 *
 * @property uid        Firestore document ID; matches the followed user's Firebase UID.
 * @property followedAt Server-assigned timestamp of when the follow relationship
 *                      was established.
 */
data class Following(
    @DocumentId
    var uid: String = "",
    @ServerTimestamp
    var followedAt: Timestamp? = null,
)