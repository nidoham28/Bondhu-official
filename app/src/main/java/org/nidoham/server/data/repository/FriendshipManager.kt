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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import org.nidoham.server.domain.model.Follower
import javax.inject.Inject

private const val PAGE_SIZE = 20

// ==========================================
// Private PagingSources
// ==========================================

private class FollowingPagingSource(
    private val ref: CollectionReference
) : PagingSource<DocumentSnapshot, String>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, String>): DocumentSnapshot? = null

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, String> =
        runCatching {
            val snapshot = ref
                .orderBy("followedAt", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE.toLong())
                .startAfterIfPresent(params.key)
                .get().await()

            LoadResult.Page(
                data = snapshot.documents.map { it.id },
                prevKey = null,
                nextKey = snapshot.nextCursor()
            )
        }.getOrElse { LoadResult.Error(it) }
}

private class FollowersPagingSource(
    private val ref: CollectionReference
) : PagingSource<DocumentSnapshot, Follower>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Follower>): DocumentSnapshot? = null

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, Follower> =
        runCatching {
            val snapshot = ref
                .orderBy("followedAt", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE.toLong())
                .startAfterIfPresent(params.key)
                .get().await()

            LoadResult.Page(
                data = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(Follower::class.java)?.copy(userId = doc.id)
                },
                prevKey = null,
                nextKey = snapshot.nextCursor()
            )
        }.getOrElse { LoadResult.Error(it) }
}

private class FollowBackPagingSource(
    private val ref: CollectionReference
) : PagingSource<DocumentSnapshot, String>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, String>): DocumentSnapshot? = null

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, String> =
        runCatching {
            val snapshot = ref
                .whereEqualTo("isFollowedByMe", false)
                .orderBy("followedAt", Query.Direction.DESCENDING)
                .limit(PAGE_SIZE.toLong())
                .startAfterIfPresent(params.key)
                .get().await()

            LoadResult.Page(
                data = snapshot.documents.map { it.id },
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
// FriendshipManager
// ==========================================

/**
 * Manages all follow/unfollow operations and paginated social list queries.
 *
 * **Firestore structure:**
 * ```
 * friendship/{uid}/following/{targetUid}  → Following
 * friendship/{uid}/followers/{targetUid}  → Follower
 * ```
 *
 * All list queries are paginated via Paging3 at [PAGE_SIZE] items per page,
 * using a [DocumentSnapshot] cursor for stable keyset pagination.
 */
@Suppress("unused")
class FriendshipManager @Inject constructor(
    private val db: FirebaseFirestore
) {
    private fun followingRef(uid: String) =
        db.collection(ROOT_COLLECTION).document(uid).collection(FOLLOWING_SUB)

    private fun followersRef(uid: String) =
        db.collection(ROOT_COLLECTION).document(uid).collection(FOLLOWERS_SUB)

    // ==========================================
    // Write Operations
    // ==========================================

    /**
     * Makes [currentUserId] follow [targetUserId].
     *
     * Atomically:
     * 1. Adds [targetUserId] to [currentUserId]'s following subcollection.
     * 2. Adds [currentUserId] to [targetUserId]'s followers subcollection.
     * 3. If mutual, sets `isFollowedByMe = true` on [currentUserId]'s follower entry.
     */
    suspend fun follow(currentUserId: String, targetUserId: String) {
        db.runTransaction { transaction ->
            val myFollowingRef = followingRef(currentUserId).document(targetUserId)
            val theirFollowersRef = followersRef(targetUserId).document(currentUserId)
            val myFollowerEntry = followersRef(currentUserId).document(targetUserId)

            val isTargetFollowingMe = transaction.get(myFollowerEntry).exists()

            transaction.set(myFollowingRef, hashMapOf<String, Any?>(
                "userId" to targetUserId,
                "followedAt" to FieldValue.serverTimestamp()
            ))
            transaction.set(theirFollowersRef, hashMapOf<String, Any?>(
                "userId" to currentUserId,
                "followedAt" to FieldValue.serverTimestamp(),
                "isFollowedByMe" to isTargetFollowingMe
            ))

            if (isTargetFollowingMe) transaction.update(myFollowerEntry, "isFollowedByMe", true)

            null
        }.await()
    }

    /**
     * Makes [currentUserId] unfollow [targetUserId].
     *
     * Atomically:
     * 1. Removes [targetUserId] from [currentUserId]'s following subcollection.
     * 2. Removes [currentUserId] from [targetUserId]'s followers subcollection.
     * 3. If mutual, resets `isFollowedByMe = false` on [currentUserId]'s follower entry.
     */
    suspend fun unfollow(currentUserId: String, targetUserId: String) {
        db.runTransaction { transaction ->
            val myFollowingRef = followingRef(currentUserId).document(targetUserId)
            val theirFollowersRef = followersRef(targetUserId).document(currentUserId)
            val myFollowerEntry = followersRef(currentUserId).document(targetUserId)

            val wasMutual = transaction.get(myFollowerEntry).exists()

            transaction.delete(myFollowingRef)
            transaction.delete(theirFollowersRef)

            if (wasMutual) transaction.update(myFollowerEntry, "isFollowedByMe", false)

            null
        }.await()
    }

    // ==========================================
    // Point Queries
    // ==========================================

    /** Returns `true` if [currentUserId] is following [targetUserId]. */
    suspend fun isFollowing(currentUserId: String, targetUserId: String): Boolean =
        runCatching { followingRef(currentUserId).document(targetUserId).get().await().exists() }
            .getOrDefault(false)

    /** Returns `true` if [currentUserId] is followed by [targetUserId]. */
    suspend fun isFollower(currentUserId: String, targetUserId: String): Boolean =
        runCatching { followersRef(currentUserId).document(targetUserId).get().await().exists() }
            .getOrDefault(false)

    /**
     * Returns all UIDs that [uid] is currently following as a plain list.
     * For display lists, prefer [getFollowingList].
     */
    suspend fun getFollowingUserIds(uid: String): List<String> =
        runCatching { followingRef(uid).get().await().documents.map { it.id } }
            .getOrDefault(emptyList())

    // ==========================================
    // Paginated Queries
    // ==========================================

    /** Paginated UIDs that [uid] is following, ordered by most-recently-followed first. */
    fun getFollowingList(uid: String): Flow<PagingData<String>> =
        pager { FollowingPagingSource(followingRef(uid)) }.flow

    /** Paginated [Follower] objects for [uid], ordered by most-recently-followed first. */
    fun getFollowersList(uid: String): Flow<PagingData<Follower>> =
        pager { FollowersPagingSource(followersRef(uid)) }.flow

    /** Paginated UIDs who follow [uid] but have not yet been followed back. */
    fun getFollowBackRequests(uid: String): Flow<PagingData<String>> =
        pager { FollowBackPagingSource(followersRef(uid)) }.flow

    // ==========================================
    // Helpers
    // ==========================================

    private fun <T : Any> pager(factory: () -> PagingSource<DocumentSnapshot, T>) =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false), pagingSourceFactory = factory)

    companion object {
        private const val ROOT_COLLECTION = "friendship"
        private const val FOLLOWING_SUB = "following"
        private const val FOLLOWERS_SUB = "followers"
    }
}