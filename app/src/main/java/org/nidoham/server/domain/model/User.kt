package org.nidoham.server.domain.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

/**
 * Represents a registered user in the system.
 *
 * Stored in Firestore under the `users/{uid}` document path.
 * Social counts ([followingCount], [followerCount], [postsCount]) are denormalized
 * for read performance and should be updated via atomic increments.
 *
 * Boolean fields prefixed with `is` require [@PropertyName] to prevent Firestore's
 * Java reflection from stripping the prefix during serialization/deserialization.
 */
data class User(

    /** Firebase Authentication UID. Immutable after account creation. */
    val uid: String = "",

    /** Unique lowercase username (e.g. `"john_doe"`). Immutable after account creation. */
    val username: String = "",

    /** Publicly visible display name. Can be changed by the user. */
    val displayName: String = "",

    /** Email address. `null` for phone-authenticated accounts. */
    val email: String? = null,

    /** Phone number. `null` for email or Google-authenticated accounts. */
    val phoneNumber: String? = null,

    /** URL of the user's profile picture. */
    val photoUrl: String? = null,

    /** URL of the user's cover/banner photo. */
    val coverUrl: String? = null,

    /** Short biography or description shown on the profile. */
    val bio: String? = null,

    /** Custom status message (e.g. `"Available"`, `"Do not disturb"`). */
    val status: String? = null,

    /** Whether the account has an admin-granted verified badge. */
    val verified: Boolean = false,

    /** Whether the account has been banned or disabled by an admin. */
    @PropertyName("isBanned")
    val isBanned: Boolean = false,

    /** Authentication provider identifier (e.g. `"password"`, `"google.com"`, `"phone"`). */
    val provider: String = "",

    /** Timestamp when the account was created. */
    val createdAt: Timestamp? = null,

    /** Timestamp of the most recent profile update. */
    val updatedAt: Timestamp? = null,

    val followingCount: Long = 0,
    val followerCount: Long = 0,
    val postsCount: Long = 0,

    /** When `true`, only approved followers can view this account's content. */
    @PropertyName("isPrivateAccount")
    val isPrivateAccount: Boolean = false,

    /** When `true`, this user's last-seen timestamp is visible to others. */
    @PropertyName("showLastSeen")
    val showLastSeen: Boolean = true,

    /** When `true`, this user's profile photo is visible to others. */
    @PropertyName("showPhotoUrl")
    val showPhotoUrl: Boolean = true
){
    val isOnline: Boolean
        get() = true
}