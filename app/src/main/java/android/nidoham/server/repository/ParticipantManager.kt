package android.nidoham.server.repository

import android.nidoham.server.domain.Participant
import android.nidoham.server.domain.ParticipantRole
import android.nidoham.server.domain.ParticipantType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("unused")
@Singleton
class ParticipantManager @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    // ─────────────────────────────────────────────────────────────
    //  Paths
    //  participant/{conversationId}
    //  participant/{conversationId}/participants/{uid}
    //  [collection group] participants where uid == currentUser
    // ─────────────────────────────────────────────────────────────

    private fun conversationDoc(conversationId: String) =
        firestore.collection(COL_PARTICIPANT).document(conversationId)

    private fun participantsCol(conversationId: String) =
        conversationDoc(conversationId).collection(COL_PARTICIPANTS)

    private fun participantDoc(conversationId: String, uid: String) =
        participantsCol(conversationId).document(uid)

    private fun participantsGroup() =
        firestore.collectionGroup(COL_PARTICIPANTS)

    // ─────────────────────────────────────────────────────────────
    //  Conversation ID helpers
    // ─────────────────────────────────────────────────────────────

    fun buildPersonalConversationId(userId: String, targetId: String): String =
        listOf(userId, targetId).sorted().joinToString(separator = "_")

    fun getPersonalConversationId(userId: String, targetId: String): String =
        buildPersonalConversationId(userId, targetId)

    /**
     * Ensures a personal conversation document and both participant
     * sub-documents exist, creating them atomically if they do not.
     * Safe to call multiple times. Returns the conversationId on success.
     */
    suspend fun getOrCreatePersonalConversation(
        userId: String,
        targetId: String
    ): Result<String> = runCatching {
        val conversationId = buildPersonalConversationId(userId, targetId)
        val convRef        = conversationDoc(conversationId)

        firestore.runTransaction { tx ->
            val snapshot = tx.get(convRef)
            if (!snapshot.exists()) {
                // FIX: ConversationType removed — use ParticipantType.PERSONAL.value
                //      for the conversation root's type field as well.
                tx.set(convRef, mapOf(
                    FIELD_TYPE       to ParticipantType.PERSONAL.value,
                    FIELD_CREATED_BY to userId
                ))
                tx.set(
                    participantDoc(conversationId, userId),
                    Participant(
                        uid  = userId,
                        role = ParticipantRole.OWNER.value,
                        type = ParticipantType.PERSONAL.value
                    )
                )
                tx.set(
                    participantDoc(conversationId, targetId),
                    Participant(
                        uid  = targetId,
                        role = ParticipantRole.MEMBER.value,
                        type = ParticipantType.PERSONAL.value
                    )
                )
            }
        }.await()

        conversationId
    }

    // ─────────────────────────────────────────────────────────────
    //  Write
    // ─────────────────────────────────────────────────────────────

    suspend fun addParticipant(
        conversationId: String,
        participant: Participant
    ): Result<Unit> = runCatching {
        require(participant.uid.isNotBlank()) { "uid must not be blank" }
        participantDoc(conversationId, participant.uid)
            .set(participant)
            .await()
    }

    /** Atomically adds multiple participants in one batch write. */
    suspend fun addParticipants(
        conversationId: String,
        participants: List<Participant>
    ): Result<Unit> = runCatching {
        require(participants.isNotEmpty()) { "participants list must not be empty" }
        val batch = firestore.batch()
        participants.forEach { p ->
            require(p.uid.isNotBlank()) { "uid must not be blank" }
            batch.set(participantDoc(conversationId, p.uid), p)
        }
        batch.commit().await()
    }

    suspend fun updateRole(
        conversationId: String,
        uid: String,
        newRole: ParticipantRole
    ): Result<Unit> = runCatching {
        participantDoc(conversationId, uid)
            .update(FIELD_ROLE, newRole.value)
            .await()
    }

    suspend fun removeParticipant(
        conversationId: String,
        uid: String
    ): Result<Unit> = runCatching {
        participantDoc(conversationId, uid)
            .delete()
            .await()
    }

    /** Atomically removes multiple participants in one batch write. */
    suspend fun removeParticipants(
        conversationId: String,
        uids: List<String>
    ): Result<Unit> = runCatching {
        require(uids.isNotEmpty()) { "uids list must not be empty" }
        val batch = firestore.batch()
        uids.forEach { uid -> batch.delete(participantDoc(conversationId, uid)) }
        batch.commit().await()
    }

    // ─────────────────────────────────────────────────────────────
    //  Read — one-shot
    // ─────────────────────────────────────────────────────────────

    suspend fun getParticipant(
        conversationId: String,
        uid: String
    ): Result<Participant?> = runCatching {
        participantDoc(conversationId, uid)
            .get().await()
            .toObject(Participant::class.java)
    }

    suspend fun getParticipants(
        conversationId: String
    ): Result<List<Participant>> = runCatching {
        participantsCol(conversationId)
            .orderBy(FIELD_JOINED_AT)
            .get().await()
            .toObjects(Participant::class.java)
    }

    suspend fun isParticipant(
        conversationId: String,
        uid: String
    ): Result<Boolean> = runCatching {
        participantDoc(conversationId, uid)
            .get().await()
            .exists()
    }

    suspend fun getAdmins(
        conversationId: String
    ): Result<List<Participant>> = runCatching {
        participantsCol(conversationId)
            .whereIn(FIELD_ROLE, listOf(ParticipantRole.OWNER.value, ParticipantRole.ADMIN.value))
            .get().await()
            .toObjects(Participant::class.java)
    }

    suspend fun getParticipantCount(conversationId: String): Result<Int> = runCatching {
        participantsCol(conversationId)
            .count()
            .get(com.google.firebase.firestore.AggregateSource.SERVER)
            .await()
            .count.toInt()
    }

    // ─────────────────────────────────────────────────────────────
    //  Read — Paging3 (participants inside one conversation)
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns a [Flow]<[PagingData]<[Participant]>> for all participants
     * in [conversationId], ordered by [FIELD_JOINED_AT] ascending.
     *
     * The factory query must not carry a `.limit()` call.
     * [ParticipantPagingSource.load] applies the correct limit per page,
     * including Paging3's enlarged initial load (typically pageSize × 3).
     */
    fun getParticipantsPaged(
        conversationId: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(
            pageSize         = pageSize,
            prefetchDistance = pageSize / 2,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = participantsCol(conversationId)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.ASCENDING)
            )
        }
    ).flow

    fun getParticipantsByRolePaged(
        conversationId: String,
        role: ParticipantRole,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = participantsCol(conversationId)
                    .whereEqualTo(FIELD_ROLE, role.value)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.ASCENDING)
            )
        }
    ).flow

    // ─────────────────────────────────────────────────────────────
    //  Read — Paging3 (current user's joined conversations)
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns a paged list of [Participant] entries where uid == [currentUserId],
     * using a Firestore collection-group query.
     *
     * ⚠ [Participant.id] is populated by `@DocumentId` and holds the leaf
     * document ID, which equals the participant's **uid** — NOT the conversationId.
     * To recover the conversationId from a [DocumentSnapshot], use:
     *
     *   documentSnapshot.reference.parent.parent?.id
     *
     * If your UI requires the conversationId, store it as an explicit field
     * on the Participant document and map it with `@PropertyName`.
     */
    fun getJoinedConversationsPaged(
        currentUserId: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = participantsGroup()
                    .whereEqualTo(FIELD_UID, currentUserId)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.DESCENDING)
            )
        }
    ).flow

    /**
     * FIX: parameter type changed from ConversationType (non-existent)
     *      to ParticipantType.
     */
    fun getJoinedConversationsByTypePaged(
        currentUserId: String,
        type: ParticipantType,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = participantsGroup()
                    .whereEqualTo(FIELD_UID, currentUserId)
                    .whereEqualTo(FIELD_TYPE, type.value)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.DESCENDING)
            )
        }
    ).flow

    // ─────────────────────────────────────────────────────────────
    //  Read — real-time Flow
    // ─────────────────────────────────────────────────────────────

    fun observeParticipant(
        conversationId: String,
        uid: String
    ): Flow<Participant?> = callbackFlow {
        val reg: ListenerRegistration =
            participantDoc(conversationId, uid)
                // FIX: explicit lambda parameter types to satisfy type inference
                .addSnapshotListener { snap: DocumentSnapshot?, err: FirebaseFirestoreException? ->
                    if (err != null) { close(err); return@addSnapshotListener }
                    trySend(snap?.toObject(Participant::class.java))
                }
        awaitClose { reg.remove() }
    }

    fun observeParticipants(
        conversationId: String
    ): Flow<List<Participant>> = callbackFlow {
        val reg: ListenerRegistration =
            participantsCol(conversationId)
                .orderBy(FIELD_JOINED_AT)
                // FIX: explicit lambda parameter types to satisfy type inference
                .addSnapshotListener { snap: QuerySnapshot?, err: FirebaseFirestoreException? ->
                    if (err != null) { close(err); return@addSnapshotListener }
                    trySend(snap?.toObjects(Participant::class.java) ?: emptyList())
                }
        awaitClose { reg.remove() }
    }

    /**
     * Real-time participant count.
     *
     * Note: The Android Firestore SDK does not expose a field-projection API
     * on snapshot listeners (.select() is web-SDK only), so the full documents
     * are fetched. For very large groups, prefer the one-shot [getParticipantCount]
     * which uses the server-side Aggregate COUNT and transfers no document payloads.
     */
    fun observeParticipantCount(conversationId: String): Flow<Int> = callbackFlow {
        val reg: ListenerRegistration =
        // FIX: .select() removed — not available in the Android SDK.
            // FIX: explicit lambda parameter types to satisfy type inference.
            participantsCol(conversationId)
                .addSnapshotListener { snap: QuerySnapshot?, err: FirebaseFirestoreException? ->
                    if (err != null) { close(err); return@addSnapshotListener }
                    trySend(snap?.size() ?: 0)
                }
        awaitClose { reg.remove() }
    }

    // ─────────────────────────────────────────────────────────────
    //  Firestore management utilities
    // ─────────────────────────────────────────────────────────────

    /**
     * Hard-deletes every participant document then the conversation root document.
     * Delegates participant deletion to [deleteAllParticipants], which chunks
     * writes at 450 ops to remain safely under Firestore's 500-operation limit.
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        deleteAllParticipants(conversationId).getOrThrow()
        conversationDoc(conversationId).delete().await()
    }

    /**
     * Deletes all participant documents in chunks of 450 to stay under
     * the 500-op batch limit.
     */
    suspend fun deleteAllParticipants(conversationId: String): Result<Unit> = runCatching {
        var query = participantsCol(conversationId).limit(450)
        while (true) {
            val docs = query.get().await().documents
            if (docs.isEmpty()) break
            val batch = firestore.batch()
            docs.forEach { batch.delete(it.reference) }
            batch.commit().await()
            val last: DocumentSnapshot = docs.last()
            query = participantsCol(conversationId).startAfter(last).limit(450)
        }
    }

    /**
     * Promotes [newOwnerId] to OWNER while demoting [currentOwnerId] to ADMIN.
     * Both writes are executed as a single atomic Firestore transaction.
     */
    suspend fun transferOwnership(
        conversationId: String,
        currentOwnerId: String,
        newOwnerId: String
    ): Result<Unit> = runCatching {
        val ownerRef  = participantDoc(conversationId, currentOwnerId)
        val targetRef = participantDoc(conversationId, newOwnerId)
        firestore.runTransaction { tx ->
            tx.update(ownerRef,  FIELD_ROLE, ParticipantRole.ADMIN.value)
            tx.update(targetRef, FIELD_ROLE, ParticipantRole.OWNER.value)
        }.await()
    }

    // ─────────────────────────────────────────────────────────────
    //  PagingSource
    // ─────────────────────────────────────────────────────────────

    /**
     * Cursor-based [PagingSource] for any Firestore [Query] that returns
     * [Participant] documents.
     *
     * The supplied [query] must NOT include a `.limit()` call — this class
     * applies [LoadParams.loadSize] as the limit per page so that Paging3's
     * enlarged initial load (pageSize × 3) is honoured correctly.
     *
     * FIX: `inner` modifier removed — the class does not reference any
     * instance state of [ParticipantManager].
     */
    class ParticipantPagingSource(
        private val query: Query
    ) : PagingSource<DocumentSnapshot, Participant>() {

        override fun getRefreshKey(
            state: PagingState<DocumentSnapshot, Participant>
        ): DocumentSnapshot? = null

        override suspend fun load(
            params: LoadParams<DocumentSnapshot>
        ): LoadResult<DocumentSnapshot, Participant> = try {
            val pageQuery = params.key
                ?.let { cursor -> query.startAfter(cursor) }
                ?: query

            val snapshot = pageQuery
                .limit(params.loadSize.toLong())
                .get()
                .await()

            val participants = snapshot.toObjects(Participant::class.java)
            val nextKey      = snapshot.documents.lastOrNull()
                ?.takeIf { participants.size >= params.loadSize }

            LoadResult.Page(
                data    = participants,
                prevKey = null,
                nextKey = nextKey
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────

    companion object {
        const val COL_PARTICIPANT  = "participant"
        const val COL_PARTICIPANTS = "participants"
        const val FIELD_ROLE       = "role"
        const val FIELD_UID        = "uid"
        const val FIELD_TYPE       = "type"
        const val FIELD_JOINED_AT  = "joined_at"
        const val FIELD_CREATED_BY = "created_by"
        const val PAGE_SIZE        = 20
    }
}