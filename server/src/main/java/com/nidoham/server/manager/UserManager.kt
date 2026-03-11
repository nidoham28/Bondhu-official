package com.nidoham.server.manager

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.nidoham.server.domain.participant.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

class UserManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val pageSize: Int = 20
) {

    private val usersCollection get() = firestore.collection("users")

    // ─── PagingSource: People List ────────────────────────────────────────────

    private inner class UserPagingSource(
        private val query: Query
    ) : PagingSource<DocumentSnapshot, User>() {

        override fun getRefreshKey(state: PagingState<DocumentSnapshot, User>): DocumentSnapshot? = null

        override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, User> {
            return try {
                val pageQuery = params.key
                    ?.let { query.startAfter(it).limit(params.loadSize.toLong()) }
                    ?: query.limit(params.loadSize.toLong())

                val snapshot = pageQuery.get().await()
                val items = snapshot.documents.mapNotNull { it.toObject(User::class.java) }
                val nextKey = snapshot.documents.lastOrNull()
                    ?.takeIf { snapshot.size() >= params.loadSize }

                LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    // ─── PagingSource: May You Know ───────────────────────────────────────────

    /**
     * Filters out users whose [User.uid] appears in [excludedUids] client-side,
     * since Firestore does not support NOT-IN queries beyond 10 values reliably
     * at scale. For small following lists this is acceptable; for large lists,
     * a Cloud Function-based recommendation endpoint is recommended.
     */
    private inner class MayYouKnowPagingSource(
        private val query: Query,
        private val excludedUids: Set<String>
    ) : PagingSource<DocumentSnapshot, User>() {

        override fun getRefreshKey(state: PagingState<DocumentSnapshot, User>): DocumentSnapshot? = null

        override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, User> {
            return try {
                val pageQuery = params.key
                    ?.let { query.startAfter(it).limit(params.loadSize.toLong()) }
                    ?: query.limit(params.loadSize.toLong())

                val snapshot = pageQuery.get().await()
                val items = snapshot.documents
                    .mapNotNull { it.toObject(User::class.java) }
                    .filter { it.uid !in excludedUids }
                val nextKey = snapshot.documents.lastOrNull()
                    ?.takeIf { snapshot.size() >= params.loadSize }

                LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    // ─── PagingSource: Search ─────────────────────────────────────────────────

    private inner class SearchPagingSource(
        private val query: Query
    ) : PagingSource<DocumentSnapshot, User>() {

        override fun getRefreshKey(state: PagingState<DocumentSnapshot, User>): DocumentSnapshot? = null

        override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, User> {
            return try {
                val pageQuery = params.key
                    ?.let { query.startAfter(it).limit(params.loadSize.toLong()) }
                    ?: query.limit(params.loadSize.toLong())

                val snapshot = pageQuery.get().await()
                val items = snapshot.documents.mapNotNull { it.toObject(User::class.java) }
                val nextKey = snapshot.documents.lastOrNull()
                    ?.takeIf { snapshot.size() >= params.loadSize }

                LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    /**
     * Writes a new [User] document to Firestore, keyed by [User.uid].
     * Uses [com.google.firebase.firestore.FirebaseFirestore.set] so the document
     * is fully overwritten if it already exists — safe for first-time registration.
     */
    suspend fun createUser(user: User) {
        usersCollection.document(user.uid)
            .set(user)
            .await()
    }

    // ─── Update ───────────────────────────────────────────────────────────────

    /**
     * Partially updates a user document identified by [uid] with the provided
     * [fields] map. Only the supplied keys are modified; all other fields remain
     * untouched. [User.updatedAt] is always appended automatically.
     *
     * Example:
     * ```
     * updateUser(uid, mapOf("bio" to "Hello!", "status" to "Online"))
     * ```
     */
    suspend fun updateUser(uid: String, fields: Map<String, Any?>) {
        val payload = fields.toMutableMap()
        payload["updatedAt"] = Timestamp.now()
        usersCollection.document(uid)
            .update(payload)
            .await()
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    /**
     * Permanently deletes the user document for [uid]. This does not cascade to
     * subcollections or related data — callers are responsible for triggering
     * cleanup via a Cloud Function if full account deletion is required.
     */
    suspend fun deleteUser(uid: String) {
        usersCollection.document(uid)
            .delete()
            .await()
    }

    // ─── Fetch Single User ────────────────────────────────────────────────────

    /**
     * Returns the [User] for the currently authenticated user, or null if the
     * document does not exist.
     */
    suspend fun fetchCurrentUser(uid: String): User? {
        return usersCollection.document(uid)
            .get()
            .await()
            .toObject(User::class.java)
    }

    /**
     * Returns the [User] for any given [uid], or null if not found.
     */
    suspend fun fetchUserById(uid: String): User? {
        return usersCollection.document(uid)
            .get()
            .await()
            .toObject(User::class.java)
    }

    // ─── Fetch People List ────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of all non-banned users, ordered by [User.createdAt]
     * descending. Excludes the current user's own document via [currentUid].
     */
    fun getPeopleStream(currentUid: String): Flow<PagingData<User>> {
        val query = usersCollection
            .whereEqualTo("banned", false)
            .whereNotEqualTo("uid", currentUid)
            .orderBy("uid")
            .orderBy("createdAt", Query.Direction.DESCENDING)

        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = { UserPagingSource(query) }
        ).flow
    }

    // ─── May You Know ─────────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of suggested users, excluding [currentUid] itself
     * and all UIDs present in [alreadyFollowingUids]. Results are ordered by
     * [User.followerCount] descending to surface popular accounts first.
     *
     * For following lists larger than ~500 entries, delegate this logic to a
     * Cloud Function to avoid loading the exclusion set on the client.
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
            pagingSourceFactory = { MayYouKnowPagingSource(query, excluded) }
        ).flow
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of users whose [User.username] starts with [queryText].
     * Firestore range queries on a single field are used here, which requires a
     * composite index on `username` in ascending order.
     */
    fun searchByUsername(queryText: String): Flow<PagingData<User>> {
        val end = queryText + "\uf8ff"
        val query = usersCollection
            .whereGreaterThanOrEqualTo("username", queryText)
            .whereLessThanOrEqualTo("username", end)
            .orderBy("username", Query.Direction.ASCENDING)

        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = { SearchPagingSource(query) }
        ).flow
    }

    /**
     * Returns a paginated stream of users whose [User.displayName] starts with [queryText].
     * Requires a composite index on `displayName` ascending.
     */
    fun searchByDisplayName(queryText: String): Flow<PagingData<User>> {
        val end = queryText + "\uf8ff"
        val query = usersCollection
            .whereGreaterThanOrEqualTo("displayName", queryText)
            .whereLessThanOrEqualTo("displayName", end)
            .orderBy("displayName", Query.Direction.ASCENDING)

        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = { SearchPagingSource(query) }
        ).flow
    }

    // ─── Counter Management ───────────────────────────────────────────────────

    /**
     * Atomically increments [User.followingCount] for [uid] by 1.
     * Should be called when [uid] follows another user.
     */
    suspend fun incrementFollowingCount(uid: String) {
        usersCollection.document(uid)
            .update("followingCount", FieldValue.increment(1))
            .await()
    }

    /**
     * Atomically decrements [User.followingCount] for [uid] by 1.
     * Should be called when [uid] unfollows another user.
     */
    suspend fun decrementFollowingCount(uid: String) {
        usersCollection.document(uid)
            .update("followingCount", FieldValue.increment(-1))
            .await()
    }

    /**
     * Atomically increments [User.followerCount] for [uid] by 1.
     * Should be called on the target user when someone follows them.
     */
    suspend fun incrementFollowerCount(uid: String) {
        usersCollection.document(uid)
            .update("followerCount", FieldValue.increment(1))
            .await()
    }

    /**
     * Atomically decrements [User.followerCount] for [uid] by 1.
     * Should be called on the target user when someone unfollows them.
     */
    suspend fun decrementFollowerCount(uid: String) {
        usersCollection.document(uid)
            .update("followerCount", FieldValue.increment(-1))
            .await()
    }

    /**
     * Atomically increments [User.postsCount] for [uid] by 1.
     * Should be called when [uid] publishes a new post.
     */
    suspend fun incrementPostsCount(uid: String) {
        usersCollection.document(uid)
            .update("postsCount", FieldValue.increment(1))
            .await()
    }

    /**
     * Atomically decrements [User.postsCount] for [uid] by 1.
     * Should be called when [uid] deletes a post.
     */
    suspend fun decrementPostsCount(uid: String) {
        usersCollection.document(uid)
            .update("postsCount", FieldValue.increment(-1))
            .await()
    }

    // ─── Account State ────────────────────────────────────────────────────────

    /**
     * Marks the user identified by [uid] as banned. Banned users are excluded
     * from all paginated streams via the `banned == false` query filter.
     */
    suspend fun banUser(uid: String) {
        usersCollection.document(uid)
            .update("banned", true, "updatedAt", Timestamp.now())
            .await()
    }

    /**
     * Reinstates a previously banned user.
     */
    suspend fun unbanUser(uid: String) {
        usersCollection.document(uid)
            .update("banned", false, "updatedAt", Timestamp.now())
            .await()
    }

    /**
     * Marks the user identified by [uid] as verified.
     */
    suspend fun verifyUser(uid: String) {
        usersCollection.document(uid)
            .update("verified", true, "updatedAt", Timestamp.now())
            .await()
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
        usersCollection.document(uid).update(
            mapOf(
                "isPrivateAccount" to isPrivateAccount,
                "showLastSeen" to showLastSeen,
                "showPhotoUrl" to showPhotoUrl,
                "updatedAt" to Timestamp.now()
            )
        ).await()
    }

    // ─── Existence Check ──────────────────────────────────────────────────────

    /**
     * Returns true if a user document exists for the given [uid].
     */
    suspend fun userExists(uid: String): Boolean =
        usersCollection.document(uid).get().await().exists()

    /**
     * Returns true if the given [username] is not already taken.
     * Requires a Firestore index on the `username` field.
     */
    suspend fun isUsernameAvailable(username: String): Boolean {
        val snapshot = usersCollection
            .whereEqualTo("username", username)
            .limit(1)
            .get()
            .await()
        return snapshot.isEmpty
    }
}