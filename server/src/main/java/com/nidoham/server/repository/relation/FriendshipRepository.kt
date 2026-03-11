package com.nidoham.server.repository.relation

import androidx.paging.PagingData
import com.google.firebase.firestore.FirebaseFirestore
import com.nidoham.server.domain.participant.Follower
import com.nidoham.server.domain.participant.Following
import com.nidoham.server.manager.FollowerManager
import com.nidoham.server.manager.FollowingManager
import kotlinx.coroutines.flow.Flow

class FriendshipRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val pageSize: Int = 20
) {

    private val followingManager = FollowingManager(firestore, pageSize)
    private val followerManager = FollowerManager(firestore, pageSize)

    // ─── Following ────────────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of users that [currentUid] is following,
     * ordered by most recently followed.
     */
    fun getFollowingStream(currentUid: String): Flow<PagingData<Following>> =
        followingManager.getFollowingStream(currentUid)

    /**
     * Follows [targetUid] on behalf of [currentUid]. Performs a fan-out batch
     * write to keep both sides of the relationship in sync atomically.
     */
    suspend fun followUser(currentUid: String, targetUid: String) =
        followingManager.followUser(currentUid, targetUid)

    /**
     * Unfollows [targetUid] and removes the corresponding follower document
     * from [targetUid]'s collection in the same batch.
     */
    suspend fun unfollowUser(currentUid: String, targetUid: String) =
        followingManager.unfollowUser(currentUid, targetUid)

    /**
     * Returns true if [currentUid] is currently following [targetUid].
     */
    suspend fun isFollowing(currentUid: String, targetUid: String): Boolean =
        followingManager.isFollowing(currentUid, targetUid)

    // ─── Followers ────────────────────────────────────────────────────────────

    /**
     * Returns a paginated stream of [currentUid]'s followers, ordered by
     * most recently followed. Each item includes [Follower.isFollowedByMe]
     * to support mutual-follow indicators in the UI.
     */
    fun getFollowersStream(currentUid: String): Flow<PagingData<Follower>> =
        followerManager.getFollowersStream(currentUid)

    /**
     * Removes [followerUid] from [currentUid]'s follower list and cleans up
     * the corresponding following document on the other side.
     */
    suspend fun removeFollower(currentUid: String, followerUid: String) =
        followerManager.removeFollower(currentUid, followerUid)

    /**
     * Updates the denormalized [Follower.isFollowedByMe] flag. Should be called
     * immediately after [followUser] or [unfollowUser] to keep the flag current.
     */
    suspend fun updateIsFollowedByMe(currentUid: String, followerUid: String, isFollowed: Boolean) =
        followerManager.updateIsFollowedByMe(currentUid, followerUid, isFollowed)

    /**
     * Returns true if [followerUid] exists in [currentUid]'s follower collection.
     */
    suspend fun isFollowedBy(currentUid: String, followerUid: String): Boolean =
        followerManager.isFollowedBy(currentUid, followerUid)
}