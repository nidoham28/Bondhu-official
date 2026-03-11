package com.nidoham.server.repository.participant

import androidx.paging.PagingData
import com.nidoham.server.domain.participant.Follower
import com.nidoham.server.domain.participant.Following
import com.nidoham.server.domain.participant.User
import com.nidoham.server.manager.FollowerManager
import com.nidoham.server.manager.FollowingManager
import com.nidoham.server.manager.PresenceManager
import com.nidoham.server.manager.PresenceManager.UserStatus
import com.nidoham.server.manager.UserManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UserRepository acts as the single source of truth for all user-related data.
 *
 * It coordinates [UserManager], [FollowerManager], [FollowingManager], and
 * [PresenceManager] — hiding the multi-manager complexity from the ViewModel layer
 * and ensuring compound operations (e.g. follow/unfollow) remain consistent across
 * all affected collections.
 */
@Singleton
class UserRepository @Inject constructor(
    private val userManager: UserManager,
    private val followerManager: FollowerManager,
    private val followingManager: FollowingManager,
    private val presenceManager: PresenceManager
) {

    // ─── User CRUD ────────────────────────────────────────────────────────────

    /**
     * Creates a new user document in Firestore.
     * Typically called once during account registration.
     *
     * @return [Result.success] on success, or [Result.failure] wrapping the caught exception.
     */
    suspend fun createUser(user: User): Result<Unit> = runCatching {
        userManager.createUser(user)
    }

    /**
     * Partially updates a user document. Only the provided [fields] are modified;
     * all other fields remain unchanged. [User.updatedAt] is automatically appended.
     *
     * Example:
     * ```
     * updateUser(uid, mapOf("bio" to "Hello!", "status" to "Active"))
     * ```
     */
    suspend fun updateUser(uid: String, fields: Map<String, Any?>) {
        userManager.updateUser(uid, fields)
    }

    /**
     * Updates the profile of the user identified by [uid] using a flexible field map.
     *
     * Any key present in [fields] will be written to Firestore; omitted keys are left
     * unchanged. [User.updatedAt] is automatically appended by [UserManager.updateUser].
     *
     * Common profile fields: `"displayName"`, `"photoUrl"`, `"bio"`, `"status"`.
     *
     * @param uid    The UID of the user whose profile is being updated.
     * @param fields A map of Firestore field names to their new values.
     * @return [Result.success] on success, or [Result.failure] wrapping the caught exception.
     *
     * Example:
     * ```
     * updateProfile(uid, mapOf("displayName" to "Alice", "photoUrl" to "https://..."))
     * ```
     */
    suspend fun updateProfile(uid: String, fields: Map<String, Any?>): Result<Unit> = runCatching {
        require(fields.isNotEmpty()) { "fields map must not be empty" }
        userManager.updateUser(uid, fields)
    }

    /**
     * Permanently deletes the user document for [uid].
     *
     * Note: This does not cascade to subcollections or friendship data.
     * Full account deletion should be handled by a Cloud Function.
     */
    suspend fun deleteUser(uid: String) {
        userManager.deleteUser(uid)
    }

    // ─── User Fetch ───────────────────────────────────────────────────────────

    /**
     * Returns the [User] document for the currently authenticated user,
     * or null if the document does not exist.
     */
    suspend fun fetchCurrentUser(uid: String): User? =
        userManager.fetchCurrentUser(uid)

    /**
     * Returns the [User] document for any given [uid], or null if not found.
     */
    suspend fun fetchUserById(uid: String): User? =
        userManager.fetchUserById(uid)

    // ─── User Discovery ───────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of all non-banned users, ordered by creation
     * date descending. The current user's own document is excluded.
     */
    fun getPeopleStream(currentUid: String): Flow<PagingData<User>> =
        userManager.getPeopleStream(currentUid)

    /**
     * Returns a paginated stream of suggested users, excluding [currentUid] and
     * all UIDs in [alreadyFollowingUids]. Results are ordered by follower count
     * descending to surface popular accounts first.
     *
     * For following lists larger than ~500 entries, delegate this logic to a
     * Cloud Function to avoid loading the exclusion set on the client.
     */
    fun getMayYouKnowStream(
        currentUid: String,
        alreadyFollowingUids: Set<String>
    ): Flow<PagingData<User>> =
        userManager.getMayYouKnowStream(currentUid, alreadyFollowingUids)

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of users whose username starts with [queryText].
     */
    fun searchByUsername(queryText: String): Flow<PagingData<User>> =
        userManager.searchByUsername(queryText)

    /**
     * Returns a paginated stream of users whose display name starts with [queryText].
     */
    fun searchByDisplayName(queryText: String): Flow<PagingData<User>> =
        userManager.searchByDisplayName(queryText)

    // ─── Follow ───────────────────────────────────────────────────────────────

    /**
     * Follows [targetUid] on behalf of [currentUid].
     *
     * This operation atomically:
     * 1. Writes to [currentUid]'s `following` subcollection.
     * 2. Writes to [targetUid]'s `follower` subcollection.
     * 3. Increments [currentUid]'s `followingCount`.
     * 4. Increments [targetUid]'s `followerCount`.
     */
    suspend fun followUser(currentUid: String, targetUid: String) {
        followingManager.followUser(currentUid, targetUid)
        userManager.incrementFollowingCount(currentUid)
        userManager.incrementFollowerCount(targetUid)
    }

    /**
     * Unfollows [targetUid] on behalf of [currentUid].
     *
     * This operation atomically:
     * 1. Deletes from [currentUid]'s `following` subcollection.
     * 2. Deletes from [targetUid]'s `follower` subcollection.
     * 3. Decrements [currentUid]'s `followingCount`.
     * 4. Decrements [targetUid]'s `followerCount`.
     */
    suspend fun unfollowUser(currentUid: String, targetUid: String) {
        followingManager.unfollowUser(currentUid, targetUid)
        userManager.decrementFollowingCount(currentUid)
        userManager.decrementFollowerCount(targetUid)
    }

    /**
     * Returns true if [currentUid] is currently following [targetUid].
     */
    suspend fun isFollowing(currentUid: String, targetUid: String): Boolean =
        followingManager.isFollowing(currentUid, targetUid)

    // ─── Following Stream ─────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of all users that [currentUid] is following,
     * ordered by follow date descending.
     */
    fun getFollowingStream(currentUid: String): Flow<PagingData<Following>> =
        followingManager.getFollowingStream(currentUid)

    // ─── Followers ────────────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of all users who follow [currentUid],
     * ordered by follow date descending.
     */
    fun getFollowersStream(currentUid: String): Flow<PagingData<Follower>> =
        followerManager.getFollowersStream(currentUid)

    /**
     * Removes [followerUid] from [currentUid]'s followers.
     *
     * This operation atomically:
     * 1. Deletes from [currentUid]'s `follower` subcollection.
     * 2. Deletes from [followerUid]'s `following` subcollection.
     * 3. Decrements [currentUid]'s `followerCount`.
     * 4. Decrements [followerUid]'s `followingCount`.
     */
    suspend fun removeFollower(currentUid: String, followerUid: String) {
        followerManager.removeFollower(currentUid, followerUid)
        userManager.decrementFollowerCount(currentUid)
        userManager.decrementFollowingCount(followerUid)
    }

    /**
     * Updates the `isFollowedByMe` flag on [followerUid]'s document inside
     * [currentUid]'s `follower` subcollection. This flag indicates whether
     * [currentUid] follows [followerUid] back.
     */
    suspend fun updateIsFollowedByMe(
        currentUid: String,
        followerUid: String,
        isFollowed: Boolean
    ) {
        followerManager.updateIsFollowedByMe(currentUid, followerUid, isFollowed)
    }

    /**
     * Returns true if [followerUid] is in [currentUid]'s follower list.
     */
    suspend fun isFollowedBy(currentUid: String, followerUid: String): Boolean =
        followerManager.isFollowedBy(currentUid, followerUid)

    // ─── Presence ─────────────────────────────────────────────────────────────

    /**
     * Returns a cold [Flow] that emits a fresh [UserStatus] every time [userId]'s
     * presence node changes in Firebase Realtime Database.
     *
     * Collect this in the ViewModel using [androidx.lifecycle.viewModelScope] to
     * ensure the listener is removed when the screen is destroyed.
     */
    fun observeUserStatus(userId: String): Flow<UserStatus> =
        presenceManager.observeUserStatus(userId)

    // ─── Account State ────────────────────────────────────────────────────────

    /**
     * Marks [uid] as banned. Banned users are excluded from all discovery streams.
     */
    suspend fun banUser(uid: String) {
        userManager.banUser(uid)
    }

    /**
     * Reinstates a previously banned user.
     */
    suspend fun unbanUser(uid: String) {
        userManager.unbanUser(uid)
    }

    /**
     * Marks [uid] as a verified user.
     */
    suspend fun verifyUser(uid: String) {
        userManager.verifyUser(uid)
    }

    // ─── Privacy ──────────────────────────────────────────────────────────────

    /**
     * Updates all three privacy flags for [uid] in a single document write.
     */
    suspend fun updatePrivacySettings(
        uid: String,
        isPrivateAccount: Boolean,
        showLastSeen: Boolean,
        showPhotoUrl: Boolean
    ) {
        userManager.updatePrivacySettings(uid, isPrivateAccount, showLastSeen, showPhotoUrl)
    }

    // ─── Post Counter ─────────────────────────────────────────────────────────

    /**
     * Atomically increments [uid]'s post count by 1.
     * Call when [uid] publishes a new post.
     */
    suspend fun incrementPostsCount(uid: String) {
        userManager.incrementPostsCount(uid)
    }

    /**
     * Atomically decrements [uid]'s post count by 1.
     * Call when [uid] deletes a post.
     */
    suspend fun decrementPostsCount(uid: String) {
        userManager.decrementPostsCount(uid)
    }

    // ─── Existence Checks ─────────────────────────────────────────────────────

    /**
     * Returns true if a user document exists for the given [uid].
     */
    suspend fun userExists(uid: String): Boolean =
        userManager.userExists(uid)

    /**
     * Returns true if the given [username] is not already taken.
     */
    suspend fun isUsernameAvailable(username: String): Boolean =
        userManager.isUsernameAvailable(username)
}