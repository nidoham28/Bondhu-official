package com.nidoham.server.manager

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.message.MessagePreview
import com.nidoham.server.domain.participant.Participant
import com.nidoham.server.util.ParticipantRole
import com.nidoham.server.util.ParticipantType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Manages all Firestore operations for the conversations collection.
 *
 * Schema:
 *   conversations/{conversationId}                — [Conversation] document
 *   participant/{conversationId}/members/{userId}  — Participant sub-collection (owned by [ParticipantManager])
 *
 * Participant and message operations are fully delegated to [ParticipantManager]
 * and [MessageManager] respectively. The only exception is [createConversation],
 * which uses a direct Firestore batch to guarantee that the conversation document
 * and its first participant record are written atomically.
 *
 * @param firestore          Injectable [FirebaseFirestore] instance.
 * @param auth               Injectable [FirebaseAuth] instance.
 * @param participantManager Injectable [ParticipantManager] for all participant operations.
 * @param messageManager     Injectable [MessageManager] for all message operations.
 */
class ConversationManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val participantManager: ParticipantManager = ParticipantManager(),
    private val messageManager: MessageManager = MessageManager()
) {

    companion object {
        private const val CONVERSATIONS  = "conversations"
        private const val PARTICIPANT_ROOT = "participant"
        private const val MEMBERS        = "members"   // must match ParticipantManager.MEMBERS
        private const val PAGE_SIZE      = 20

        private const val FIELD_PARENT_ID        = "parentId"
        private const val FIELD_CREATOR_ID       = "creatorId"
        private const val FIELD_TITLE            = "title"
        private const val FIELD_SUBTITLE         = "subtitle"
        private const val FIELD_TYPE             = "type"
        private const val FIELD_LAST_MESSAGE     = "lastMessage"
        private const val FIELD_CREATED_AT       = "createdAt"
        private const val FIELD_UPDATED_AT       = "updatedAt"
        private const val FIELD_SUBSCRIBER_COUNT = "subscriberCount"
        private const val FIELD_MESSAGE_COUNT    = "messageCount"
        private const val FIELD_TRANSLATED       = "translated"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun conversationsCollection() =
        firestore.collection(CONVERSATIONS)

    private fun conversationDocument(conversationId: String) =
        conversationsCollection().document(conversationId)

    // The participant document reference is constructed locally only for the
    // atomic batch in createConversation. All other participant access goes
    // through ParticipantManager.
    private fun participantDocument(conversationId: String, userId: String) =
        firestore.collection(PARTICIPANT_ROOT)
            .document(conversationId)
            .collection(MEMBERS)
            .document(userId)

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new [Conversation] document and atomically adds the currently
     * authenticated user as the initial [ParticipantRole.ADMIN] in a single
     * Firestore batch write. Atomicity here is non-negotiable — a conversation
     * document without a corresponding participant record is an invalid state.
     *
     * If [Conversation.parentId] is blank, Firestore auto-generates the document ID.
     *
     * @param conversation The [Conversation] to persist.
     * @return The conversation document ID on success.
     */
    suspend fun createConversation(conversation: Conversation): Result<String> = runCatching {
        val currentUser = auth.currentUser
            ?: error("No authenticated user is signed in.")

        val docRef = if (conversation.parentId.isBlank()) conversationsCollection().document()
        else conversationDocument(conversation.parentId)
        val conversationId = docRef.id

        val populated = conversation.copy(
            parentId  = conversationId,
            creatorId = currentUser.uid
        )
        val participant = Participant(
            uid      = currentUser.uid,
            parentId = conversationId,
            role     = ParticipantRole.ADMIN.value,
            type     = conversation.type
        )

        // Direct batch used intentionally — participantManager.addParticipant()
        // issues its own independent write and cannot participate in this batch.
        firestore.batch().apply {
            set(docRef, populated)
            set(participantDocument(conversationId, currentUser.uid), participant)
        }.commit().await()

        conversationId
    }

    /**
     * Updates specific fields on an existing [Conversation] document.
     * Automatically stamps [FIELD_UPDATED_AT] with the current server timestamp
     * unless it is already present in [fields].
     *
     * @param conversationId The conversation document ID.
     * @param fields         Map of Firestore field names to their new values.
     */
    suspend fun updateConversation(
        conversationId: String,
        fields: Map<String, Any?>
    ): Result<Unit> = runCatching {
        require(fields.isNotEmpty()) { "Update fields must not be empty." }
        val stamped = if (FIELD_UPDATED_AT !in fields) {
            fields + (FIELD_UPDATED_AT to FieldValue.serverTimestamp())
        } else fields
        conversationDocument(conversationId).update(stamped).await()
    }

    /**
     * Replaces the [lastMessage] preview on a conversation document. Call this
     * after a message is successfully written via [MessageManager].
     *
     * @param conversationId The conversation document ID.
     * @param preview        The [MessagePreview] to store.
     */
    suspend fun updateLastMessage(
        conversationId: String,
        preview: MessagePreview
    ): Result<Unit> = updateConversation(
        conversationId,
        mapOf(FIELD_LAST_MESSAGE to preview)
    )

    /** Atomically increments [FIELD_MESSAGE_COUNT] by 1. */
    suspend fun incrementMessageCount(conversationId: String): Result<Unit> =
        updateConversation(conversationId, mapOf(FIELD_MESSAGE_COUNT to FieldValue.increment(1)))

    /** Atomically decrements [FIELD_MESSAGE_COUNT] by 1. */
    suspend fun decrementMessageCount(conversationId: String): Result<Unit> =
        updateConversation(conversationId, mapOf(FIELD_MESSAGE_COUNT to FieldValue.increment(-1)))

    /** Atomically increments [FIELD_SUBSCRIBER_COUNT] by 1. */
    suspend fun incrementSubscriberCount(conversationId: String): Result<Unit> =
        updateConversation(conversationId, mapOf(FIELD_SUBSCRIBER_COUNT to FieldValue.increment(1)))

    /** Atomically decrements [FIELD_SUBSCRIBER_COUNT] by 1. */
    suspend fun decrementSubscriberCount(conversationId: String): Result<Unit> =
        updateConversation(conversationId, mapOf(FIELD_SUBSCRIBER_COUNT to FieldValue.increment(-1)))

    /**
     * Permanently deletes a [Conversation] document. Does not cascade-delete its
     * messages sub-collection; call [MessageManager.deleteAllMessages] first if a
     * full wipe is required.
     *
     * @param conversationId The conversation document ID to delete.
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        conversationDocument(conversationId).delete().await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single [Conversation] by its ID, or null if it does not exist.
     *
     * @param conversationId The conversation document ID.
     */
    suspend fun fetchConversation(conversationId: String): Result<Conversation?> = runCatching {
        conversationDocument(conversationId).get().await().toObject(Conversation::class.java)
    }

    /**
     * Fetches all conversations in which [userId] is a participant. Resolves
     * the conversation IDs via [ParticipantManager.fetchJoinedIds], then
     * fetches the corresponding conversation documents in parallel.
     *
     * @param userId The Firebase UID of the target user.
     * @param type   Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    suspend fun fetchConversationsForUser(
        userId: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Conversation>> = runCatching {
        val ids = participantManager.fetchJoinedIds(userId, type).getOrThrow()
        coroutineScope {
            ids.map { id -> async { conversationDocument(id).get().await() } }
                .awaitAll()
                .mapNotNull { it.toObject(Conversation::class.java) }
        }
    }

    /**
     * Fetches all conversations the currently authenticated user has joined.
     * Delegates ID resolution to [ParticipantManager].
     *
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    suspend fun fetchCurrentUserConversations(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Conversation>> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("No authenticated user is signed in."))
        return fetchConversationsForUser(uid, type)
    }

    /**
     * Applies a [ConversationFilter] to the conversations collection and returns
     * all matching records as a plain list.
     *
     * @param filter The [ConversationFilter] describing the desired constraints.
     */
    suspend fun fetchConversationsFiltered(
        filter: ConversationFilter
    ): Result<List<Conversation>> = runCatching {
        filter.applyTo(conversationsCollection()).get().await().toObjects(Conversation::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches conversations whose [title] starts with [prefix] using a
     * lexicographic range query. Case-sensitive; normalise to lowercase at write
     * time and pass a lowercase prefix for case-insensitive behaviour.
     *
     * Requires an ascending index on `title`.
     *
     * @param prefix The title prefix to match; must not be blank.
     * @param filter Optional [ConversationFilter] for additional constraints.
     * @param limit  Maximum results to return.
     */
    suspend fun searchConversationsByTitle(
        prefix: String,
        filter: ConversationFilter? = null,
        limit: Long = 20
    ): Result<List<Conversation>> = runCatching {
        require(prefix.isNotBlank()) { "Search prefix must not be blank." }
        val end = prefix.trimEnd() + "\uf8ff"
        var query: Query = conversationsCollection()
            .whereGreaterThanOrEqualTo(FIELD_TITLE, prefix)
            .whereLessThanOrEqualTo(FIELD_TITLE, end)
            .orderBy(FIELD_TITLE, Query.Direction.ASCENDING)
            .limit(limit)
        filter?.type?.let       { query = query.whereEqualTo(FIELD_TYPE, it.value) }
        filter?.creatorId?.let  { query = query.whereEqualTo(FIELD_CREATOR_ID, it) }
        filter?.translated?.let { query = query.whereEqualTo(FIELD_TRANSLATED, it) }
        query.get().await().toObjects(Conversation::class.java)
    }

    /**
     * Searches conversations by [subtitle] prefix using the same lexicographic
     * range strategy as [searchConversationsByTitle].
     *
     * Requires an ascending index on `subtitle`.
     *
     * @param prefix The subtitle prefix to match; must not be blank.
     * @param limit  Maximum results to return.
     */
    suspend fun searchConversationsBySubtitle(
        prefix: String,
        limit: Long = 20
    ): Result<List<Conversation>> = runCatching {
        require(prefix.isNotBlank()) { "Search prefix must not be blank." }
        val end = prefix.trimEnd() + "\uf8ff"
        conversationsCollection()
            .whereGreaterThanOrEqualTo(FIELD_SUBTITLE, prefix)
            .whereLessThanOrEqualTo(FIELD_SUBTITLE, end)
            .orderBy(FIELD_SUBTITLE, Query.Direction.ASCENDING)
            .limit(limit)
            .get()
            .await()
            .toObjects(Conversation::class.java)
    }

    /**
     * Fetches all conversations created by [creatorId], ordered by creation date
     * descending. Optionally filtered by [ParticipantType].
     *
     * @param creatorId The Firebase UID of the creator.
     * @param type      Optional participant type filter.
     * @param limit     Maximum results to return.
     */
    suspend fun searchConversationsByCreator(
        creatorId: String,
        type: ParticipantType? = null,
        limit: Long = 20
    ): Result<List<Conversation>> = runCatching {
        require(creatorId.isNotBlank()) { "creatorId must not be blank." }
        var query: Query = conversationsCollection()
            .whereEqualTo(FIELD_CREATOR_ID, creatorId)
            .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
            .limit(limit)
        type?.let { query = query.whereEqualTo(FIELD_TYPE, it.value) }
        query.get().await().toObjects(Conversation::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Real-Time Listeners
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits an updated [Conversation] whenever the given document changes, or
     * null if it no longer exists.
     *
     * @param conversationId The conversation document ID to observe.
     */
    fun observeConversation(conversationId: String): Flow<Conversation?> = callbackFlow {
        val reg: ListenerRegistration =
            conversationDocument(conversationId).addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                trySend(snapshot?.toObject(Conversation::class.java))
            }
        awaitClose { reg.remove() }
    }

    /**
     * Emits an updated list of [Conversation] objects whenever the collection
     * changes. An optional [ConversationFilter] narrows the observed query.
     *
     * @param filter Optional [ConversationFilter] to restrict the observed query.
     */
    fun observeConversations(
        filter: ConversationFilter? = null
    ): Flow<List<Conversation>> = callbackFlow {
        val base  = conversationsCollection()
        val query = filter?.applyTo(base) ?: base

        val reg: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObjects(Conversation::class.java) ?: emptyList())
        }
        awaitClose { reg.remove() }
    }

    /**
     * Searches conversations by [title] prefix in real time, emitting a fresh
     * result list on every Firestore change. Suitable for a live search bar.
     *
     * @param prefix The title prefix to observe.
     * @param filter Optional [ConversationFilter] for additional constraints.
     * @param limit  Maximum results to stream.
     */
    fun observeSearchByTitle(
        prefix: String,
        filter: ConversationFilter? = null,
        limit: Long = 20
    ): Flow<List<Conversation>> = callbackFlow {
        if (prefix.isBlank()) { trySend(emptyList()); awaitClose(); return@callbackFlow }

        val end = prefix.trimEnd() + "\uf8ff"
        var query: Query = conversationsCollection()
            .whereGreaterThanOrEqualTo(FIELD_TITLE, prefix)
            .whereLessThanOrEqualTo(FIELD_TITLE, end)
            .orderBy(FIELD_TITLE, Query.Direction.ASCENDING)
            .limit(limit)
        filter?.type?.let       { query = query.whereEqualTo(FIELD_TYPE, it.value) }
        filter?.creatorId?.let  { query = query.whereEqualTo(FIELD_CREATOR_ID, it) }
        filter?.translated?.let { query = query.whereEqualTo(FIELD_TRANSLATED, it) }

        val reg: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObjects(Conversation::class.java) ?: emptyList())
        }
        awaitClose { reg.remove() }
    }

    /**
     * Emits the conversation IDs of all conversations the currently authenticated
     * user has joined, updating in real time as membership changes. Delegates
     * entirely to [ParticipantManager.observeJoinedIds].
     *
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    fun observeCurrentUserConversationIds(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Flow<List<String>> {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("No authenticated user is signed in.")
        return participantManager.observeJoinedIds(uid, type)
    }

    /**
     * Emits a fully hydrated list of [Conversation] objects for all conversations
     * the authenticated user has joined, updating in real time as either membership
     * or any joined conversation document changes.
     *
     * Membership changes are observed via [ParticipantManager.observeJoinedIds].
     * For each resolved conversation ID, an individual Firestore document listener
     * is maintained. Inner listeners are replaced atomically on membership changes
     * and fully removed when the [Flow] is cancelled.
     *
     * For users with a very large number of conversations, prefer combining
     * [observeCurrentUserConversationIds] with [fetchConversationsPaged] to avoid
     * attaching an unbounded number of document listeners simultaneously.
     *
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    fun observeCurrentUserConversations(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Flow<List<Conversation>> = callbackFlow {
        val uid = auth.currentUser?.uid
            ?: run { close(IllegalStateException("No authenticated user is signed in.")); return@callbackFlow }

        val snapshots = mutableMapOf<String, Conversation?>()
        val innerRegs = mutableMapOf<String, ListenerRegistration>()

        fun emitMerged() { trySend(snapshots.values.filterNotNull()) }

        // Delegate membership observation to ParticipantManager.
        val job = launch {
            participantManager.observeJoinedIds(uid, type).collectLatest { currentIds ->
                val currentSet = currentIds.toSet()

                (innerRegs.keys - currentSet).forEach { id ->
                    innerRegs.remove(id)?.remove()
                    snapshots.remove(id)
                }

                (currentSet - innerRegs.keys).forEach { conversationId ->
                    innerRegs[conversationId] = conversationDocument(conversationId)
                        .addSnapshotListener { doc, error ->
                            if (error != null) { close(error); return@addSnapshotListener }
                            snapshots[conversationId] = doc?.toObject(Conversation::class.java)
                            emitMerged()
                        }
                }

                if ((currentSet - innerRegs.keys).isEmpty()) emitMerged()
            }
        }

        awaitClose {
            job.cancel()
            innerRegs.values.forEach { it.remove() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Paging3
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated [Flow] over the conversations collection, ordered
     * by [FIELD_UPDATED_AT] descending so the most recently active conversations
     * appear first.
     *
     * @param pageSize Documents per page.
     */
    fun fetchConversationsPaged(
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false, prefetchDistance = pageSize / 2),
        pagingSourceFactory = {
            ConversationPagingSource(
                conversationsCollection()
                    .orderBy(FIELD_UPDATED_AT, Query.Direction.DESCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    /**
     * Returns a cursor-paginated [Flow] with a [ConversationFilter] applied.
     *
     * @param filter   [ConversationFilter] to apply before paginating.
     * @param pageSize Documents per page.
     */
    fun fetchConversationsFilteredPaged(
        filter: ConversationFilter,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false, prefetchDistance = pageSize / 2),
        pagingSourceFactory = {
            ConversationPagingSource(
                filter.applyTo(conversationsCollection()).limit(pageSize.toLong())
            )
        }
    ).flow
}

// ─────────────────────────────────────────────────────────────────────────────
// ConversationFilter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable, composable filter descriptor for conversation queries.
 *
 * Only non-null fields are applied to the Firestore query. Combining multiple
 * inequality or ordering constraints may require a composite Firestore index.
 *
 * @param type          Filters by [ParticipantType].
 * @param creatorId     Filters by the creator's Firebase UID.
 * @param translated    Filters by the translated flag.
 * @param createdAfter  Includes only conversations created after this [Timestamp].
 * @param createdBefore Includes only conversations created before this [Timestamp].
 * @param updatedAfter  Includes only conversations updated after this [Timestamp].
 * @param sortBy        Firestore field name to sort by; defaults to `updatedAt`.
 * @param sortOrder     Sort direction; defaults to [Query.Direction.DESCENDING].
 * @param limit         Caps the result set to this many documents.
 */
data class ConversationFilter(
    val type: ParticipantType? = null,
    val creatorId: String? = null,
    val translated: Boolean? = null,
    val createdAfter: Timestamp? = null,
    val createdBefore: Timestamp? = null,
    val updatedAfter: Timestamp? = null,
    val sortBy: String = "updatedAt",
    val sortOrder: Query.Direction = Query.Direction.DESCENDING,
    val limit: Long? = null
) {
    companion object {
        private const val FIELD_TYPE       = "type"
        private const val FIELD_CREATOR_ID = "creatorId"
        private const val FIELD_TRANSLATED = "translated"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_UPDATED_AT = "updatedAt"

        val PERSONAL = ConversationFilter(type = ParticipantType.PERSONAL)
        val GROUP    = ConversationFilter(type = ParticipantType.GROUP)
    }

    /** Applies all non-null constraints to [query] and returns the result. */
    fun applyTo(query: Query): Query {
        var q = query
        type?.let          { q = q.whereEqualTo(FIELD_TYPE, it.value) }
        creatorId?.let     { q = q.whereEqualTo(FIELD_CREATOR_ID, it) }
        translated?.let    { q = q.whereEqualTo(FIELD_TRANSLATED, it) }
        createdAfter?.let  { q = q.whereGreaterThan(FIELD_CREATED_AT, it) }
        createdBefore?.let { q = q.whereLessThan(FIELD_CREATED_AT, it) }
        updatedAfter?.let  { q = q.whereGreaterThan(FIELD_UPDATED_AT, it) }
        q = q.orderBy(sortBy, sortOrder)
        limit?.let { q = q.limit(it) }
        return q
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ConversationPagingSource
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Firestore-backed [PagingSource] for [Conversation] documents.
 *
 * Uses cursor-based pagination via [DocumentSnapshot.startAfter] to avoid
 * re-reading previously loaded pages. Only forward pagination is supported;
 * a refresh always restarts from the first page.
 *
 * @param query Base Firestore [Query]; must include a [Query.limit] clause.
 */
class ConversationPagingSource(
    private val query: Query
) : PagingSource<DocumentSnapshot, Conversation>() {

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, Conversation> = try {
        val pageQuery     = params.key?.let { query.startAfter(it) } ?: query
        val snapshot      = pageQuery.get().await()
        val conversations = snapshot.toObjects(Conversation::class.java)
        val nextKey       = snapshot.documents.lastOrNull().takeUnless { snapshot.isEmpty }

        LoadResult.Page(data = conversations, prevKey = null, nextKey = nextKey)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(
        state: PagingState<DocumentSnapshot, Conversation>
    ): DocumentSnapshot? = null
}