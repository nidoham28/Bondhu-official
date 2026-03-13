package com.nidoham.server.manager

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.nidoham.server.domain.participant.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

/**
 * Manages all user document operations: creation, updates, deletion, paginated
 * streams, search, counter increments, and account state changes.
 *
 * All suspend write functions return [Result] so callers can handle failures
 * without catching exceptions directly. Counter operations are atomic via
 * [FieldValue.increment].
 *
 * @param firestore Injectable [FirebaseFirestore] instance.
 * @param pageSize  Default page size for all paginated streams.
 */
class UserManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val pageSize: Int = 20
) {

    private val usersCollection get() = firestore.collection("users")

    // ─────────────────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a new [User] document to Firestore, keyed by [User.uid].
     * Uses `set` so the document is fully overwritten if it already exists —
     * safe for first-time registration.
     */
    suspend fun createUser(user: User): Result<Unit> = runCatching {
        usersCollection.document(user.uid).set(user).await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the [User] for the given [uid], or null if the document does
     * not exist.
     */
    suspend fun fetchUser(uid: String): Result<User?> = runCatching {
        usersCollection.document(uid).get().await().toObject(User::class.java)
    }

    /**
     * Returns true if a user document exists for the given [uid].
     */
    suspend fun userExists(uid: String): Result<Boolean> = runCatching {
        usersCollection.document(uid).get().await().exists()
    }

    /**
     * Returns true if the given [username] is not already taken.
     * Requires a Firestore index on the `username` field.
     */
    suspend fun isUsernameAvailable(username: String): Result<Boolean> = runCatching {
        usersCollection
            .whereEqualTo("username", username)
            .limit(1)
            .get()
            .await()
            .isEmpty
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Update
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Partially updates the user document identified by [uid]. Only the supplied
     * [fields] keys are modified; all other fields remain untouched.
     * [User.updatedAt] is always appended automatically as a server timestamp.
     *
     * Example:
     * ```
     * updateUser(uid, mapOf("bio" to "Hello!", "status" to "Online"))
     * ```
     */
    suspend fun updateUser(uid: String, fields: Map<String, Any?>): Result<Unit> = runCatching {
        val payload = fields.toMutableMap()
        payload["updatedAt"] = FieldValue.serverTimestamp()
        usersCollection.document(uid).update(payload).await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Permanently deletes the user document for [uid]. Does not cascade to
     * sub-collections or related data — full account deletion should be
     * delegated to a Cloud Function.
     */
    suspend fun deleteUser(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid).delete().await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Paginated Streams
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of all non-banned users, ordered by
     * [User.createdAt] descending. The current user is excluded client-side
     * to avoid Firestore inequality-filter ordering constraints on the document ID.
     *
     * @param currentUid The authenticated user's UID; filtered out of results.
     */
    fun getPeopleStream(currentUid: String): Flow<PagingData<User>> {
        val query = usersCollection
            .whereEqualTo("banned", false)
            .orderBy("createdAt", Query.Direction.DESCENDING)

        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = {
                UserPagingSource(query, filter = { it.uid != currentUid })
            }
        ).flow
    }

    /**
     * Returns a paginated stream of suggested users, excluding [currentUid] and
     * all UIDs in [alreadyFollowingUids]. Results are ordered by
     * [User.followerCount] descending to surface popular accounts first.
     *
     * Client-side exclusion is acceptable for following lists up to ~500 entries.
     * For larger lists, delegate recommendation logic to a Cloud Function.
     *
     * @param currentUid          The authenticated user's UID.
     * @param alreadyFollowingUids UIDs that should be suppressed from suggestions.
     */
    fun getMayYouKnowStream(
        currentUid: String,
        alreadyFollowingUids: Set<String>
    ): Flow<PagingData<User>> {
        val excluded = alreadyFollowingUids + currentUid
        val query = usersCollection
            .whereEqualTo("banned", false)
            .orderBy("followerCount", Query.Direction.DESCENDING)

        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = {
                UserPagingSource(query, filter = { it.uid !in excluded })
            }
        ).flow
    }

    /**
     * Returns a paginated stream of users whose [User.username] starts with
     * [queryText]. Requires a Firestore index on `username` ascending.
     */
    fun searchByUsername(queryText: String): Flow<PagingData<User>> =
        buildSearchStream("username", queryText)

    /**
     * Returns a paginated stream of users whose [User.displayName] starts with
     * [queryText]. Requires a Firestore index on `displayName` ascending.
     */
    fun searchByDisplayName(queryText: String): Flow<PagingData<User>> =
        buildSearchStream("displayName", queryText)

    private fun buildSearchStream(field: String, queryText: String): Flow<PagingData<User>> {
        val query = usersCollection
            .whereGreaterThanOrEqualTo(field, queryText)
            .whereLessThanOrEqualTo(field, queryText + "\uf8ff")
            .orderBy(field, Query.Direction.ASCENDING)

        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = { UserPagingSource(query) }
        ).flow
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Counter Management
    // ─────────────────────────────────────────────────────────────────────────

    /** Atomically increments [User.followingCount] for [uid] by 1. */
    suspend fun incrementFollowingCount(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid).update("followingCount", FieldValue.increment(1)).await()
    }

    /** Atomically decrements [User.followingCount] for [uid] by 1. */
    suspend fun decrementFollowingCount(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid).update("followingCount", FieldValue.increment(-1)).await()
    }

    /** Atomically increments [User.followerCount] for [uid] by 1. */
    suspend fun incrementFollowerCount(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid).update("followerCount", FieldValue.increment(1)).await()
    }

    /** Atomically decrements [User.followerCount] for [uid] by 1. */
    suspend fun decrementFollowerCount(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid).update("followerCount", FieldValue.increment(-1)).await()
    }

    /** Atomically increments [User.postsCount] for [uid] by 1. */
    suspend fun incrementPostsCount(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid).update("postsCount", FieldValue.increment(1)).await()
    }

    /** Atomically decrements [User.postsCount] for [uid] by 1. */
    suspend fun decrementPostsCount(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid).update("postsCount", FieldValue.increment(-1)).await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Account State
    // ─────────────────────────────────────────────────────────────────────────

    /** Marks [uid] as banned. Banned users are excluded from all paginated streams. */
    suspend fun banUser(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid)
            .update("banned", true, "updatedAt", FieldValue.serverTimestamp())
            .await()
    }

    /** Reinstates a previously banned user. */
    suspend fun unbanUser(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid)
            .update("banned", false, "updatedAt", FieldValue.serverTimestamp())
            .await()
    }

    /** Marks [uid] as verified. */
    suspend fun verifyUser(uid: String): Result<Unit> = runCatching {
        usersCollection.document(uid)
            .update("verified", true, "updatedAt", FieldValue.serverTimestamp())
            .await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Privacy
    // ─────────────────────────────────────────────────────────────────────────

    /** Updates all three privacy flags for [uid] in a single document write. */
    suspend fun updatePrivacySettings(
        uid: String,
        isPrivateAccount: Boolean,
        showLastSeen: Boolean,
        showPhotoUrl: Boolean
    ): Result<Unit> = runCatching {
        usersCollection.document(uid).update(
            mapOf(
                "isPrivateAccount" to isPrivateAccount,
                "showLastSeen"     to showLastSeen,
                "showPhotoUrl"     to showPhotoUrl,
                "updatedAt"        to FieldValue.serverTimestamp()
            )
        ).await()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UserPagingSource
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Generic Firestore-backed [PagingSource] for [User] queries. An optional
 * [filter] predicate supports client-side exclusion (e.g. current user,
 * already-followed accounts) without requiring additional Firestore inequality
 * filters that impose ordering constraints.
 *
 * Paging terminates when the returned page is smaller than the requested load
 * size, which is the reliable signal that Firestore has no further documents.
 *
 * @param query  The base Firestore [Query]; must not include a `limit` clause.
 * @param filter Optional predicate; documents failing the check are dropped
 *               from the emitted page. Defaults to no filtering.
 */
internal class UserPagingSource(
    private val query: Query,
    private val filter: ((User) -> Boolean)? = null
) : PagingSource<DocumentSnapshot, User>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, User>): DocumentSnapshot? = null

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, User> = try {
        val pageQuery = params.key
            ?.let { query.startAfter(it).limit(params.loadSize.toLong()) }
            ?: query.limit(params.loadSize.toLong())

        val snapshot = pageQuery.get().await()
        val items = snapshot.documents
            .mapNotNull { it.toObject(User::class.java) }
            .let { users -> filter?.let(users::filter) ?: users }

        val nextKey = if (snapshot.size() < params.loadSize) null
        else snapshot.documents.lastOrNull()

        LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}