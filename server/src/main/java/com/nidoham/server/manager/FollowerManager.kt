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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

class FollowerManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val pageSize: Int = 20
) {

    private fun followerCollection(uid: String) =
        firestore.collection("friendship").document(uid).collection("follower")

    private inner class FollowerPagingSource(
        private val query: Query
    ) : PagingSource<DocumentSnapshot, Follower>() {

        override fun getRefreshKey(state: PagingState<DocumentSnapshot, Follower>): DocumentSnapshot? = null

        override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, Follower> {
            return try {
                val pageQuery = params.key
                    ?.let { query.startAfter(it).limit(params.loadSize.toLong()) }
                    ?: query.limit(params.loadSize.toLong())

                val snapshot = pageQuery.get().await()
                val items = snapshot.documents.mapNotNull { it.toObject(Follower::class.java) }
                val nextKey = snapshot.documents.lastOrNull()
                    ?.takeIf { snapshot.size() >= params.loadSize }

                LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    fun getFollowersStream(currentUid: String): Flow<PagingData<Follower>> {
        val query = followerCollection(currentUid)
            .orderBy("followedAt", Query.Direction.DESCENDING)

        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = { FollowerPagingSource(query) }
        ).flow
    }

    suspend fun removeFollower(currentUid: String, followerUid: String) {
        val followerRef = followerCollection(currentUid).document(followerUid)
        val followingRef = firestore
            .collection("friendship").document(followerUid)
            .collection("following").document(currentUid)

        firestore.runBatch { batch ->
            batch.delete(followerRef)
            batch.delete(followingRef)
        }.await()
    }

    suspend fun updateIsFollowedByMe(currentUid: String, followerUid: String, isFollowed: Boolean) {
        followerCollection(currentUid).document(followerUid)
            .update("isFollowedByMe", isFollowed)
            .await()
    }

    suspend fun isFollowedBy(currentUid: String, followerUid: String): Boolean =
        followerCollection(currentUid).document(followerUid).get().await().exists()
}