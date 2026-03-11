package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp

/**
 * Represents a user profile document stored in Firestore.
 *
 * At least one of [email] or [phoneNumber] must be non-null,
 * depending on the authentication provider used during registration.
 */
data class User(

    // ─── Identity ─────────────────────────────────────────────────────────────
    val uid: String = "",
    val username: String = "",
    val displayName: String = "",
    val provider: String = "",

    // ─── Contact ──────────────────────────────────────────────────────────────
    val email: String? = null,
    val phoneNumber: String? = null,

    // ─── Profile ──────────────────────────────────────────────────────────────
    val photoUrl: String? = null,
    val coverUrl: String? = null,
    val bio: String? = null,
    val status: String? = null,

    // ─── Account State ────────────────────────────────────────────────────────
    val verified: Boolean = false,
    val banned: Boolean = false,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,

    // ─── Stats ────────────────────────────────────────────────────────────────
    val followingCount: Long = 0L,
    val followerCount: Long = 0L,
    val postsCount: Long = 0L,

    // ─── Privacy ──────────────────────────────────────────────────────────────
    val isPrivateAccount: Boolean = false,
    val showLastSeen: Boolean = true,
    val showPhotoUrl: Boolean = true
)