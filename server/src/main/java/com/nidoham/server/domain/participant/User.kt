package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import com.nidoham.server.util.AuthProvider

/**
 * Represents a user profile document stored in Firestore under `users/{uid}`.
 *
 * At least one of [email] or [phoneNumber] must be non-null, depending on
 * the authentication provider used during registration.
 *
 * All fields are `var` to support Firestore's no-arg constructor and
 * reflective field population during deserialization. [uid] is populated
 * automatically from the document ID via [@DocumentId]. [createdAt],
 * [updatedAt], and [lastActiveAt] are server-assigned via [@ServerTimestamp]
 * and should be left null on write.
 *
 * @property uid             Firestore document ID; matches the Firebase Auth UID.
 * @property username        Unique, user-chosen handle used for search and mention.
 * @property displayName     Human-readable display name shown in the UI.
 * @property provider        Authentication provider used at registration.
 * @property email           Email address; non-null for password and Google providers.
 * @property phoneNumber     Phone number; non-null for phone provider.
 * @property photoUrl        imgbb-hosted profile photo URL.
 * @property coverUrl        imgbb-hosted cover photo URL.
 * @property bio             Optional short biography.
 * @property status          Optional user-set status message.
 * @property verified        Whether the account has been verified.
 * @property banned          Whether the account has been banned.
 * @property createdAt       Server-assigned timestamp of account creation.
 * @property updatedAt       Server-assigned timestamp of the last profile update.
 * @property lastActiveAt    Client-written timestamp of the user's last activity,
 *                           used for presence and last-seen display.
 * @property followingCount  Number of accounts this user follows.
 * @property followerCount   Number of accounts following this user.
 * @property postsCount      Total number of posts published by this user.
 * @property isPrivateAccount Whether the account requires a follow request.
 * @property showLastSeen    Whether last-seen timestamps are visible to others.
 * @property showPhotoUrl    Whether the profile photo is visible to others.
 */
data class User(

    // ─── Identity ─────────────────────────────────────────────────────────────
    @DocumentId
    var uid: String = "",
    var username: String = "",
    var displayName: String = "",
    var provider: String = AuthProvider.PASSWORD.value,

    // ─── Contact ──────────────────────────────────────────────────────────────
    var email: String? = null,
    var phoneNumber: String? = null,

    // ─── Profile ──────────────────────────────────────────────────────────────
    var photoUrl: String? = null,
    var coverUrl: String? = null,
    var bio: String? = null,
    var status: String? = null,

    // ─── Account State ────────────────────────────────────────────────────────
    var verified: Boolean = false,
    var banned: Boolean = false,
    @ServerTimestamp
    var createdAt: Timestamp? = null,
    @ServerTimestamp
    var updatedAt: Timestamp? = null,
    var lastActiveAt: Long? = null,

    // ─── Stats ────────────────────────────────────────────────────────────────
    var followingCount: Long = 0L,
    var followerCount: Long = 0L,
    var postsCount: Long = 0L,

    // ─── Privacy ──────────────────────────────────────────────────────────────
    var isPrivateAccount: Boolean = false,
    var showLastSeen: Boolean = true,
    var showPhotoUrl: Boolean = true,
)