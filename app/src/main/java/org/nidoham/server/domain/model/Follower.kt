package org.nidoham.server.domain.model

import com.google.firebase.Timestamp

/**
 * Represents a user who follows the current account.
 *
 * Stored as a subcollection document under `users/{uid}/followers/{userId}`.
 */
data class Follower(
    /** UID of the user who is following this account. */
    val userId: String = "",

    /** Timestamp when the follow relationship was established. */
    val followedAt: Timestamp? = null,

    /** Whether the current account also follows this user back. */
    val isFollowedByMe: Boolean = false
)