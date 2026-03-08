package org.nidoham.server.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.nidoham.server.domain.model.User
import timber.log.Timber
import javax.inject.Inject

private const val PAGE_SIZE = 20

// ==========================================
// Private PagingSource
// ==========================================

private class NotFollowingPagingSource(
    private val usersRef: CollectionReference,
    private val currentUserId: String,
    private val friendshipManager: FriendshipManager
) : PagingSource<DocumentSnapshot, User>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, User>): DocumentSnapshot? = null

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, User> =
        runCatching {
            val excludedIds = friendshipManager.getFollowingUserIds(currentUserId) + currentUserId

            val snapshot = usersRef
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE.toLong())
                .startAfterIfPresent(params.key)
                .get().await()

            LoadResult.Page(
                data = snapshot.documents
                    .mapNotNull { it.toObject(User::class.java) }
                    .filter { it.uid !in excludedIds },
                prevKey = null,
                nextKey = snapshot.nextCursor()
            )
        }.getOrElse { LoadResult.Error(it) }
}

// ==========================================
// Query Extensions
// ==========================================

private fun Query.startAfterIfPresent(cursor: DocumentSnapshot?): Query =
    if (cursor != null) startAfter(cursor) else this

/** Returns the last document as a pagination cursor, or `null` if this is the final page. */
private fun QuerySnapshot.nextCursor(): DocumentSnapshot? =
    documents.lastOrNull()?.takeIf { documents.size >= PAGE_SIZE }

// ==========================================
// UserManager
// ==========================================

/**
 * Manages all read/write operations for [User] documents in Firestore.
 *
 * **Firestore structure:**
 * ```
 * users/{uid}           → User
 * usernames/{username}  → { uid: String }
 * ```
 *
 * The `usernames` collection acts as a uniqueness index — reserved atomically
 * alongside user creation to prevent duplicate usernames.
 */
@Suppress("unused")
class UserManager @Inject constructor(
    private val db: FirebaseFirestore,
    private val friendshipManager: FriendshipManager
) {
    private fun usersRef() = db.collection(USERS_COLLECTION)
    private fun usernamesRef() = db.collection(USERNAMES_COLLECTION)

    // ==========================================
    // Write Operations
    // ==========================================

    /**
     * Creates a new [User] document, atomically reserving [User.username] in the
     * `usernames` index. Fails with [IllegalStateException] if the username is already taken.
     *
     * Should be called once at the end of the registration flow.
     */
    suspend fun createNewUser(user: User): Result<Unit> = runCatching {
        val username = user.username.lowercase()
        db.runTransaction { transaction ->
            val usernameRef = usernamesRef().document(username)
            if (transaction.get(usernameRef).exists()) {
                throw IllegalStateException("Username '$username' is already taken.")
            }
            transaction.set(usersRef().document(user.uid), user)
            transaction.set(usernameRef, mapOf("uid" to user.uid))
        }.await()
    }

    /**
     * Partially updates the [User] document for [uid].
     * Only fields present in [updates] are written; all others remain unchanged.
     * Automatically appends `updatedAt` with a server timestamp.
     */
    suspend fun updateUserProfile(uid: String, updates: Map<String, Any>): Result<Unit> = runCatching {
        val data = updates.toMutableMap().apply { put("updatedAt", FieldValue.serverTimestamp()) }
        usersRef().document(uid).update(data).await()
    }

    /**
     * Updates one or more privacy flags on the [User] document for [uid].
     * Only non-null parameters are included in the Firestore write.
     * Automatically appends `updatedAt` with a server timestamp.
     */
    suspend fun updatePrivacySettings(
        uid: String,
        isPrivate: Boolean? = null,
        showLastSeen: Boolean? = null,
        showPhotoUrl: Boolean? = null
    ): Result<Unit> = runCatching {
        val updates = mutableMapOf<String, Any>("updatedAt" to FieldValue.serverTimestamp())
        isPrivate?.let { updates["isPrivateAccount"] = it }
        showLastSeen?.let { updates["showLastSeen"] = it }
        showPhotoUrl?.let { updates["showPhotoUrl"] = it }
        usersRef().document(uid).update(updates).await()
    }

    /**
     * Atomically increments or decrements a numeric counter field on the [User] document.
     *
     * @param field The Firestore field name to modify (e.g. `"followerCount"`).
     * @param increment Positive to increment, negative to decrement.
     */
    suspend fun updateCounts(uid: String, field: String, increment: Long): Result<Unit> = runCatching {
        usersRef().document(uid).update(mapOf(field to FieldValue.increment(increment))).await()
    }

    // ==========================================
    // Read Operations
    // ==========================================

    /** Returns the [User] for [uid], or `null` on failure or if the document does not exist. */
    suspend fun getUser(uid: String): User? =
        runCatching {
            usersRef().document(uid).get().await().toObject(User::class.java)
        }.getOrNull()

    /** Emits the [User] for [uid] in real time whenever the Firestore document changes. */
    fun observeUser(uid: String): Flow<User?> = callbackFlow {
        val listener = usersRef().document(uid).addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObject(User::class.java))
        }
        awaitClose { listener.remove() }
    }

    // ==========================================
    // Search
    // ==========================================

    /**
     * Returns the [User] whose `username` field exactly matches [username],
     * or `null` if not found.
     */
    suspend fun searchByUsername(username: String): User? =
        runCatching {
            usersRef()
                .whereEqualTo("username", username.lowercase())
                .limit(1)
                .get().await()
                .documents.firstOrNull()
                ?.toObject(User::class.java)
        }.getOrNull()

    /**
     * Returns a paginated page of [User]s whose `username` starts with [query],
     * excluding [currentUserId] and users already followed.
     *
     * Client-side exclusion is applied because Firestore's `whereNotIn` is capped at 10 values.
     * Pass [lastDocument] from the previous result to advance the cursor.
     */
    suspend fun searchUsers(
        currentUserId: String,
        query: String,
        lastDocument: DocumentSnapshot? = null
    ): Pair<List<User>, DocumentSnapshot?> {
        return try {
            val excludedIds = friendshipManager.getFollowingUserIds(currentUserId) + currentUserId
            val term = query.lowercase()

            var q: Query = usersRef()
                .orderBy("username")
                .startAt(term)
                .endAt(term + "\uf8ff")
                .limit(PAGE_SIZE.toLong())

            if (lastDocument != null) q = q.startAfter(lastDocument)

            val snapshot = q.get().await()
            val results = snapshot.documents
                .mapNotNull { it.toObject(User::class.java) }
                .filter { it.uid !in excludedIds }

            Pair(results, snapshot.documents.lastOrNull())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "searchUsers failed for query='$query'")
            Pair(emptyList(), null)
        }
    }

    // ==========================================
    // Paginated Queries
    // ==========================================

    /**
     * Returns a Paging3 [Flow] of [User]s that [currentUserId] is not yet following,
     * ordered by most-recently-created first. Intended for the "Suggested People" feed.
     */
    fun fetchNotFollowingUsersPaged(currentUserId: String): Flow<PagingData<User>> =
        pager {
            NotFollowingPagingSource(
                usersRef = usersRef(),
                currentUserId = currentUserId,
                friendshipManager = friendshipManager
            )
        }.flow

    /**
     * Returns a paginated page of [User]s that [currentUserId] is not yet following.
     * For a Paging3 flow, prefer [fetchNotFollowingUsersPaged].
     */
    suspend fun fetchNotFollowingUsers(
        currentUserId: String,
        lastDocument: DocumentSnapshot? = null
    ): Pair<List<User>, DocumentSnapshot?> {
        return try {
            val excludedIds = friendshipManager.getFollowingUserIds(currentUserId) + currentUserId

            var query: Query = usersRef()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE.toLong())

            if (lastDocument != null) query = query.startAfter(lastDocument)

            val snapshot = query.get().await()
            val filtered = snapshot.documents
                .mapNotNull { it.toObject(User::class.java) }
                .filter { it.uid !in excludedIds }

            Pair(filtered, snapshot.documents.lastOrNull())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "fetchNotFollowingUsers failed for uid='$currentUserId'")
            Pair(emptyList(), null)
        }
    }

    // ==========================================
    // Helpers
    // ==========================================

    private fun <T : Any> pager(factory: () -> PagingSource<DocumentSnapshot, T>) =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false), pagingSourceFactory = factory)

    companion object {
        const val USERS_COLLECTION = "users"
        const val USERNAMES_COLLECTION = "usernames"
        private const val TAG = "UserManager"
    }
}