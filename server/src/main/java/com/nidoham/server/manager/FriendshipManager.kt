package com.nidoham.server.manager

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.nidoham.server.domain.participant.Follower
import com.nidoham.server.domain.participant.Following
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

/**
 * Manages all follow/unfollow operations and paginated follower and following
 * queries for the friendship graph.
 *
 * Firestore schema:
 *   friendship/{uid}/followers/{followerUid}  — [Follower] document
 *   friendship/{uid}/following/{followingUid} — [Following] document
 *
 * Every write that modifies the friendship graph touches both sides of the
 * relationship atomically in a single batch, ensuring that the follower and
 * following sub-collections are never left in an inconsistent state.
 *
 * @param firestore Injectable [FirebaseFirestore] instance.
 */
class FriendshipManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    companion object {
        private const val ROOT              = "friendship"
        private const val FOLLOWERS         = "followers"
        private const val FOLLOWING         = "following"
        private const val FIELD_FOLLOWED_AT = "followedAt"
        private const val PAGE_SIZE         = 20
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun followersCollection(uid: String) =
        firestore.collection(ROOT).document(uid).collection(FOLLOWERS)

    private fun followingCollection(uid: String) =
        firestore.collection(ROOT).document(uid).collection(FOLLOWING)

    private fun followerDocument(uid: String, followerUid: String) =
        followersCollection(uid).document(followerUid)

    private fun followingDocument(uid: String, targetUid: String) =
        followingCollection(uid).document(targetUid)

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Records that [currentUid] has followed [targetUid] by writing both sides
     * of the relationship atomically:
     *   - A [Following] document under [currentUid]'s following sub-collection.
     *   - A [Follower] document under [targetUid]'s followers sub-collection.
     *
     * [followedAt] is populated by the server via [@ServerTimestamp].
     *
     * @param currentUid The Firebase UID of the user performing the follow.
     * @param targetUid  The Firebase UID of the user being followed.
     */
    suspend fun followUser(currentUid: String, targetUid: String): Result<Unit> = runCatching {
        firestore.batch().apply {
            set(followingDocument(currentUid, targetUid), Following())
            set(followerDocument(targetUid, currentUid), Follower())
        }.commit().await()
    }

    /**
     * Removes the follow relationship between [currentUid] and [targetUid] by
     * deleting both sides atomically.
     *
     * @param currentUid The Firebase UID of the user performing the unfollow.
     * @param targetUid  The Firebase UID of the user being unfollowed.
     */
    suspend fun unfollowUser(currentUid: String, targetUid: String): Result<Unit> = runCatching {
        firestore.batch().apply {
            delete(followingDocument(currentUid, targetUid))
            delete(followerDocument(targetUid, currentUid))
        }.commit().await()
    }

    /**
     * Removes [followerUid] from [currentUid]'s followers by deleting both
     * sides of the relationship atomically.
     *
     * @param currentUid  The Firebase UID of the user removing the follower.
     * @param followerUid The Firebase UID of the follower being removed.
     */
    suspend fun removeFollower(currentUid: String, followerUid: String): Result<Unit> = runCatching {
        firestore.batch().apply {
            delete(followerDocument(currentUid, followerUid))
            delete(followingDocument(followerUid, currentUid))
        }.commit().await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if [followerUid] is following [currentUid].
     *
     * @param currentUid  The Firebase UID of the user whose followers are checked.
     * @param followerUid The Firebase UID of the candidate follower.
     */
    suspend fun isFollowedBy(currentUid: String, followerUid: String): Result<Boolean> = runCatching {
        followerDocument(currentUid, followerUid).get().await().exists()
    }

    /**
     * Returns true if [currentUid] is following [targetUid].
     *
     * @param currentUid The Firebase UID of the user whose following list is checked.
     * @param targetUid  The Firebase UID of the candidate followee.
     */
    suspend fun isFollowing(currentUid: String, targetUid: String): Result<Boolean> = runCatching {
        followingDocument(currentUid, targetUid).get().await().exists()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Paging3 — Followers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] over [uid]'s followers,
     * ordered by follow date descending.
     *
     * @param uid      The Firebase UID of the user whose followers to stream.
     * @param pageSize Documents per page; defaults to [PAGE_SIZE].
     */
    fun fetchFollowersPaged(
        uid: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Follower>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            FriendshipPagingSource(
                query = followersCollection(uid)
                    .orderBy(FIELD_FOLLOWED_AT, Query.Direction.DESCENDING),
                transform = { it.toObject(Follower::class.java) }
            )
        }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // Paging3 — Following
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] over the users that
     * [uid] follows, ordered by follow date descending.
     *
     * @param uid      The Firebase UID of the user whose following list to stream.
     * @param pageSize Documents per page; defaults to [PAGE_SIZE].
     */
    fun fetchFollowingPaged(
        uid: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Following>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            FriendshipPagingSource(
                query = followingCollection(uid)
                    .orderBy(FIELD_FOLLOWED_AT, Query.Direction.DESCENDING),
                transform = { it.toObject(Following::class.java) }
            )
        }
    ).flow
}

// ─────────────────────────────────────────────────────────────────────────────
// FriendshipPagingSource
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Generic Firestore-backed [PagingSource] shared by both followers and following
 * pagination. Cursor-based pagination via [DocumentSnapshot.startAfter] avoids
 * re-reading previously loaded pages. Only forward pagination is supported;
 * a refresh always restarts from the first page.
 *
 * [nextKey] is set to null as soon as the returned page is smaller than the
 * requested load size, which is the correct and only reliable signal that
 * Firestore has no further documents to return.
 *
 * @param T         The domain type to deserialize each document into.
 * @param query     The base Firestore [Query]; must not include a limit clause.
 * @param transform Maps a [DocumentSnapshot] to [T]; returning null skips the document.
 */
internal class FriendshipPagingSource<T : Any>(
    private val query: Query,
    private val transform: (DocumentSnapshot) -> T?
) : PagingSource<DocumentSnapshot, T>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, T>): DocumentSnapshot? = null

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, T> = try {
        val pageQuery = params.key
            ?.let { query.startAfter(it).limit(params.loadSize.toLong()) }
            ?: query.limit(params.loadSize.toLong())

        val snapshot = pageQuery.get().await()
        val items    = snapshot.documents.mapNotNull(transform)

        // A page smaller than the requested load size means Firestore has no
        // further documents — set nextKey to null to terminate paging.
        val nextKey = if (snapshot.size() < params.loadSize) null
        else snapshot.documents.lastOrNull()

        LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}