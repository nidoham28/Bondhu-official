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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Manages all Message-related Firestore operations.
 *
 * Firestore schema:
 *   conversations/{conversationId}/messages/{messageId} — Message document
 *
 * Every operation is keyed by [conversationId] and, where applicable,
 * [messageId]. The [sendMessage] method auto-generates [messageId] if
 * the [Message.messageId] field is blank.
 *
 * @param firestore Injectable [FirebaseFirestore] instance.
 * @param auth      Injectable [FirebaseAuth] instance.
 */
class MessageManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val CONVERSATION_COLLECTION = "conversations"
        private const val MESSAGE_SUB_COLLECTION  = "messages"
        private const val PAGE_SIZE               = 30

        private const val FIELD_MESSAGE_ID        = "message_id"
        private const val FIELD_SENDER_ID         = "sender_id"
        private const val FIELD_CONTENT           = "content"
        private const val FIELD_TIMESTAMP         = "timestamp"
        private const val FIELD_TYPE              = "type"
        private const val FIELD_STATUS            = "status"
        private const val FIELD_REPLY_TO          = "reply_to"
        private const val FIELD_EDITED_AT         = "edited_at"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun messagesCollection(conversationId: String) =
        firestore.collection(CONVERSATION_COLLECTION)
            .document(conversationId)
            .collection(MESSAGE_SUB_COLLECTION)

    private fun messageDocument(conversationId: String, messageId: String) =
        messagesCollection(conversationId).document(messageId)

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a new message to the given conversation.
     *
     * If [Message.messageId] is blank, Firestore auto-generates the document ID
     * and the field is populated before the write. The [Message.senderId] is
     * validated against the currently authenticated user to prevent spoofing.
     *
     * @param conversationId The parent conversation ID.
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

        val docRef    = if (message.messageId.isBlank()) messagesCollection(conversationId).document()
        else messageDocument(conversationId, message.messageId)
        val messageId = docRef.id
        val populated = message.copy(
            messageId      = messageId,
            conversationId = conversationId
        )

        docRef.set(populated).await()
        messageId
    }

    /**
     * Sends multiple messages to the same conversation in a single Firestore
     * batch write, reducing round-trips. All entries are validated before the
     * batch is committed.
     *
     * @param conversationId The parent conversation ID.
     * @param messages       The list of [Message] objects to persist.
     * @return A list of the generated message IDs in the same order as [messages].
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
            val docRef    = if (message.messageId.isBlank()) messagesCollection(conversationId).document()
            else messageDocument(conversationId, message.messageId)
            val messageId = docRef.id
            ids.add(messageId)
            batch.set(docRef, message.copy(messageId = messageId, conversationId = conversationId))
        }

        batch.commit().await()
        ids
    }

    /**
     * Edits the content of an existing message. Sets [FIELD_EDITED_AT] to the
     * current server timestamp and updates the status to [MessageStatus.SENT].
     *
     * Only the original sender may edit a message; this is enforced by comparing
     * [senderId] against the authenticated user.
     *
     * @param conversationId The parent conversation ID.
     * @param messageId      The message document ID.
     * @param newContent     The replacement content string.
     */
    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        newContent: String
    ): Result<Unit> = runCatching {
        val currentUser = auth.currentUser
            ?: error("No authenticated user is signed in.")

        val snapshot = messageDocument(conversationId, messageId).get().await()
        val existing = snapshot.toObject(Message::class.java)
            ?: error("Message '$messageId' does not exist.")
        require(existing.senderId == currentUser.uid) {
            "Only the original sender may edit this message."
        }
        require(newContent.isNotBlank()) { "Message content must not be blank." }

        messageDocument(conversationId, messageId).update(
            mapOf(
                FIELD_CONTENT   to newContent,
                FIELD_EDITED_AT to FieldValue.serverTimestamp(),
                FIELD_STATUS    to MessageStatus.SENT.name.lowercase()
            )
        ).await()
    }

    /**
     * Updates the delivery or read status of a single message.
     *
     * @param conversationId The parent conversation ID.
     * @param messageId      The message document ID.
     * @param status         The new [MessageStatus] to apply.
     */
    suspend fun updateMessageStatus(
        conversationId: String,
        messageId: String,
        status: MessageStatus
    ): Result<Unit> = runCatching {
        messageDocument(conversationId, messageId)
            .update(FIELD_STATUS, status.name.lowercase())
            .await()
    }

    /**
     * Updates the statuses of multiple messages in a single batch write.
     *
     * @param conversationId The parent conversation ID.
     * @param messageIds     The message document IDs to update.
     * @param status         The new [MessageStatus] to apply to all entries.
     */
    suspend fun updateMessageStatusBatch(
        conversationId: String,
        messageIds: List<String>,
        status: MessageStatus
    ): Result<Unit> = runCatching {
        require(messageIds.isNotEmpty()) { "messageIds must not be empty." }
        val batch = firestore.batch()
        messageIds.forEach { messageId ->
            batch.update(
                messageDocument(conversationId, messageId),
                FIELD_STATUS, status.name.lowercase()
            )
        }
        batch.commit().await()
    }

    /**
     * Deletes a single message document.
     *
     * @param conversationId The parent conversation ID.
     * @param messageId      The message document ID to delete.
     */
    suspend fun deleteMessage(
        conversationId: String,
        messageId: String
    ): Result<Unit> = runCatching {
        messageDocument(conversationId, messageId).delete().await()
    }

    /**
     * Deletes multiple message documents in a single batch write.
     *
     * @param conversationId The parent conversation ID.
     * @param messageIds     The message document IDs to delete.
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
     * Deletes all messages within a conversation by fetching every document ID
     * and issuing batch deletes in chunks of 500, respecting Firestore's hard
     * limit per batch operation.
     *
     * This is typically called before deleting the parent conversation document.
     *
     * @param conversationId The parent conversation ID.
     */
    suspend fun deleteAllMessages(conversationId: String): Result<Unit> = runCatching {
        val ids = messagesCollection(conversationId)
            .get()
            .await()
            .documents
            .map { it.id }

        if (ids.isNotEmpty()) {
            ids.chunked(500).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { batch.delete(messageDocument(conversationId, it)) }
                batch.commit().await()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single [Message] by its ID within a conversation.
     *
     * @return The [Message] object, or null if the document does not exist.
     */
    suspend fun fetchMessage(
        conversationId: String,
        messageId: String
    ): Result<Message?> = runCatching {
        messageDocument(conversationId, messageId)
            .get()
            .await()
            .toObject(Message::class.java)
    }

    /**
     * Fetches multiple messages by their IDs in a single round of parallel reads.
     * Documents that do not exist are silently omitted.
     *
     * @param conversationId The parent conversation ID.
     * @param messageIds     The message document IDs to retrieve.
     */
    suspend fun fetchMessagesByIds(
        conversationId: String,
        messageIds: List<String>
    ): Result<List<Message>> = runCatching {
        require(messageIds.isNotEmpty()) { "messageIds must not be empty." }
        messageIds
            .map { messageDocument(conversationId, it).get().await() }
            .mapNotNull { it.toObject(Message::class.java) }
    }

    /**
     * Applies a [MessageFilter] to the messages sub-collection and returns all
     * matching records as a plain list.
     *
     * @param conversationId The parent conversation ID.
     * @param filter         The [MessageFilter] describing the desired constraints.
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
    // Search Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches messages whose [content] field starts with the given [prefix],
     * using a lexicographic range query on the `content` field.
     *
     * Because Firestore does not support native full-text search, this method
     * matches only messages whose content begins with [prefix] exactly as stored.
     * For case-insensitive matching, normalise content to lowercase at write time
     * and pass a lowercase [prefix] here.
     *
     * Requires an ascending single-field index on `content`. If combined with a
     * [MessageFilter] that adds additional constraints, a composite index may
     * be required.
     *
     * @param conversationId The parent conversation ID to search within.
     * @param prefix         The content prefix to match. Must not be blank.
     * @param filter         Optional [MessageFilter] to add sender, type, or
     *                       status constraints on top of the content search.
     * @param limit          Maximum number of results to return. Defaults to 20.
     * @return A list of matching [Message] objects ordered by content ascending.
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
        // Selectively apply safe filter fields that do not conflict with
        // the content range query's required orderBy on FIELD_CONTENT.
        filter?.senderId?.let { query = query.whereEqualTo(FIELD_SENDER_ID, it) }
        filter?.type?.let     { query = query.whereEqualTo(FIELD_TYPE, it.name.lowercase()) }
        filter?.status?.let   { query = query.whereEqualTo(FIELD_STATUS, it.name.lowercase()) }

        query.get().await().toObjects(Message::class.java)
    }

    /**
     * Fetches all messages sent by a specific user within the given conversation,
     * ordered by timestamp descending. Optionally filtered by [MessageStatus].
     *
     * @param conversationId The parent conversation ID.
     * @param senderId       The Firebase UID of the sender to filter by.
     * @param status         Optional [MessageStatus] to narrow results further.
     * @param limit          Maximum number of results to return. Defaults to 30.
     * @return A list of matching [Message] objects ordered by timestamp descending.
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
        status?.let { query = query.whereEqualTo(FIELD_STATUS, it.name.lowercase()) }
        query.get().await().toObjects(Message::class.java)
    }

    /**
     * Fetches all reply messages targeting a specific parent message, ordered
     * by timestamp ascending so the thread reads chronologically.
     *
     * @param conversationId   The parent conversation ID.
     * @param parentMessageId  The message ID that replies reference in [reply_to].
     * @param limit            Maximum number of results to return. Defaults to 30.
     * @return A list of [Message] objects that are replies to [parentMessageId].
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

    /**
     * Observes all reply messages targeting a specific parent message in real
     * time. The listener is removed automatically when the [Flow] is cancelled.
     *
     * @param conversationId  The parent conversation ID.
     * @param parentMessageId The message ID that replies reference in [reply_to].
     * @param limit           Maximum number of live results to stream. Defaults to 30.
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
        val query: Query = messagesCollection(conversationId)
            .whereEqualTo(FIELD_REPLY_TO, parentMessageId)
            .orderBy(FIELD_TIMESTAMP, Query.Direction.ASCENDING)
            .limit(limit)

        val registration: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObjects(Message::class.java) ?: emptyList())
        }
        awaitClose { registration.remove() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Real-Time Listeners
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits an updated [Message] object whenever the given document changes,
     * or null if the document no longer exists.
     *
     * @param conversationId The parent conversation ID.
     * @param messageId      The message document ID to observe.
     */
    fun observeMessage(
        conversationId: String,
        messageId: String
    ): Flow<Message?> = callbackFlow {
        val registration: ListenerRegistration =
            messageDocument(conversationId, messageId).addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                trySend(snapshot?.toObject(Message::class.java))
            }
        awaitClose { registration.remove() }
    }

    /**
     * Emits an updated list of [Message] objects whenever the messages
     * sub-collection of the given conversation changes, ordered by timestamp
     * descending. An optional [MessageFilter] narrows the listener query.
     *
     * @param conversationId The parent conversation ID.
     * @param filter         Optional [MessageFilter] to restrict the observed query.
     */
    fun observeMessages(
        conversationId: String,
        filter: MessageFilter? = null
    ): Flow<List<Message>> = callbackFlow {
        val baseQuery = messagesCollection(conversationId)
            .orderBy(FIELD_TIMESTAMP, Query.Direction.DESCENDING) as Query
        val query     = filter?.applyTo(baseQuery) ?: baseQuery

        val registration: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObjects(Message::class.java) ?: emptyList())
        }
        awaitClose { registration.remove() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Paging3 Support
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a [Flow] of cursor-paginated [PagingData] over the messages
     * sub-collection, ordered by timestamp descending so the most recent
     * messages load first — the standard pattern for chat UIs.
     *
     * @param conversationId The parent conversation ID.
     * @param pageSize       Number of documents to load per page.
     */
    fun fetchMessagesPaged(
        conversationId: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Message>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false,
            prefetchDistance   = pageSize / 2
        ),
        pagingSourceFactory = {
            MessagePagingSource(
                query = messagesCollection(conversationId)
                    .orderBy(FIELD_TIMESTAMP, Query.Direction.DESCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    /**
     * Returns a [Flow] of cursor-paginated [PagingData] applying a
     * [MessageFilter], allowing filtered message lists to also benefit from
     * incremental loading.
     *
     * @param conversationId The parent conversation ID.
     * @param filter         The [MessageFilter] to apply.
     * @param pageSize       Number of documents to load per page.
     */
    fun fetchMessagesFilteredPaged(
        conversationId: String,
        filter: MessageFilter,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Message>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false,
            prefetchDistance   = pageSize / 2
        ),
        pagingSourceFactory = {
            MessagePagingSource(
                query = filter.applyTo(messagesCollection(conversationId))
                    .limit(pageSize.toLong())
            )
        }
    ).flow
}

// ─────────────────────────────────────────────────────────────────────────────
// MessageFilter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A composable, immutable filter descriptor for message queries.
 *
 * Each field is optional. Only non-null fields are applied to the Firestore
 * query, so any combination of constraints is valid. Combining multiple fields
 * on the same query may require a composite Firestore index.
 *
 * Usage example:
 * ```kotlin
 * val filter = MessageFilter(
 *     senderId  = "user123",
 *     type      = MessageType.TEXT,
 *     status    = MessageStatus.SENT,
 *     after     = Timestamp(1_700_000_000, 0),
 *     sortOrder = Query.Direction.ASCENDING
 * )
 * manager.fetchMessagesFiltered(conversationId, filter)
 * ```
 *
 * @param senderId  Filters messages by a specific sender UID.
 * @param type      Filters messages by [MessageType].
 * @param status    Filters messages by [MessageStatus].
 * @param replyTo   Filters messages that are replies to a specific message ID.
 * @param after     Includes only messages sent after this [Timestamp].
 * @param before    Includes only messages sent before this [Timestamp].
 * @param sortOrder Sort direction on the timestamp field; defaults to [Query.Direction.DESCENDING].
 * @param limit     Caps the result set to the given number of documents.
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
        /** All pending (unread) messages, oldest first. */
        val PENDING = MessageFilter(
            status    = MessageStatus.PENDING,
            sortOrder = Query.Direction.ASCENDING
        )

        /** All text messages, newest first. */
        val TEXT_ONLY = MessageFilter(type = MessageType.TEXT)
    }

    /**
     * Applies all non-null constraints to the given [Query] and returns the
     * resulting constrained [Query].
     *
     * @param query The base Firestore [Query] to build upon.
     */
    fun applyTo(query: Query): Query {
        var q = query

        senderId?.let  { q = q.whereEqualTo("sender_id", it) }
        type?.let      { q = q.whereEqualTo("type", it.name.lowercase()) }
        status?.let    { q = q.whereEqualTo("status", it.name.lowercase()) }
        replyTo?.let   { q = q.whereEqualTo("reply_to", it) }
        after?.let     { q = q.whereGreaterThan("timestamp", it) }
        before?.let    { q = q.whereLessThan("timestamp", it) }

        q = q.orderBy("timestamp", sortOrder)

        limit?.let { q = q.limit(it) }

        return q
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MessagePagingSource
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A Firestore-backed [PagingSource] for [Message] documents.
 *
 * Uses cursor-based pagination via [DocumentSnapshot.startAfter] to avoid
 * re-reading previously loaded pages. Only forward pagination is supported;
 * refreshes always restart from the most recent page.
 *
 * @param query The base Firestore [Query] — must include a [Query.limit] clause.
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
        val nextKey   = snapshot.documents.lastOrNull().takeUnless { snapshot.isEmpty() }

        LoadResult.Page(data = messages, prevKey = null, nextKey = nextKey)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(
        state: PagingState<DocumentSnapshot, Message>
    ): DocumentSnapshot? = null
}