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
import com.nidoham.server.domain.message.Message
import com.nidoham.server.util.MessageStatus
import com.nidoham.server.util.MessageType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Manages all Firestore operations for the messages sub-collection.
 *
 * Schema: message/{conversationId}/messages/{messageId}
 *
 * All operations are keyed by [conversationId] and, where applicable,
 * [messageId]. [sendMessage] auto-generates [messageId] when [Message.messageId]
 * is blank.
 *
 * @param firestore Injectable [FirebaseFirestore] instance.
 * @param auth      Injectable [FirebaseAuth] instance.
 */
class MessageManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val ROOT         = "message"
        private const val MESSAGES     = "messages"
        private const val PAGE_SIZE    = 30

        private const val FIELD_MESSAGE_ID = "messageId"
        private const val FIELD_PARENT_ID  = "parentId"
        private const val FIELD_SENDER_ID  = "senderId"
        private const val FIELD_CONTENT    = "content"
        private const val FIELD_REPLY_TO   = "replyTo"
        private const val FIELD_TIMESTAMP  = "timestamp"
        private const val FIELD_EDITED_AT  = "editedAt"
        private const val FIELD_TYPE       = "type"
        private const val FIELD_STATUS     = "status"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun messagesCollection(conversationId: String) =
        firestore.collection(ROOT).document(conversationId).collection(MESSAGES)

    private fun messageDocument(conversationId: String, messageId: String) =
        messagesCollection(conversationId).document(messageId)

    // ─────────────────────────────────────────────────────────────────────────
    // Write — Single
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a new message to the given conversation.
     *
     * If [Message.messageId] is blank, Firestore auto-generates the document ID
     * and the field is populated before the write. [Message.senderId] is validated
     * against the authenticated user to prevent spoofing.
     *
     * @param conversationId Parent conversation ID.
     * @param message        The [Message] to persist.
     * @return The message document ID on success.
     */
    suspend fun sendMessage(
        conversationId: String,
        message: Message
    ): Result<String> = runCatching {
        val currentUser = auth.currentUser
            ?: error("No authenticated user is signed in.")
        require(message.senderId == currentUser.uid) {
            "senderId ('${message.senderId}') must match the authenticated user ('${currentUser.uid}')."
        }
        val docRef = if (message.messageId.isBlank()) messagesCollection(conversationId).document()
        else messageDocument(conversationId, message.messageId)

        docRef.set(message.copy(messageId = docRef.id, parentId = conversationId)).await()
        docRef.id
    }

    /**
     * Writes multiple messages to the same conversation in a single atomic batch,
     * reducing round-trips. All entries are validated before the batch is committed.
     *
     * @param conversationId Parent conversation ID.
     * @param messages       List of [Message] objects to persist.
     * @return A list of generated message IDs in the same order as [messages].
     */
    suspend fun sendMessagesBatch(
        conversationId: String,
        messages: List<Message>
    ): Result<List<String>> = runCatching {
        val currentUser = auth.currentUser
            ?: error("No authenticated user is signed in.")
        require(messages.isNotEmpty()) { "Messages list must not be empty." }

        val batch = firestore.batch()
        val ids   = mutableListOf<String>()

        messages.forEach { message ->
            require(message.senderId == currentUser.uid) {
                "All messages must have senderId matching the authenticated user."
            }
            val docRef = if (message.messageId.isBlank()) messagesCollection(conversationId).document()
            else messageDocument(conversationId, message.messageId)
            ids.add(docRef.id)
            batch.set(docRef, message.copy(messageId = docRef.id, parentId = conversationId))
        }

        batch.commit().await()
        ids
    }

    /**
     * Replaces the content of an existing message and stamps [FIELD_EDITED_AT]
     * with the current server timestamp. Only the original sender may edit.
     *
     * @param conversationId Parent conversation ID.
     * @param messageId      Message document ID.
     * @param newContent     Replacement content string.
     */
    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        newContent: String
    ): Result<Unit> = runCatching {
        val currentUser = auth.currentUser
            ?: error("No authenticated user is signed in.")
        require(newContent.isNotBlank()) { "Message content must not be blank." }

        val existing = messageDocument(conversationId, messageId).get().await()
            .toObject(Message::class.java)
            ?: error("Message '$messageId' does not exist.")
        require(existing.senderId == currentUser.uid) {
            "Only the original sender may edit this message."
        }

        messageDocument(conversationId, messageId).update(
            mapOf(
                FIELD_CONTENT   to newContent,
                FIELD_EDITED_AT to FieldValue.serverTimestamp(),
                FIELD_STATUS    to MessageStatus.SENT.value
            )
        ).await()
    }

    /**
     * Updates the delivery or read status of a single message.
     *
     * @param conversationId Parent conversation ID.
     * @param messageId      Message document ID.
     * @param status         The new [MessageStatus] to apply.
     */
    suspend fun updateMessageStatus(
        conversationId: String,
        messageId: String,
        status: MessageStatus
    ): Result<Unit> = runCatching {
        messageDocument(conversationId, messageId)
            .update(FIELD_STATUS, status.value)
            .await()
    }

    /**
     * Updates the status of multiple messages in a single atomic batch write.
     *
     * @param conversationId Parent conversation ID.
     * @param messageIds     Message document IDs to update.
     * @param status         The new [MessageStatus] to apply to all entries.
     */
    suspend fun updateMessageStatusBatch(
        conversationId: String,
        messageIds: List<String>,
        status: MessageStatus
    ): Result<Unit> = runCatching {
        require(messageIds.isNotEmpty()) { "messageIds must not be empty." }
        val batch = firestore.batch()
        messageIds.forEach { id ->
            batch.update(messageDocument(conversationId, id), FIELD_STATUS, status.value)
        }
        batch.commit().await()
    }

    /**
     * Deletes a single message document.
     *
     * @param conversationId Parent conversation ID.
     * @param messageId      Message document ID to delete.
     */
    suspend fun deleteMessage(conversationId: String, messageId: String): Result<Unit> = runCatching {
        messageDocument(conversationId, messageId).delete().await()
    }

    /**
     * Deletes multiple message documents in a single atomic batch write.
     *
     * @param conversationId Parent conversation ID.
     * @param messageIds     Message document IDs to delete.
     */
    suspend fun deleteMessagesBatch(
        conversationId: String,
        messageIds: List<String>
    ): Result<Unit> = runCatching {
        require(messageIds.isNotEmpty()) { "messageIds must not be empty." }
        val batch = firestore.batch()
        messageIds.forEach { batch.delete(messageDocument(conversationId, it)) }
        batch.commit().await()
    }

    /**
     * Deletes all messages within a conversation by fetching all document IDs and
     * issuing batch deletes in chunks of 500, respecting Firestore's hard batch limit.
     *
     * Typically called before deleting the parent conversation document.
     *
     * @param conversationId Parent conversation ID.
     */
    suspend fun deleteAllMessages(conversationId: String): Result<Unit> = runCatching {
        val ids = messagesCollection(conversationId).get().await().documents.map { it.id }
        ids.chunked(500).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(messageDocument(conversationId, it)) }
            batch.commit().await()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read — Single & Batch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single [Message] by its ID, or null if the document does not exist.
     *
     * @param conversationId Parent conversation ID.
     * @param messageId      Message document ID.
     */
    suspend fun fetchMessage(
        conversationId: String,
        messageId: String
    ): Result<Message?> = runCatching {
        messageDocument(conversationId, messageId).get().await().toObject(Message::class.java)
    }

    /**
     * Fetches multiple messages by their IDs in parallel. Documents that do not
     * exist are silently omitted from the result.
     *
     * @param conversationId Parent conversation ID.
     * @param messageIds     Message document IDs to retrieve.
     */
    suspend fun fetchMessagesByIds(
        conversationId: String,
        messageIds: List<String>
    ): Result<List<Message>> = runCatching {
        require(messageIds.isNotEmpty()) { "messageIds must not be empty." }
        coroutineScope {
            messageIds
                .map { id -> async { messageDocument(conversationId, id).get().await() } }
                .awaitAll()
                .mapNotNull { it.toObject(Message::class.java) }
        }
    }

    /**
     * Applies a [MessageFilter] to the messages sub-collection and returns all
     * matching records as a plain list.
     *
     * @param conversationId Parent conversation ID.
     * @param filter         [MessageFilter] describing the desired constraints.
     */
    suspend fun fetchMessagesFiltered(
        conversationId: String,
        filter: MessageFilter
    ): Result<List<Message>> = runCatching {
        filter.applyTo(messagesCollection(conversationId))
            .get()
            .await()
            .toObjects(Message::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches messages whose content starts with [prefix] using a lexicographic
     * range query. Firestore does not support native full-text search; only
     * prefix matches are possible here.
     *
     * For case-insensitive matching, normalise content to lowercase at write time
     * and pass a lowercase [prefix]. Requires an ascending index on `content`.
     *
     * @param conversationId Parent conversation ID.
     * @param prefix         Content prefix to match; must not be blank.
     * @param filter         Optional [MessageFilter] for additional sender, type,
     *                       or status constraints (range/sort on content is already applied).
     * @param limit          Maximum results to return.
     */
    suspend fun searchMessagesByContent(
        conversationId: String,
        prefix: String,
        filter: MessageFilter? = null,
        limit: Long = 20
    ): Result<List<Message>> = runCatching {
        require(prefix.isNotBlank()) { "Search prefix must not be blank." }
        val end = prefix.trimEnd() + "\uf8ff"
        var query: Query = messagesCollection(conversationId)
            .whereGreaterThanOrEqualTo(FIELD_CONTENT, prefix)
            .whereLessThanOrEqualTo(FIELD_CONTENT, end)
            .orderBy(FIELD_CONTENT, Query.Direction.ASCENDING)
            .limit(limit)

        filter?.senderId?.let { query = query.whereEqualTo(FIELD_SENDER_ID, it) }
        filter?.type?.let     { query = query.whereEqualTo(FIELD_TYPE, it.value) }
        filter?.status?.let   { query = query.whereEqualTo(FIELD_STATUS, it.value) }

        query.get().await().toObjects(Message::class.java)
    }

    /**
     * Fetches all messages from a specific sender within the given conversation,
     * ordered by timestamp descending. Optionally filtered by [MessageStatus].
     *
     * @param conversationId Parent conversation ID.
     * @param senderId       Firebase UID of the sender to filter by.
     * @param status         Optional [MessageStatus] to narrow results.
     * @param limit          Maximum results to return.
     */
    suspend fun searchMessagesBySender(
        conversationId: String,
        senderId: String,
        status: MessageStatus? = null,
        limit: Long = 30
    ): Result<List<Message>> = runCatching {
        require(senderId.isNotBlank()) { "senderId must not be blank." }
        var query: Query = messagesCollection(conversationId)
            .whereEqualTo(FIELD_SENDER_ID, senderId)
            .orderBy(FIELD_TIMESTAMP, Query.Direction.DESCENDING)
            .limit(limit)
        status?.let { query = query.whereEqualTo(FIELD_STATUS, it.value) }
        query.get().await().toObjects(Message::class.java)
    }

    /**
     * Fetches all replies targeting [parentMessageId], ordered by timestamp
     * ascending so the thread reads chronologically.
     *
     * @param conversationId  Parent conversation ID.
     * @param parentMessageId Message ID that replies reference in [replyTo].
     * @param limit           Maximum results to return.
     */
    suspend fun fetchReplies(
        conversationId: String,
        parentMessageId: String,
        limit: Long = 30
    ): Result<List<Message>> = runCatching {
        require(parentMessageId.isNotBlank()) { "parentMessageId must not be blank." }
        messagesCollection(conversationId)
            .whereEqualTo(FIELD_REPLY_TO, parentMessageId)
            .orderBy(FIELD_TIMESTAMP, Query.Direction.ASCENDING)
            .limit(limit)
            .get()
            .await()
            .toObjects(Message::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Real-Time Listeners
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits an updated [Message] whenever the given document changes, or null
     * if the document no longer exists.
     *
     * @param conversationId Parent conversation ID.
     * @param messageId      Message document ID to observe.
     */
    fun observeMessage(conversationId: String, messageId: String): Flow<Message?> = callbackFlow {
        val reg: ListenerRegistration =
            messageDocument(conversationId, messageId).addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                trySend(snapshot?.toObject(Message::class.java))
            }
        awaitClose { reg.remove() }
    }

    /**
     * Emits an updated list of [Message] objects whenever the messages
     * sub-collection changes, ordered by timestamp descending. An optional
     * [MessageFilter] narrows the observed query.
     *
     * @param conversationId Parent conversation ID.
     * @param filter         Optional [MessageFilter] to restrict the observed query.
     */
    fun observeMessages(
        conversationId: String,
        filter: MessageFilter? = null
    ): Flow<List<Message>> = callbackFlow {
        val base  = messagesCollection(conversationId)
            .orderBy(FIELD_TIMESTAMP, Query.Direction.DESCENDING)
        val query = filter?.applyTo(base) ?: base

        val reg: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObjects(Message::class.java) ?: emptyList())
        }
        awaitClose { reg.remove() }
    }

    /**
     * Emits real-time reply messages targeting [parentMessageId], ordered
     * ascending. The listener is removed automatically when the [Flow] is cancelled.
     *
     * @param conversationId  Parent conversation ID.
     * @param parentMessageId Message ID that replies reference in [replyTo].
     * @param limit           Maximum live results to stream.
     */
    fun observeReplies(
        conversationId: String,
        parentMessageId: String,
        limit: Long = 30
    ): Flow<List<Message>> = callbackFlow {
        if (parentMessageId.isBlank()) {
            trySend(emptyList())
            awaitClose()
            return@callbackFlow
        }
        val reg: ListenerRegistration = messagesCollection(conversationId)
            .whereEqualTo(FIELD_REPLY_TO, parentMessageId)
            .orderBy(FIELD_TIMESTAMP, Query.Direction.ASCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                trySend(snapshot?.toObjects(Message::class.java) ?: emptyList())
            }
        awaitClose { reg.remove() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Paging3
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated [Flow] over the messages sub-collection,
     * ordered by timestamp descending — the standard pattern for chat UIs.
     *
     * @param conversationId Parent conversation ID.
     * @param pageSize       Documents per page.
     */
    fun fetchMessagesPaged(
        conversationId: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Message>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false, prefetchDistance = pageSize / 2),
        pagingSourceFactory = {
            MessagePagingSource(
                messagesCollection(conversationId)
                    .orderBy(FIELD_TIMESTAMP, Query.Direction.DESCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    /**
     * Returns a cursor-paginated [Flow] with a [MessageFilter] applied, allowing
     * filtered message lists to benefit from incremental loading.
     *
     * @param conversationId Parent conversation ID.
     * @param filter         [MessageFilter] to apply before paginating.
     * @param pageSize       Documents per page.
     */
    fun fetchMessagesFilteredPaged(
        conversationId: String,
        filter: MessageFilter,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Message>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false, prefetchDistance = pageSize / 2),
        pagingSourceFactory = {
            MessagePagingSource(
                filter.applyTo(messagesCollection(conversationId)).limit(pageSize.toLong())
            )
        }
    ).flow
}

// ─────────────────────────────────────────────────────────────────────────────
// MessageFilter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable, composable filter descriptor for message queries.
 *
 * Only non-null fields are applied to the Firestore query. Combining multiple
 * inequality or ordering constraints may require a composite Firestore index.
 *
 * Usage:
 * ```kotlin
 * val filter = MessageFilter(
 *     senderId  = "user123",
 *     type      = MessageType.TEXT,
 *     status    = MessageStatus.SENT,
 *     after     = Timestamp(1_700_000_000, 0),
 *     sortOrder = Query.Direction.ASCENDING
 * )
 * ```
 *
 * @param senderId  Filters messages by a specific sender UID.
 * @param type      Filters messages by [MessageType].
 * @param status    Filters messages by [MessageStatus].
 * @param replyTo   Filters messages that are replies to a specific message ID.
 * @param after     Includes only messages sent after this [Timestamp].
 * @param before    Includes only messages sent before this [Timestamp].
 * @param sortOrder Sort direction on timestamp; defaults to [Query.Direction.DESCENDING].
 * @param limit     Caps the result set to this many documents.
 */
data class MessageFilter(
    val senderId: String? = null,
    val type: MessageType? = null,
    val status: MessageStatus? = null,
    val replyTo: String? = null,
    val after: Timestamp? = null,
    val before: Timestamp? = null,
    val sortOrder: Query.Direction = Query.Direction.DESCENDING,
    val limit: Long? = null
) {
    companion object {
        private const val FIELD_SENDER_ID = "senderId"
        private const val FIELD_TYPE      = "type"
        private const val FIELD_STATUS    = "status"
        private const val FIELD_REPLY_TO  = "replyTo"
        private const val FIELD_TIMESTAMP = "timestamp"

        /** All pending messages, oldest first. */
        val PENDING = MessageFilter(
            status    = MessageStatus.PENDING,
            sortOrder = Query.Direction.ASCENDING
        )

        /** All text messages, newest first. */
        val TEXT_ONLY = MessageFilter(type = MessageType.TEXT)
    }

    /** Applies all non-null constraints to [query] and returns the result. */
    fun applyTo(query: Query): Query {
        var q = query
        senderId?.let { q = q.whereEqualTo(FIELD_SENDER_ID, it) }
        type?.let     { q = q.whereEqualTo(FIELD_TYPE, it.value) }
        status?.let   { q = q.whereEqualTo(FIELD_STATUS, it.value) }
        replyTo?.let  { q = q.whereEqualTo(FIELD_REPLY_TO, it) }
        after?.let    { q = q.whereGreaterThan(FIELD_TIMESTAMP, it) }
        before?.let   { q = q.whereLessThan(FIELD_TIMESTAMP, it) }
        q = q.orderBy(FIELD_TIMESTAMP, sortOrder)
        limit?.let    { q = q.limit(it) }
        return q
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MessagePagingSource
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Firestore-backed [PagingSource] for [Message] documents.
 *
 * Uses cursor-based pagination via [DocumentSnapshot.startAfter] to avoid
 * re-reading previously loaded pages. Only forward pagination is supported;
 * a refresh always restarts from the most recent page.
 *
 * @param query Base Firestore [Query]; must include a [Query.limit] clause.
 */
class MessagePagingSource(
    private val query: Query
) : PagingSource<DocumentSnapshot, Message>() {

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, Message> = try {
        val pageQuery = params.key?.let { query.startAfter(it) } ?: query
        val snapshot  = pageQuery.get().await()
        val messages  = snapshot.toObjects(Message::class.java)
        val nextKey   = snapshot.documents.lastOrNull().takeUnless { snapshot.isEmpty }

        LoadResult.Page(data = messages, prevKey = null, nextKey = nextKey)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Message>): DocumentSnapshot? = null
}