package org.nidoham.server.data.repository

import androidx.paging.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.nidoham.server.domain.model.*
import java.util.Locale

// ═════════════════════════════════════════════════════════════════════════════
// SECTION 1 — PAGING SOURCES (historical message loading)
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Loads messages in reverse-chronological order (newest first).
 * Used for scrolling back through history.
 * Cursor = DocumentSnapshot (not Timestamp) to avoid timestamp-collision bugs.
 */
class MessagePagingSource(
    private val messagesCollection: CollectionReference
) : PagingSource<DocumentSnapshot, Message>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Message>): DocumentSnapshot? = null

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, Message> {
        return try {
            var query: Query = messagesCollection
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(params.loadSize.toLong())

            if (params.key != null) {
                query = query.startAfter(params.key!!)
            }

            val snapshot = query.get().await()
            val messages = snapshot.documents.mapNotNull { it.toObject(Message::class.java) }

            LoadResult.Page(
                data = messages,
                prevKey = null, // newest-first; no backwards paging needed
                nextKey = if (snapshot.documents.size < params.loadSize) null
                else snapshot.documents.lastOrNull()
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

/**
 * Prefix-search PagingSource on `searchable_content`.
 */
class MessageSearchPagingSource(
    private val messagesCollection: CollectionReference,
    private val normalizedQuery: String
) : PagingSource<DocumentSnapshot, Message>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Message>): DocumentSnapshot? = null

    override suspend fun load(params: LoadParams<DocumentSnapshot>): LoadResult<DocumentSnapshot, Message> {
        return try {
            val endAt = normalizedQuery + "\uf8ff"

            var query: Query = messagesCollection
                .whereGreaterThanOrEqualTo("searchable_content", normalizedQuery)
                .whereLessThanOrEqualTo("searchable_content", endAt)
                .orderBy("searchable_content")
                .limit(params.loadSize.toLong())

            if (params.key != null) {
                query = query.startAfter(params.key!!)
            }

            val snapshot = query.get().await()
            val messages = snapshot.documents.mapNotNull { it.toObject(Message::class.java) }

            LoadResult.Page(
                data = messages,
                prevKey = null,
                nextKey = if (snapshot.documents.size < params.loadSize) null
                else snapshot.documents.lastOrNull()
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

/** Utility: always-empty PagingSource (used for blank search queries). */
private class EmptyPagingSource<K : Any, V : Any> : PagingSource<K, V>() {
    override fun getRefreshKey(state: PagingState<K, V>): K? = null
    override suspend fun load(params: LoadParams<K>): LoadResult<K, V> =
        LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
}

// ═════════════════════════════════════════════════════════════════════════════
// SECTION 2 — REAL-TIME EVENT MODELS
// ═════════════════════════════════════════════════════════════════════════════

/** Represents a real-time change event for a single message. */
sealed class MessageEvent {
    data class Added(val message: Message)    : MessageEvent()
    data class Modified(val message: Message) : MessageEvent()
    data class Removed(val message: Message)  : MessageEvent()
}

/** Typing state for a conversation. */
data class TypingState(
    val conversationId: String,
    val typingUserIds: List<String>  // user IDs currently typing
)

// ═════════════════════════════════════════════════════════════════════════════
// SECTION 3 — MESSAGE MANAGER
// ═════════════════════════════════════════════════════════════════════════════

class MessageManager(private val firestore: FirebaseFirestore) {

    companion object {
        private const val PAGE_SIZE              = 20
        private const val REALTIME_WINDOW        = 30   // messages kept in live feed
        private const val COL_MESSAGES           = "messages"
        private const val COL_TYPING             = "typing"
        private const val TYPING_TIMEOUT_MS      = 5_000L
    }

    // ── Collection helpers ────────────────────────────────────────────────────

    private fun messagesCol(conversationId: String): CollectionReference =
        firestore.collection(COL_MESSAGES)
            .document(conversationId)
            .collection(COL_MESSAGES)

    private fun conversationDoc(conversationId: String): DocumentReference =
        firestore.collection(COL_MESSAGES).document(conversationId)

    private fun typingDoc(conversationId: String): DocumentReference =
        firestore.collection(COL_TYPING).document(conversationId)

    // ─────────────────────────────────────────────────────────────────────────
    // 3A. REAL-TIME LISTENERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits real-time [MessageEvent]s (ADDED / MODIFIED / REMOVED) for
     * the most recent [windowSize] messages in a conversation.
     *
     * Messenger-style usage:
     *   - Subscribe immediately when the chat screen opens.
     *   - Display ADDED events at the top of the list.
     *   - Apply MODIFIED events in-place (edit, status change, unsend).
     *   - Remove REMOVED events from the local list.
     *
     * The Flow stays active until the collector is cancelled (e.g. viewModelScope).
     *
     * Usage (ViewModel):
     *   messageManager
     *       .observeMessageEvents(conversationId)
     *       .onEach { event -> handleEvent(event) }
     *       .launchIn(viewModelScope)
     */
    fun observeMessageEvents(
        conversationId: String,
        windowSize: Int = REALTIME_WINDOW
    ): Flow<MessageEvent> = callbackFlow {

        val query = messagesCol(conversationId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(windowSize.toLong())

        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener

            for (change in snapshot.documentChanges) {
                val message = change.document.toObject(Message::class.java)
                val event = when (change.type) {
                    DocumentChange.Type.ADDED    -> MessageEvent.Added(message)
                    DocumentChange.Type.MODIFIED -> MessageEvent.Modified(message)
                    DocumentChange.Type.REMOVED  -> MessageEvent.Removed(message)
                }
                trySend(event)
            }
        }

        // Remove listener when the Flow collector is cancelled
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    /**
     * Convenience wrapper — emits the full live message list on every change.
     * Prefer [observeMessageEvents] for fine-grained control.
     *
     * Usage (ViewModel):
     *   val liveMessages: StateFlow<List<Message>> = messageManager
     *       .observeLiveMessages(conversationId)
     *       .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
     */
    fun observeLiveMessages(
        conversationId: String,
        windowSize: Int = REALTIME_WINDOW
    ): Flow<List<Message>> = callbackFlow {

        val query = messagesCol(conversationId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(windowSize.toLong())

        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            val messages = snapshot.documents.mapNotNull { it.toObject(Message::class.java) }
            trySend(messages)
        }

        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // 3B. PAGED HISTORY  (Paging 3)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a [Flow<PagingData<Message>>] for loading older history.
     *
     * How to combine with real-time feed in your ViewModel:
     *
     *   // Live feed (newest messages, auto-updating)
     *   val liveMessages = messageManager
     *       .observeLiveMessages(conversationId)
     *       .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
     *
     *   // History (paginated, load-on-scroll)
     *   val history = messageManager
     *       .getMessageHistory(conversationId)
     *       .cachedIn(viewModelScope)
     *
     * In your UI:
     *   - Show `liveMessages` at the top (RecyclerView header / Compose LazyColumn reversed)
     *   - Append paginated `history` below as user scrolls up
     */
    fun getMessageHistory(
        conversationId: String,
        pageSize: Int = PAGE_SIZE,
        startAfterTimestamp: Timestamp? = null
    ): Flow<PagingData<Message>> = Pager(
        config = PagingConfig(
            pageSize = pageSize,
            enablePlaceholders = false,
            prefetchDistance = pageSize / 2
        ),
        pagingSourceFactory = {
            MessagePagingSource(messagesCol(conversationId))
        }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // 3C. PAGED SEARCH
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Paged prefix-search within a conversation.
     *
     * Usage (ViewModel):
     *   private val _query = MutableStateFlow("")
     *   val searchResults = _query
     *       .debounce(300)
     *       .flatMapLatest { q ->
     *           messageManager.searchMessagesPaged(conversationId, q)
     *       }
     *       .cachedIn(viewModelScope)
     */
    fun searchMessagesPaged(
        conversationId: String,
        queryText: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Message>> {
        val normalized = queryText.lowercase(Locale.getDefault()).trim()
        return Pager(
            config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
            pagingSourceFactory = {
                if (normalized.isEmpty()) EmptyPagingSource()
                else MessageSearchPagingSource(messagesCol(conversationId), normalized)
            }
        ).flow
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3D. TYPING INDICATORS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Call when the local user starts or stops typing.
     * Firestore document: typing/{conversationId}
     * Structure: { userId: serverTimestamp | FieldValue.delete() }
     */
    suspend fun setTyping(
        conversationId: String,
        userId: String,
        isTyping: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val value: Any = if (isTyping) FieldValue.serverTimestamp() else FieldValue.delete()
            typingDoc(conversationId)
                .set(mapOf(userId to value), SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Observes who is currently typing in a conversation.
     * Emits a [TypingState] on every change.
     *
     * Usage (ViewModel):
     *   val typingState = messageManager
     *       .observeTyping(conversationId, currentUserId)
     *       .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TypingState(conversationId, emptyList()))
     *
     * In UI: show "Alice is typing…" when typingState.typingUserIds is non-empty
     * (excluding the current user).
     */
    fun observeTyping(
        conversationId: String,
        currentUserId: String
    ): Flow<TypingState> = callbackFlow {

        val registration = typingDoc(conversationId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    trySend(TypingState(conversationId, emptyList()))
                    return@addSnapshotListener
                }

                val now = System.currentTimeMillis()
                val typingUserIds = snapshot.data
                    ?.entries
                    ?.filter { (uid, value) ->
                        // Exclude self, and exclude stale entries older than TYPING_TIMEOUT_MS
                        if (uid == currentUserId) return@filter false
                        val ts = (value as? Timestamp)?.toDate()?.time ?: return@filter false
                        (now - ts) < TYPING_TIMEOUT_MS
                    }
                    ?.map { it.key }
                    ?: emptyList()

                trySend(TypingState(conversationId, typingUserIds))
            }

        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // 3E. PRESENCE (online / last seen)
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // 3F. DELIVERY RECEIPTS — auto mark DELIVERED on observation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Call this once when the chat screen becomes visible.
     * Marks all SENT messages (not sent by [currentUserId]) as DELIVERED.
     *
     * For READ receipts, call [markMessageRead] per message when it
     * scrolls into the viewport.
     */
    suspend fun markAllDelivered(
        conversationId: String,
        currentUserId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sentStatus = MessageStatus.SENT.name.lowercase(Locale.getDefault())
            val snapshot = messagesCol(conversationId)
                .whereEqualTo("status", sentStatus)
                .whereNotEqualTo("sender_id", currentUserId)
                .get()
                .await()

            if (snapshot.isEmpty) return@withContext Result.success(Unit)

            val batch = firestore.batch()
            snapshot.documents.forEach { doc ->
                batch.update(
                    doc.reference,
                    "status", MessageStatus.DELIVERED.name.lowercase(Locale.getDefault())
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mark a single message as READ by [userId].
     * Updates both the `read_by` array and the `status` field to READ
     * when called by all participants.
     */
    suspend fun markMessageRead(
        conversationId: String,
        messageId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val docRef = messagesCol(conversationId).document(messageId)
            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                val readBy = snapshot.get("read_by") as? List<*> ?: emptyList<String>()

                // Only write if not already marked read by this user
                if (userId !in readBy) {
                    transaction.update(docRef, "read_by", FieldValue.arrayUnion(userId))
                    transaction.update(
                        docRef,
                        "status",
                        MessageStatus.READ.name.lowercase(Locale.getDefault())
                    )
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3G. WRITE OPERATIONS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a new message.
     * - Status = SENDING while batch is in-flight.
     * - Status = SENT after Firestore confirms the write.
     * - Atomically updates conversation container metadata.
     */
    suspend fun pushMessage(message: Message): Result<Message> = withContext(Dispatchers.IO) {
        try {
            val batch  = firestore.batch()
            val colRef = messagesCol(message.conversationId)
            val docRef = if (message.messageId.isNotEmpty()) colRef.document(message.messageId)
            else colRef.document()
            val now    = Timestamp.now()

            val outgoing = message.copy(
                messageId       = docRef.id,
                timestamp       = now,
                searchableContent = message.content.lowercase(Locale.getDefault()),
                status          = MessageStatus.SENDING.name.lowercase(Locale.getDefault())
            )

            batch.set(docRef, outgoing)

            // Update conversation-level metadata
            batch.set(
                conversationDoc(message.conversationId),
                mapOf(
                    "last_message_id"        to outgoing.messageId,
                    "last_message_timestamp" to now,
                    "last_message_preview"   to outgoing.content.take(100),
                    "updated_at"             to now
                ),
                SetOptions.merge()
            )

            batch.commit().await()

            // Firestore confirmed — promote to SENT
            val sentStatus = MessageStatus.SENT.name.lowercase(Locale.getDefault())
            docRef.update("status", sentStatus).await()

            Result.success(outgoing.copy(status = sentStatus))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Edit message content (updates searchable_content + edited_at). */
    suspend fun updateMessageContent(
        conversationId: String,
        messageId: String,
        newContent: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            messagesCol(conversationId).document(messageId).update(
                mapOf(
                    "content"            to newContent,
                    "searchable_content" to newContent.lowercase(Locale.getDefault()),
                    "edited_at"          to Timestamp.now()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Unsend / delete a message for self or everyone. */
    suspend fun unsendMessage(
        conversationId: String,
        messageId: String,
        status: UnsentStatus
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            messagesCol(conversationId).document(messageId).update(
                mapOf(
                    "unsent"    to status.name.lowercase(Locale.getDefault()),
                    "unsent_at" to Timestamp.now()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}