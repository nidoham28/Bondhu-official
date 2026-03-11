package com.nidoham.server.manager

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.nidoham.server.domain.participant.Following
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

class FollowingManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val pageSize: Int = 20
) {

    private fun followingCollection(uid: String) =
        firestore.collection("friendship").document(uid).collection("following")

    private inner class FollowingPagingSource(
        private val query: Query
    ) : PagingSource<DocumentSnapshot, Following>() {

        override fun getRefreshKey(state: PagingState<DocumentSnapshot, Following>): DocumentSnapshot? = null

        override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, Following> {
            return try {
                val pageQuery = params.key
                    ?.let { query.startAfter(it).limit(params.loadSize.toLong()) }
                    ?: query.limit(params.loadSize.toLong())

                val snapshot = pageQuery.get().await()
                val items = snapshot.documents.mapNotNull { it.toObject(Following::class.java) }
                val nextKey = snapshot.documents.lastOrNull()
                    ?.takeIf { snapshot.size() >= params.loadSize }

                LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    fun getFollowingStream(currentUid: String): Flow<PagingData<Following>> {
        val query = followingCollection(currentUid)
            .orderBy("followedAt", Query.Direction.DESCENDING)

        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = { FollowingPagingSource(query) }
        ).flow
    }

    suspend fun followUser(currentUid: String, targetUid: String) {
        val now = Timestamp.now()
        val followingRef = followingCollection(currentUid).document(targetUid)
        val followerRef = firestore
            .collection("friendship").document(targetUid)
            .collection("follower").document(currentUid)

        firestore.runBatch { batch ->
            batch.set(followingRef, mapOf("uid" to targetUid, "followedAt" to now))
            batch.set(followerRef, mapOf("uid" to currentUid, "followedAt" to now, "isFollowedByMe" to false))
        }.await()
    }

    suspend fun unfollowUser(currentUid: String, targetUid: String) {
        val followingRef = followingCollection(currentUid).document(targetUid)
        val followerRef = firestore
            .collection("friendship").document(targetUid)
            .collection("follower").document(currentUid)

        firestore.runBatch { batch ->
            batch.delete(followingRef)
            batch.delete(followerRef)
        }.await()
    }

    suspend fun isFollowing(currentUid: String, targetUid: String): Boolean =
        followingCollection(currentUid).document(targetUid).get().await().exists()
}