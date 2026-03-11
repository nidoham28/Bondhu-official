package org.nidoham.server.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.nidoham.server.domain.model.Conversation
import android.nidoham.server.domain.Participant
import android.nidoham.server.domain.ParticipantRole
import android.nidoham.server.domain.ParticipantType
import android.nidoham.server.repository.ParticipantManager
import java.util.Locale

@Suppress("unused")
class ConversationManager(
    private val firestore: FirebaseFirestore,
    private val participantRepository: ParticipantManager
) {

    private val conversationRef: CollectionReference
        get() = firestore.collection("conversations")

    // ─────────────────────────────────────────────────────────────────────────
    // PAGING SOURCES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads conversations the user belongs to, ordered by joined_at DESC.
     *
     * Without participants_ids on the root document, membership is determined
     * via a two-step approach:
     *   1. Query the `participants` collection group for entries where uid == userId,
     *      using the DocumentSnapshot as a pagination cursor.
     *   2. Extract conversationIds from the document path and fetch the
     *      corresponding Conversation documents via whereIn.
     *
     * Page size must not exceed 30 — the Firestore whereIn limit.
     */
    private class ConversationPagingSource(
        private val firestore: FirebaseFirestore,
        private val userId: String
    ) : PagingSource<DocumentSnapshot, Conversation>() {

        override fun getRefreshKey(
            state: PagingState<DocumentSnapshot, Conversation>
        ): DocumentSnapshot? = null

        override suspend fun load(
            params: LoadParams<DocumentSnapshot>
        ): LoadResult<DocumentSnapshot, Conversation> = try {
            // Step 1: page through participant entries for this user
            var participantQuery = firestore.collectionGroup("participants")
                .whereEqualTo("uid", userId)
                .orderBy("joined_at", Query.Direction.DESCENDING)
                .limit(params.loadSize.toLong())

            params.key?.let { participantQuery = participantQuery.startAfter(it) }

            val participantSnap = participantQuery.get().await()

            // Step 2: resolve conversationIds from the subcollection path
            // Path: participant/{conversationId}/participants/{uid}
            val conversationIds = participantSnap.documents.mapNotNull { doc ->
                doc.reference.parent.parent?.id
            }

            if (conversationIds.isEmpty()) {
                return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
            }

            // Step 3: fetch Conversation documents — whereIn supports up to 30 IDs
            val conversations = firestore.collection("conversations")
                .whereIn(FieldPath.documentId(), conversationIds)
                .get().await()
                .toObjects(Conversation::class.java)
                // Restore the joined_at ordering from step 1
                .sortedBy { conversationIds.indexOf(it.conversationId) }

            LoadResult.Page(
                data    = conversations,
                prevKey = null,
                nextKey = if (participantSnap.documents.size < params.loadSize) null
                else participantSnap.documents.lastOrNull()
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * Search PagingSource — prefix match on the `title` field, scoped to
     * conversations the user has joined.
     *
     * Uses the same two-step approach as [ConversationPagingSource], with an
     * additional client-side title filter applied after fetching conversations.
     * This is a pragmatic trade-off: Firestore does not support native full-text
     * search, and combining a collection-group membership query with a field
     * range filter on a different collection is not possible in a single query.
     * For production-grade search, delegate to Algolia or Typesense.
     *
     * Page size must not exceed 30.
     */
    private class ConversationSearchPagingSource(
        private val firestore: FirebaseFirestore,
        private val userId: String,
        private val searchQuery: String
    ) : PagingSource<DocumentSnapshot, Conversation>() {

        override fun getRefreshKey(
            state: PagingState<DocumentSnapshot, Conversation>
        ): DocumentSnapshot? = null

        override suspend fun load(
            params: LoadParams<DocumentSnapshot>
        ): LoadResult<DocumentSnapshot, Conversation> = try {
            val normalized = searchQuery.lowercase(Locale.getDefault()).trim()
            if (normalized.isEmpty()) {
                return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
            }

            // Step 1: page through participant entries for this user
            var participantQuery = firestore.collectionGroup("participants")
                .whereEqualTo("uid", userId)
                .orderBy("joined_at", Query.Direction.DESCENDING)
                .limit(params.loadSize.toLong())

            params.key?.let { participantQuery = participantQuery.startAfter(it) }

            val participantSnap = participantQuery.get().await()

            val conversationIds = participantSnap.documents.mapNotNull { doc ->
                doc.reference.parent.parent?.id
            }

            if (conversationIds.isEmpty()) {
                return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
            }

            // Step 2: fetch conversations and apply title filter client-side
            val conversations = firestore.collection("conversations")
                .whereIn(FieldPath.documentId(), conversationIds)
                .get().await()
                .toObjects(Conversation::class.java)
                .filter { it.title.lowercase(Locale.getDefault()).startsWith(normalized) }
                .sortedBy { conversationIds.indexOf(it.conversationId) }

            LoadResult.Page(
                data    = conversations,
                prevKey = null,
                nextKey = if (participantSnap.documents.size < params.loadSize) null
                else participantSnap.documents.lastOrNull()
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAGING FLOWS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Page size is capped at 30 to stay within the Firestore whereIn limit
     * used by the two-step paging approach.
     */
    fun getConversationPagingFlow(
        userId: String,
        pageSize: Int = 20
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize.coerceAtMost(30),
            enablePlaceholders = false,
            prefetchDistance   = pageSize / 2
        ),
        pagingSourceFactory = { ConversationPagingSource(firestore, userId) }
    ).flow

    fun searchConversationsPagingFlow(
        userId: String,
        searchQuery: String,
        pageSize: Int = 20
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize.coerceAtMost(30),
            enablePlaceholders = false
        ),
        pagingSourceFactory = { ConversationSearchPagingSource(firestore, userId, searchQuery) }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new conversation document and writes participant subcollection
     * documents via [ParticipantManager].
     *
     * No participants_ids array is written to the root document.
     * Membership is determined exclusively through the participants subcollection.
     */
    suspend fun createConversation(
        creatorId: String,
        title: String,
        type: ParticipantType,
        initialParticipantIds: List<String> = emptyList(),
        photoUrl: String? = null
    ): Result<Conversation> = withContext(Dispatchers.IO) {
        try {
            val docRef = conversationRef.document()
            val now    = Timestamp.now()

            val allParticipantIds = (listOf(creatorId) + initialParticipantIds).distinct()

            val newConversation = Conversation(
                conversationId  = docRef.id,
                creatorId       = creatorId,
                title           = title,
                type            = type.value,
                photoUrl        = photoUrl,
                createdAt       = now,
                updatedAt       = now,
                subscriberCount = allParticipantIds.size.toLong()
            )

            docRef.set(newConversation).await()

            val participants = buildList {
                add(Participant(uid = creatorId, role = ParticipantRole.OWNER.value, type = type.value))
                initialParticipantIds
                    .filter { it != creatorId }
                    .forEach { uid ->
                        add(Participant(uid = uid, role = ParticipantRole.MEMBER.value, type = type.value))
                    }
            }
            participantRepository.addParticipants(docRef.id, participants).getOrThrow()

            Result.success(newConversation)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateConversationDetails(
        conversationId: String,
        title: String?          = null,
        photoUrl: String?       = null,
        adminApproval: Boolean? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updates = mutableMapOf<String, Any>("updated_at" to Timestamp.now())
            title?.let         { updates["title"]          = it }
            photoUrl?.let      { updates["photo_url"]      = it }
            adminApproval?.let { updates["admin_approval"] = it }

            conversationRef.document(conversationId).update(updates).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes all participant subcollection documents and then the conversation
     * root document. For very large groups, consider triggering subcollection
     * deletion via a Cloud Function instead.
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                participantRepository.deleteAllParticipants(conversationId).getOrThrow()
                conversationRef.document(conversationId).delete().await()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // PARTICIPANT MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a participant to the conversation.
     * Updates only subscriber_count and updated_at on the root document.
     */
    suspend fun addParticipant(
        conversationId: String,
        newUserId: String,
        type: ParticipantType,
        role: ParticipantRole = ParticipantRole.MEMBER
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val alreadyPresent = participantRepository
                .isParticipant(conversationId, newUserId)
                .getOrThrow()
            if (alreadyPresent) return@withContext Result.failure(
                IllegalStateException("User $newUserId is already a participant")
            )

            participantRepository.addParticipant(
                conversationId,
                Participant(uid = newUserId, role = role.value, type = type.value)
            ).getOrThrow()

            conversationRef.document(conversationId).update(
                "subscriber_count", FieldValue.increment(1),
                "updated_at",       Timestamp.now()
            ).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes a participant from the conversation.
     * Updates only subscriber_count and updated_at on the root document.
     */
    suspend fun removeParticipant(
        conversationId: String,
        userIdToRemove: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            participantRepository.removeParticipant(conversationId, userIdToRemove).getOrThrow()

            conversationRef.document(conversationId).update(
                "subscriber_count", FieldValue.increment(-1),
                "updated_at",       Timestamp.now()
            ).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Allows a participant to leave voluntarily.
     * The OWNER must transfer ownership before leaving.
     */
    suspend fun leaveConversation(
        conversationId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val participant = participantRepository
                .getParticipant(conversationId, userId)
                .getOrThrow()
                ?: return@withContext Result.failure(
                    IllegalStateException("User is not a participant")
                )

            if (participant.participantRole == ParticipantRole.OWNER) {
                return@withContext Result.failure(
                    IllegalStateException("Owner cannot leave. Transfer ownership or delete the group.")
                )
            }

            participantRepository.removeParticipant(conversationId, userId).getOrThrow()

            conversationRef.document(conversationId).update(
                "subscriber_count", FieldValue.increment(-1),
                "updated_at",       Timestamp.now()
            ).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Delegates entirely to [ParticipantManager.updateRole]. */
    suspend fun updateParticipantRole(
        conversationId: String,
        userId: String,
        newRole: ParticipantRole
    ): Result<Unit> = withContext(Dispatchers.IO) {
        participantRepository.updateRole(conversationId, userId, newRole)
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
     * Finds an existing 1-on-1 conversation between two users.
     *
     * Without participants_ids, membership is resolved via the participant
     * collection group. The approach is:
     *   1. Find all PERSONAL conversations that userId has joined.
     *   2. For each candidate conversationId, confirm opponentId is also
     *      a participant via [ParticipantManager.isParticipant].
     *   3. Return the first confirmed match.
     *
     * Personal conversations between two users are rare in number, so the
     * bounded candidate set (limit 20) keeps this efficient in practice.
     */
    suspend fun findExistingPrivateConversation(
        userId: String,
        opponentId: String
    ): Result<Conversation?> = withContext(Dispatchers.IO) {
        try {
            // Step 1: find PERSONAL participant entries for userId
            val participantSnap = firestore.collectionGroup("participants")
                .whereEqualTo("uid", userId)
                .whereEqualTo("type", ParticipantType.PERSONAL.value)
                .limit(20)
                .get().await()

            val candidateIds = participantSnap.documents.mapNotNull { doc ->
                doc.reference.parent.parent?.id
            }

            // Step 2: confirm opponentId membership and return the first match
            val matchingId = candidateIds.firstOrNull { conversationId ->
                participantRepository
                    .isParticipant(conversationId, opponentId)
                    .getOrElse { false }
            }

            val conversation = matchingId?.let { id ->
                conversationRef.document(id).get().await()
                    .toObject(Conversation::class.java)
            }

            Result.success(conversation)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COUNTERS
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun incrementMessageCount(conversationId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                conversationRef.document(conversationId)
                    .update(
                        "message_count", FieldValue.increment(1),
                        "updated_at",    Timestamp.now()
                    ).await()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}