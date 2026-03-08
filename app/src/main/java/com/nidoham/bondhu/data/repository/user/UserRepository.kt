package com.nidoham.bondhu.data.repository.user

import androidx.paging.PagingData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.nidoham.server.data.repository.FriendshipManager
import org.nidoham.server.data.repository.SettingsManager
import org.nidoham.server.data.repository.UserManager
import org.nidoham.server.domain.model.Follower
import org.nidoham.server.domain.model.Settings
import org.nidoham.server.domain.model.User
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for all user-related operations.
 *
 * Orchestrates [UserManager], [FriendshipManager], and [SettingsManager]
 * so that ViewModels interact with one cohesive API.
 */
@Singleton
class UserRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val userManager: UserManager,
    private val friendshipManager: FriendshipManager,
    private val settingsManager: SettingsManager
) {

    // ==========================================
    // Auth
    // ==========================================

    /** Returns the currently authenticated user's UID, or `null` if not signed in. */
    fun getCurrentUserId(): String? = auth.currentUser?.uid

    /**
     * Returns the current UID wrapped in [Result], or a failure if no user is signed in.
     * Used internally to guard operations that require authentication.
     */
    private fun requireUserId(): Result<String> =
        getCurrentUserId()?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("No authenticated user."))

    // ==========================================
    // Registration
    // ==========================================

    /**
     * Creates a new [User] document and initializes default [Settings].
     * Both operations must succeed — if either fails the [Result] reflects the failure.
     * Should be called once at the end of the registration flow.
     */
    suspend fun create(user: User): Result<Unit> = runCatching {
        userManager.createNewUser(user).getOrThrow()
        settingsManager.createDefaultSettings(user.uid).getOrThrow()
    }

    // ==========================================
    // Profile
    // ==========================================

    /** Returns the [User] for any [uid], or `null` if not found. */
    suspend fun getUserProfile(uid: String): User? =
        userManager.getUser(uid)

    /**
     * Returns the [User] for [uid] wrapped in a [Result].
     * Prefer this over [getUserProfile] when the caller needs structured error handling.
     */
    suspend fun getUserById(uid: String): Result<User> = runCatching {
        userManager.getUser(uid) ?: throw NoSuchElementException("User '$uid' not found.")
    }

    /** Returns the currently signed-in user's [User] document, or `null` if not signed in. */
    suspend fun getCurrentUserProfile(): User? =
        getCurrentUserId()?.let { userManager.getUser(it) }

    /**
     * Emits the [User] document for [uid] in real time whenever the Firestore document changes.
     * Use this in ViewModels that need to react to live profile changes (e.g. bottom nav avatar).
     */
    fun observeCurrentUser(uid: String): Flow<User?> =
        userManager.observeUser(uid)

    /**
     * Partially updates the current user's profile.
     * Only fields present in [updates] are written; `updatedAt` is appended automatically.
     */
    suspend fun updateProfile(updates: Map<String, Any>): Result<Unit> {
        val id = requireUserId().getOrElse { return Result.failure(it) }
        return userManager.updateUserProfile(id, updates)
    }

    /**
     * Updates one or more privacy flags on the current user's [User] document.
     * Only non-null parameters are sent to Firestore.
     */
    suspend fun updatePrivacy(
        isPrivate: Boolean? = null,
        showLastSeen: Boolean? = null,
        showPhotoUrl: Boolean? = null
    ): Result<Unit> {
        val id = requireUserId().getOrElse { return Result.failure(it) }
        return userManager.updatePrivacySettings(id, isPrivate, showLastSeen, showPhotoUrl)
    }

    // ==========================================
    // Search
    // ==========================================

    /**
     * Searches for a [User] whose `username` exactly matches [username].
     * Returns `null` if no match is found.
     */
    suspend fun searchByUsername(username: String): User? =
        userManager.searchByUsername(username)

    /**
     * Returns a paginated page of [User]s whose usernames start with [query],
     * excluding the current user and anyone already followed.
     *
     * Pass [lastDocument] from the previous result to advance the cursor.
     * Returns an empty list with a `null` cursor if the user is not signed in.
     */
    suspend fun searchPeople(
        query: String,
        lastDocument: DocumentSnapshot? = null
    ): Pair<List<User>, DocumentSnapshot?> {
        val id = requireUserId().getOrElse { return Pair(emptyList(), null) }
        return userManager.searchUsers(id, query, lastDocument)
    }

    // ==========================================
    // People Suggestions (Paging3)
    // ==========================================

    /**
     * Returns a paginated [Flow] of [User]s that the current user is not yet following.
     * Intended for the "Suggested People" feed.
     * Emits an empty flow immediately if no user is signed in.
     */
    fun getSuggestedPeople(): Flow<PagingData<User>> {
        val id = getCurrentUserId() ?: return emptyFlow()
        return userManager.fetchNotFollowingUsersPaged(id)
    }

    // ==========================================
    // Social (Follow / Unfollow)
    // ==========================================

    /**
     * Makes the current user follow [targetId] and increments the relevant counters.
     * Counter updates are only applied after the friendship write succeeds.
     */
    suspend fun follow(targetId: String): Result<Unit> = runCatching {
        val id = requireUserId().getOrThrow()
        friendshipManager.follow(id, targetId)
        userManager.updateCounts(id, "followingCount", 1)
        userManager.updateCounts(targetId, "followerCount", 1)
    }

    /**
     * Makes the current user unfollow [targetId] and decrements the relevant counters.
     * Counter updates are only applied after the friendship delete succeeds.
     */
    suspend fun unfollow(targetId: String): Result<Unit> = runCatching {
        val id = requireUserId().getOrThrow()
        friendshipManager.unfollow(id, targetId)
        userManager.updateCounts(id, "followingCount", -1)
        userManager.updateCounts(targetId, "followerCount", -1)
    }

    /**
     * Returns `true` if the current user is following [targetId].
     * Returns `false` if no user is signed in.
     */
    suspend fun isFollowing(targetId: String): Boolean {
        val id = getCurrentUserId() ?: return false
        return friendshipManager.isFollowing(id, targetId)
    }

    /**
     * Paginated [Flow] of UIDs that the current user is following,
     * ordered by most-recently-followed first.
     * Emits an empty flow if no user is signed in.
     */
    fun getFollowingList(): Flow<PagingData<String>> {
        val id = getCurrentUserId() ?: return emptyFlow()
        return friendshipManager.getFollowingList(id)
    }

    /**
     * Paginated [Flow] of [Follower] objects for the current user,
     * ordered by most-recently-followed first.
     * Emits an empty flow if no user is signed in.
     */
    fun getFollowersList(): Flow<PagingData<Follower>> {
        val id = getCurrentUserId() ?: return emptyFlow()
        return friendshipManager.getFollowersList(id)
    }

    /**
     * Paginated [Flow] of UIDs who follow the current user but have not yet been followed back.
     * Emits an empty flow if no user is signed in.
     */
    fun getFollowBackRequests(): Flow<PagingData<String>> {
        val id = getCurrentUserId() ?: return emptyFlow()
        return friendshipManager.getFollowBackRequests(id)
    }

    // ==========================================
    // Settings
    // ==========================================

    /** Returns the current [Settings] for the signed-in user, or `null` if not signed in. */
    suspend fun getSettings(): Settings? =
        getCurrentUserId()?.let { settingsManager.getSettings(it) }

    /**
     * Partially updates the current user's [Settings].
     * Only fields present in [updates] are written; all others remain unchanged.
     */
    suspend fun updateSettings(updates: Map<String, Any>): Result<Unit> {
        val id = requireUserId().getOrElse { return Result.failure(it) }
        return settingsManager.updateSettings(id, updates)
    }
}