package com.nidoham.server.repository.participant

import androidx.paging.PagingData
import com.nidoham.server.domain.participant.Follower
import com.nidoham.server.domain.participant.Following
import com.nidoham.server.domain.participant.User
import com.nidoham.server.manager.FriendshipManager
import com.nidoham.server.manager.PresenceManager
import com.nidoham.server.manager.PresenceManager.UserStatus
import com.nidoham.server.manager.UserManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for all user-related data operations.
 *
 * Coordinates [UserManager], [FriendshipManager], and [PresenceManager],
 * hiding multi-manager complexity from the ViewModel layer. All suspend
 * write functions return [Result] so callers can handle failures via
 * pattern matching rather than direct exception handling.
 *
 * Compound operations (follow, unfollow, removeFollower) are not fully
 * atomic across the friendship graph and the user counters — a network
 * failure between the two writes can leave counts inconsistent. For
 * production hardening, consolidate the counter updates into the
 * [FriendshipManager] batch or delegate the entire operation to a
 * Cloud Function.
 *
 * @param userManager       Handles all [User] document operations.
 * @param friendshipManager Handles follow graph reads and writes.
 * @param presenceManager   Handles real-time presence observation.
 */
@Singleton
class UserRepository @Inject constructor(
    private val userManager: UserManager,
    private val friendshipManager: FriendshipManager,
    private val presenceManager: PresenceManager
) {

    // ─────────────────────────────────────────────────────────────────────────
    // User CRUD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new [User] document in Firestore.
     * Typically called once during account registration.
     */
    suspend fun createUser(user: User): Result<Unit> =
        userManager.createUser(user)

    /**
     * Partially updates the user document identified by [uid]. Only the
     * provided [fields] keys are written; all other fields remain unchanged.
     * [fields] must not be empty. [User.updatedAt] is automatically appended
     * as a server timestamp by [UserManager].
     *
     * Common fields: `"displayName"`, `"photoUrl"`, `"bio"`, `"status"`.
     *
     * @return [Result.failure] with [IllegalArgumentException] if [fields] is empty.
     */
    suspend fun updateUser(uid: String, fields: Map<String, Any?>): Result<Unit> = runCatching {
        require(fields.isNotEmpty()) { "fields map must not be empty" }
        userManager.updateUser(uid, fields).getOrThrow()
    }

    /**
     * Permanently deletes the [User] document for [uid]. Does not cascade
     * to sub-collections or friendship data — full account deletion should
     * be handled by a Cloud Function.
     */
    suspend fun deleteUser(uid: String): Result<Unit> =
        userManager.deleteUser(uid)

    // ─────────────────────────────────────────────────────────────────────────
    // User Fetch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the [User] for the given [uid], or null if the document does
     * not exist.
     */
    suspend fun fetchUser(uid: String): Result<User?> =
        userManager.fetchUser(uid)

    /**
     * Returns true if a user document exists for [uid].
     */
    suspend fun userExists(uid: String): Result<Boolean> =
        userManager.userExists(uid)

    /**
     * Returns true if [username] is not already taken.
     */
    suspend fun isUsernameAvailable(username: String): Result<Boolean> =
        userManager.isUsernameAvailable(username)

    // ─────────────────────────────────────────────────────────────────────────
    // User Discovery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of all non-banned users, ordered by creation
     * date descending, excluding the current user's own document.
     */
    fun getPeopleStream(currentUid: String): Flow<PagingData<User>> =
        userManager.getPeopleStream(currentUid)

    /**
     * Returns a paginated stream of suggested users, excluding [currentUid]
     * and all UIDs in [alreadyFollowingUids], ordered by follower count
     * descending. For following lists larger than ~500 entries, delegate
     * this logic to a Cloud Function.
     */
    fun getMayYouKnowStream(
        currentUid: String,
        alreadyFollowingUids: Set<String>
    ): Flow<PagingData<User>> =
        userManager.getMayYouKnowStream(currentUid, alreadyFollowingUids)

    // ─────────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a paginated stream of users whose username starts with [queryText]. */
    fun searchByUsername(queryText: String): Flow<PagingData<User>> =
        userManager.searchByUsername(queryText)

    /** Returns a paginated stream of users whose display name starts with [queryText]. */
    fun searchByDisplayName(queryText: String): Flow<PagingData<User>> =
        userManager.searchByDisplayName(queryText)

    // ─────────────────────────────────────────────────────────────────────────
    // Follow Graph — Writes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Records that [currentUid] has followed [targetUid].
     *
     * Writes both sides of the friendship graph atomically via
     * [FriendshipManager], then increments the counter on each user document
     * in two separate writes. A failure between the batch commit and the
     * counter updates will leave counts inconsistent; see class-level KDoc.
     */
    suspend fun followUser(currentUid: String, targetUid: String): Result<Unit> = runCatching {
        friendshipManager.followUser(currentUid, targetUid).getOrThrow()
        userManager.incrementFollowingCount(currentUid).getOrThrow()
        userManager.incrementFollowerCount(targetUid).getOrThrow()
    }

    /**
     * Removes the follow relationship between [currentUid] and [targetUid].
     *
     * Deletes both sides of the friendship graph atomically, then decrements
     * the counter on each user document. See class-level KDoc for atomicity
     * caveats.
     */
    suspend fun unfollowUser(currentUid: String, targetUid: String): Result<Unit> = runCatching {
        friendshipManager.unfollowUser(currentUid, targetUid).getOrThrow()
        userManager.decrementFollowingCount(currentUid).getOrThrow()
        userManager.decrementFollowerCount(targetUid).getOrThrow()
    }

    /**
     * Removes [followerUid] from [currentUid]'s follower list.
     *
     * Deletes both sides of the friendship graph atomically, then decrements
     * the counter on each user document. See class-level KDoc for atomicity
     * caveats.
     */
    suspend fun removeFollower(currentUid: String, followerUid: String): Result<Unit> = runCatching {
        friendshipManager.removeFollower(currentUid, followerUid).getOrThrow()
        userManager.decrementFollowerCount(currentUid).getOrThrow()
        userManager.decrementFollowingCount(followerUid).getOrThrow()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Follow Graph — Reads
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if [currentUid] is currently following [targetUid]. */
    suspend fun isFollowing(currentUid: String, targetUid: String): Result<Boolean> =
        friendshipManager.isFollowing(currentUid, targetUid)

    /** Returns true if [followerUid] is in [currentUid]'s follower list. */
    suspend fun isFollowedBy(currentUid: String, followerUid: String): Result<Boolean> =
        friendshipManager.isFollowedBy(currentUid, followerUid)

    /** Returns a paginated stream of users that [uid] follows, ordered by follow date descending. */
    fun getFollowingStream(uid: String): Flow<PagingData<Following>> =
        friendshipManager.fetchFollowingPaged(uid)

    /**
     * Returns a paginated stream of users matching [queryText] against
     * [User.username]. For combined username and display name search,
     * a Cloud Function or dedicated search index (e.g. Algolia) is recommended,
     * as merging two independent Paging3 streams with deduplication is not
     * reliably achievable client-side.
     */
    fun searchUsers(queryText: String): Flow<PagingData<User>> =
        userManager.searchByUsername(queryText)

    /** Returns a paginated stream of users who follow [uid], ordered by follow date descending. */
    fun getFollowersStream(uid: String): Flow<PagingData<Follower>> =
        friendshipManager.fetchFollowersPaged(uid)

    // ─────────────────────────────────────────────────────────────────────────
    // Presence
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cold [Flow] that emits a fresh [UserStatus] each time [userId]'s
     * presence node changes in Firebase Realtime Database. Collect within
     * [androidx.lifecycle.viewModelScope] to ensure the listener is removed
     * when the screen is destroyed.
     */
    fun observeUserStatus(userId: String): Flow<UserStatus> =
        presenceManager.observeUserStatus(userId)

    // ─────────────────────────────────────────────────────────────────────────
    // Account State
    // ─────────────────────────────────────────────────────────────────────────

    /** Marks [uid] as banned. Banned users are excluded from all discovery streams. */
    suspend fun banUser(uid: String): Result<Unit> =
        userManager.banUser(uid)

    /** Reinstates a previously banned user. */
    suspend fun unbanUser(uid: String): Result<Unit> =
        userManager.unbanUser(uid)

    /** Marks [uid] as a verified user. */
    suspend fun verifyUser(uid: String): Result<Unit> =
        userManager.verifyUser(uid)

    // ─────────────────────────────────────────────────────────────────────────
    // Privacy
    // ─────────────────────────────────────────────────────────────────────────

    /** Updates all three privacy flags for [uid] in a single document write. */
    suspend fun updatePrivacySettings(
        uid: String,
        isPrivateAccount: Boolean,
        showLastSeen: Boolean,
        showPhotoUrl: Boolean
    ): Result<Unit> =
        userManager.updatePrivacySettings(uid, isPrivateAccount, showLastSeen, showPhotoUrl)

    // ─────────────────────────────────────────────────────────────────────────
    // Post Counters
    // ─────────────────────────────────────────────────────────────────────────

    /** Atomically increments [uid]'s post count by 1. Call when a post is published. */
    suspend fun incrementPostsCount(uid: String): Result<Unit> =
        userManager.incrementPostsCount(uid)

    /** Atomically decrements [uid]'s post count by 1. Call when a post is deleted. */
    suspend fun decrementPostsCount(uid: String): Result<Unit> =
        userManager.decrementPostsCount(uid)
}