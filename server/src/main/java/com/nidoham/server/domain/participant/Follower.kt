package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

/**
 * Represents a user who follows the profile owner.
 *
 * Stored as a document in the `followers` sub-collection of the followed user.
 * [isFollowedByMe] is intentionally absent — whether the authenticated user
 * follows this person back is session-specific derived state and must be
 * computed at the repository layer, not persisted in Firestore.
 *
 * @property uid        Firestore document ID; matches the follower's Firebase UID.
 * @property followedAt Server-assigned timestamp of when the follow relationship
 *                      was established.
 */
data class Follower(
    @DocumentId
    var uid: String = "",
    @ServerTimestamp
    var followedAt: Timestamp? = null,
)