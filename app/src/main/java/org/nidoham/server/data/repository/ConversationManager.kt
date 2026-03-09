package org.nidoham.server.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.*
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.nidoham.server.domain.model.*
import java.util.Locale

class ConversationManager(private val firestore: FirebaseFirestore) {

    private val conversationRef: CollectionReference
        get() = firestore.collection("conversations")

    // ─────────────────────────────────────────────────────────────────────────
    // PAGING SOURCES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads conversations ordered by updated_at DESC.
     *
     * Fixed: cursor is now DocumentSnapshot (not Timestamp).
     * Timestamp cursors cause duplicate/skipped pages when two conversations
     * share the same updated_at value.
     */
    private class ConversationPagingSource(
        private val firestore: FirebaseFirestore,
        private val userId: String
    ) : PagingSource<DocumentSnapshot, Conversation>() {

        // Fixed: was returning updatedAt (Timestamp) which caused startAfter()
        // to skip the anchor item after invalidation. Returning null restarts
        // from the freshest page — correct for a chat list.
        override fun getRefreshKey(state: PagingState<DocumentSnapshot, Conversation>): DocumentSnapshot? = null

        override suspend fun load(
            params: LoadParams<DocumentSnapshot>
        ): LoadResult<DocumentSnapshot, Conversation> {
            return try {
                var query: Query = firestore.collection("conversations")
                    .whereArrayContains("participants_ids", userId)
                    .orderBy("updated_at", Query.Direction.DESCENDING)
                    .limit(params.loadSize.toLong())

                // Apply DocumentSnapshot cursor for precise pagination
                params.key?.let { query = query.startAfter(it) }

                val snapshot = query.get().await()
                val conversations = snapshot.documents
                    .mapNotNull { it.toObject(Conversation::class.java) }

                LoadResult.Page(
                    data = conversations,
                    prevKey = null,
                    nextKey = if (snapshot.documents.size < params.loadSize) null
                    else snapshot.documents.lastOrNull()
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    /**
     * Search PagingSource — prefix match on the `title` field.
     *
     * Fixed: was using .startAt(query).endAt(endQuery) as range cursors and
     * then .startAfter(documentSnapshot) as a page cursor. Firestore only
     * honours the last cursor set, so the range filter was silently discarded
     * on every page after the first. Now uses whereGreaterThanOrEqualTo /
     * whereLessThanOrEqualTo (field filters, not cursors) so startAfter()
     * can safely handle pagination independently.
     *
     * Requires Firestore composite index:
     *   Collection: conversations
     *   Fields: participants_ids (Array), title (Ascending)
     */
    private class ConversationSearchPagingSource(
        private val firestore: FirebaseFirestore,
        private val userId: String,
        private val searchQuery: String
    ) : PagingSource<DocumentSnapshot, Conversation>() {

        override fun getRefreshKey(state: PagingState<DocumentSnapshot, Conversation>): DocumentSnapshot? = null

        override suspend fun load(
            params: LoadParams<DocumentSnapshot>
        ): LoadResult<DocumentSnapshot, Conversation> {
            return try {
                val normalized = searchQuery.lowercase(Locale.getDefault()).trim()
                if (normalized.isEmpty()) {
                    return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
                }
                val endQuery = normalized + "\uf8ff"

                // Fixed: range expressed as field filters, NOT as Firestore cursors.
                // This leaves startAfter() free to act as the pagination cursor.
                var query: Query = firestore.collection("conversations")
                    .whereArrayContains("participants_ids", userId)
                    .whereGreaterThanOrEqualTo("title", normalized)
                    .whereLessThanOrEqualTo("title", endQuery)
                    .orderBy("title", Query.Direction.ASCENDING)
                    .limit(params.loadSize.toLong())

                params.key?.let { query = query.startAfter(it) }

                val snapshot = query.get().await()
                val conversations = snapshot.documents
                    .mapNotNull { it.toObject(Conversation::class.java) }

                LoadResult.Page(
                    data = conversations,
                    prevKey = null,
                    nextKey = if (snapshot.documents.size < params.loadSize) null
                    else snapshot.documents.lastOrNull()
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAGING FLOWS
    // ─────────────────────────────────────────────────────────────────────────

    fun getConversationPagingFlow(
        userId: String,
        pageSize: Int = 20
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false, prefetchDistance = pageSize / 2),
        pagingSourceFactory = { ConversationPagingSource(firestore, userId) }
    ).flow

    fun searchConversationsPagingFlow(
        userId: String,
        searchQuery: String,
        pageSize: Int = 20
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = { ConversationSearchPagingSource(firestore, userId, searchQuery) }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun createConversation(
        creatorId: String,
        title: String,
        type: ConversationType,
        initialParticipantIds: List<String> = emptyList(),
        photoUrl: String? = null
    ): Result<Conversation> = withContext(Dispatchers.IO) {
        try {
            val docRef = conversationRef.document()
            val now = Timestamp.now()

            val creatorParticipant = Participant(
                uid = creatorId,
                role = ParticipantRole.OWNER.name.lowercase(Locale.getDefault()),
                joinedAt = now,
                active = true
            )

            val otherParticipants = initialParticipantIds
                .filter { it != creatorId }
                .map { uid ->
                    Participant(
                        uid = uid,
                        role = ParticipantRole.MEMBER.name.lowercase(Locale.getDefault()),
                        joinedAt = now,
                        active = true
                    )
                }

            val allParticipants = listOf(creatorParticipant) + otherParticipants

            val newConversation = Conversation(
                conversationId = docRef.id,
                creatorId = creatorId,
                title = title,
                type = type.name.lowercase(Locale.getDefault()),
                photoUrl = photoUrl,
                participants = allParticipants,
                participantsIds = allParticipants.map { it.uid },
                createdAt = now,
                updatedAt = now,
                subscriberCount = allParticipants.size.toLong()
            )

            docRef.set(newConversation).await()
            Result.success(newConversation)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateConversationDetails(
        conversationId: String,
        title: String? = null,
        photoUrl: String? = null,
        adminApproval: Boolean? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updates = mutableMapOf<String, Any>("updated_at" to Timestamp.now())
            title?.let { updates["title"] = it }
            photoUrl?.let { updates["photo_url"] = it }
            adminApproval?.let { updates["admin_approval"] = it }

            conversationRef.document(conversationId).update(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes the conversation document.
     *
     * NOTE: Firestore does NOT auto-delete sub-collections. The
     * messages/{conversationId}/messages sub-collection must be deleted
     * separately — either via a Cloud Function triggered on document delete,
     * or by batch-deleting all message documents before calling this.
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            conversationRef.document(conversationId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARTICIPANT MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun addParticipant(
        conversationId: String,
        newUserId: String,
        role: ParticipantRole = ParticipantRole.MEMBER
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = conversationRef.document(conversationId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: throw Exception("Conversation not found")

                if (newUserId in conversation.participantsIds) {
                    throw Exception("User already in conversation")
                }

                val newParticipant = Participant(
                    uid = newUserId,
                    role = role.name.lowercase(Locale.getDefault()),
                    joinedAt = Timestamp.now(),
                    active = true
                )

                transaction.update(
                    docRef, mapOf(
                        "participants" to conversation.participants + newParticipant,
                        "participants_ids" to conversation.participantsIds + newUserId,
                        "subscriber_count" to FieldValue.increment(1),
                        "updated_at" to Timestamp.now()
                    )
                )
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeParticipant(
        conversationId: String,
        userIdToRemove: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = conversationRef.document(conversationId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: throw Exception("Conversation not found")

                transaction.update(
                    docRef, mapOf(
                        "participants" to conversation.participants.filter { it.uid != userIdToRemove },
                        "participants_ids" to conversation.participantsIds.filter { it != userIdToRemove },
                        "subscriber_count" to FieldValue.increment(-1),
                        "updated_at" to Timestamp.now()
                    )
                )
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun leaveConversation(
        conversationId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = conversationRef.document(conversationId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: throw Exception("Conversation not found")

                val participant = conversation.participants.find { it.uid == userId }
                    ?: throw Exception("User not in conversation")

                if (participant.toRole() == ParticipantRole.OWNER) {
                    throw Exception("Owner cannot leave. Transfer ownership or delete the group.")
                }

                transaction.update(
                    docRef, mapOf(
                        "participants" to conversation.participants.filter { it.uid != userId },
                        "participants_ids" to conversation.participantsIds.filter { it != userId },
                        "subscriber_count" to FieldValue.increment(-1),
                        "updated_at" to Timestamp.now()
                    )
                )
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateParticipantRole(
        conversationId: String,
        userId: String,
        newRole: ParticipantRole
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = conversationRef.document(conversationId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: throw Exception("Conversation not found")

                val updatedParticipants = conversation.participants.map { p ->
                    if (p.uid == userId) p.copy(role = newRole.name.lowercase(Locale.getDefault())) else p
                }

                transaction.update(docRef, "participants", updatedParticipants)
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FETCHING
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getConversation(conversationId: String): Result<Conversation> =
        withContext(Dispatchers.IO) {
            try {
                val snapshot = conversationRef.document(conversationId).get().await()
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: return@withContext Result.failure(Exception("Conversation not found"))
                Result.success(conversation)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Finds an existing private (1-on-1) conversation between two users.
     *
     * Fixed: added .limit(50) to cap Firestore reads. Firestore cannot filter
     * by two array-contains fields simultaneously, so we filter participantsIds
     * client-side. The limit keeps this safe for users with large conversation lists.
     */
    suspend fun findExistingPrivateConversation(
        userId: String,
        opponentId: String
    ): Result<Conversation?> = withContext(Dispatchers.IO) {
        try {
            val snapshot = conversationRef
                .whereEqualTo("type", ConversationType.PRIVATE.name.lowercase(Locale.getDefault()))
                .whereArrayContains("participants_ids", userId)
                .limit(50) // Fixed: was unbounded
                .get()
                .await()

            val match = snapshot.documents
                .mapNotNull { it.toObject(Conversation::class.java) }
                .find { it.participantsIds.size == 2 && opponentId in it.participantsIds }

            Result.success(match)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COUNTERS & UNREAD
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun incrementMessageCount(conversationId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                conversationRef.document(conversationId)
                    .update(
                        "message_count", FieldValue.increment(1),
                        "updated_at", Timestamp.now()
                    ).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getUnreadCount(
        conversationId: String,
        userId: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val snapshot = conversationRef.document(conversationId).get().await()
            val conversation = snapshot.toObject(Conversation::class.java)
                ?: return@withContext Result.failure(Exception("Conversation not found"))

            val participant = conversation.participants.find { it.uid == userId }
                ?: return@withContext Result.failure(Exception("User not a participant"))

            Result.success(
                maxOf(0, (conversation.messageCount - participant.lastMessageCount).toInt())
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Atomically syncs participant's read position to the current message count.
     * Returns the number of messages that were unread before syncing.
     */
    suspend fun syncParticipantReadCount(
        conversationId: String,
        userId: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val docRef = conversationRef.document(conversationId)
            var previousUnread = 0

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: throw Exception("Conversation not found")

                val index = conversation.participants.indexOfFirst { it.uid == userId }
                if (index == -1) throw Exception("User not a participant")

                val participant = conversation.participants[index]
                previousUnread = maxOf(
                    0,
                    (conversation.messageCount - participant.lastMessageCount).toInt()
                )

                val updated = conversation.participants.toMutableList()
                updated[index] = participant.copy(lastMessageCount = conversation.messageCount)
                transaction.update(docRef, "participants", updated)
            }.await()

            Result.success(previousUnread)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateLastSeen(
        conversationId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = conversationRef.document(conversationId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: return@runTransaction

                val updated = conversation.participants.map { p ->
                    if (p.uid == userId) p.copy(lastSeen = Timestamp.now()) else p
                }
                transaction.update(docRef, "participants", updated)
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}