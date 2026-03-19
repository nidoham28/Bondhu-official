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
 * @param firestore Injectable [FirebaseFirestore] instance.
 * @param auth      Injectable [FirebaseAuth] instance.
 */
class MessageManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val ROOT = "message"
        private const val MESSAGES = "messages"
        private const val PAGE_SIZE = 30

        private const val FIELD_MESSAGE_ID = "messageId"
        private const val FIELD_PARENT_ID = "parentId"
        private const val FIELD_SENDER_ID = "senderId"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_REPLY_TO = "replyTo"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_EDITED_AT = "editedAt"
        private const val FIELD_TYPE = "type"
        private const val FIELD_STATUS = "status"
    }

    private fun messagesCollection(conversationId: String) =
        firestore.collection(ROOT).document(conversationId).collection(MESSAGES)

    private fun messageDocument(conversationId: String, messageId: String) =
        messagesCollection(conversationId).document(messageId)

    // ─────────────────────────────────────────────────────────────────────────
    // Write — Single
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a new message validated against the current authenticated user.
     * Use this for standard user-to-user messages.
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
        saveMessage(conversationId, message)
    }

    /**
     * Writes a message WITHOUT validating the sender ID.
     *
     * Use this for system-generated messages, notifications, or AI responses
     * where the sender is a bot ID, not the currently authenticated user.
     *
     * @param conversationId Parent conversation ID.
     * @param message        The [Message] to persist.
     * @return The message document ID on success.
     */
    suspend fun addMessage(
        conversationId: String,
        message: Message
    ): Result<String> = runCatching {
        saveMessage(conversationId, message)
    }

    /**
     * Internal helper to persist message to Firestore.
     */
    private suspend fun saveMessage(conversationId: String, message: Message): String {
        val docRef = if (message.messageId.isBlank()) messagesCollection(conversationId).document()
        else messageDocument(conversationId, message.messageId)

        val finalMessage = message.copy(
            messageId = docRef.id,
            parentId = conversationId,
            timestamp = message.timestamp
        )

        docRef.set(finalMessage).await()
        return docRef.id
    }

    /**
     * Writes multiple messages validated against the current user.
     */
    suspend fun sendMessagesBatch(
        conversationId: String,
        messages: List<Message>
    ): Result<List<String>> = runCatching {
        val currentUser = auth.currentUser
            ?: error("No authenticated user is signed in.")
        require(messages.isNotEmpty()) { "Messages list must not be empty." }

        val batch = firestore.batch()
        val ids = mutableListOf<String>()

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
     * Writes multiple messages WITHOUT validating the sender.
     * Useful for injecting multiple system messages at once.
     */
    suspend fun addMessagesBatch(
        conversationId: String,
        messages: List<Message>
    ): Result<List<String>> = runCatching {
        require(messages.isNotEmpty()) { "Messages list must not be empty." }

        val batch = firestore.batch()
        val ids = mutableListOf<String>()

        messages.forEach { message ->
            val docRef = if (message.messageId.isBlank()) messagesCollection(conversationId).document()
            else messageDocument(conversationId, message.messageId)
            ids.add(docRef.id)
            batch.set(docRef, message.copy(messageId = docRef.id, parentId = conversationId))
        }

        batch.commit().await()
        ids
    }

    /**
     * Replaces the content of an existing message. Only the original sender may edit.
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
                FIELD_CONTENT to newContent,
                FIELD_EDITED_AT to FieldValue.serverTimestamp(),
                FIELD_STATUS to MessageStatus.SENT.value
            )
        ).await()
    }

    /**
     * Updates the delivery or read status of a single message.
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
     */
    suspend fun deleteMessage(conversationId: String, messageId: String): Result<Unit> = runCatching {
        messageDocument(conversationId, messageId).delete().await()
    }

    /**
     * Deletes multiple message documents in a single atomic batch write.
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
     * Deletes all messages within a conversation.
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

    suspend fun fetchMessage(
        conversationId: String,
        messageId: String
    ): Result<Message?> = runCatching {
        messageDocument(conversationId, messageId).get().await().toObject(Message::class.java)
    }

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
        filter?.type?.let { query = query.whereEqualTo(FIELD_TYPE, it.value) }
        filter?.status?.let { query = query.whereEqualTo(FIELD_STATUS, it.value) }

        query.get().await().toObjects(Message::class.java)
    }

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

    fun observeMessage(conversationId: String, messageId: String): Flow<Message?> = callbackFlow {
        val reg: ListenerRegistration =
            messageDocument(conversationId, messageId).addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                trySend(snapshot?.toObject(Message::class.java))
            }
        awaitClose { reg.remove() }
    }

    fun observeMessages(
        conversationId: String,
        filter: MessageFilter? = null
    ): Flow<List<Message>> = callbackFlow {
        val base = messagesCollection(conversationId)
            .orderBy(FIELD_TIMESTAMP, Query.Direction.DESCENDING)
        val query = filter?.applyTo(base) ?: base

        val reg: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObjects(Message::class.java) ?: emptyList())
        }
        awaitClose { reg.remove() }
    }

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
        private const val FIELD_TYPE = "type"
        private const val FIELD_STATUS = "status"
        private const val FIELD_REPLY_TO = "replyTo"
        private const val FIELD_TIMESTAMP = "timestamp"

        val PENDING = MessageFilter(
            status = MessageStatus.PENDING,
            sortOrder = Query.Direction.ASCENDING
        )

        val TEXT_ONLY = MessageFilter(type = MessageType.TEXT)
    }

    fun applyTo(query: Query): Query {
        var q = query
        senderId?.let { q = q.whereEqualTo(FIELD_SENDER_ID, it) }
        type?.let { q = q.whereEqualTo(FIELD_TYPE, it.value) }
        status?.let { q = q.whereEqualTo(FIELD_STATUS, it.value) }
        replyTo?.let { q = q.whereEqualTo(FIELD_REPLY_TO, it) }
        after?.let { q = q.whereGreaterThan(FIELD_TIMESTAMP, it) }
        before?.let { q = q.whereLessThan(FIELD_TIMESTAMP, it) }
        q = q.orderBy(FIELD_TIMESTAMP, sortOrder)
        limit?.let { q = q.limit(it) }
        return q
    }
}

class MessagePagingSource(
    private val query: Query
) : PagingSource<DocumentSnapshot, Message>() {

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, Message> = try {
        val pageQuery = params.key?.let { query.startAfter(it) } ?: query
        val snapshot = pageQuery.get().await()
        val messages = snapshot.toObjects(Message::class.java)
        val nextKey = snapshot.documents.lastOrNull().takeUnless { snapshot.isEmpty }

        LoadResult.Page(data = messages, prevKey = null, nextKey = nextKey)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Message>): DocumentSnapshot? = null
}