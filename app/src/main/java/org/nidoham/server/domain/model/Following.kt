package org.nidoham.server.domain.model

import com.google.firebase.Timestamp

/**
 * Represents a user that the current account follows.
 *
 * Stored as a subcollection document under `users/{uid}/following/{userId}`.
 */
data class Following(
    /** UID of the user being followed. */
    val userId: String = "",

    /** Timestamp when the follow relationship was established. */
    val followedAt: Timestamp? = null
)