package com.nidoham.bondhu.data.repository.message

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.nidoham.server.data.repository.ConversationManager
import org.nidoham.server.data.repository.MessageEvent
import org.nidoham.server.data.repository.MessageManager
import org.nidoham.server.data.repository.TypingState
import org.nidoham.server.domain.model.*
import java.util.Locale

// ═════════════════════════════════════════════════════════════════════════════
// RESULT WRAPPERS
// ═════════════════════════════════════════════════════════════════════════════

/** Reason why a user is not allowed to send a message. */
enum class SendDeniedReason {
    NOT_A_PARTICIPANT,
    BLOCKED_BY_TARGET,
    BLOCKED_TARGET,
    CHANNEL_ADMIN_ONLY,    // only admins/owners can post in channels
    MUTED,
    CONVERSATION_NOT_FOUND
}

sealed class SendPermission {
    object Allowed : SendPermission()
    data class Denied(val reason: SendDeniedReason) : SendPermission()
}

// ═════════════════════════════════════════════════════════════════════════════
// PARTICIPANT PAGING SOURCE
// Paginates the participants list stored inside the Conversation document.
// Since participants is an embedded array (not a sub-collection), we page it
// client-side from a single document fetch — correct for typical group sizes.
// For very large channels (1000+ members) migrate participants to a sub-collection.
// ═════════════════════════════════════════════════════════════════════════════

private class ParticipantPagingSource(
    private val firestore: FirebaseFirestore,
    private val conversationId: String,
    private val roleFilter: ParticipantRole?   // null = all roles
) : PagingSource<Int, Participant>() {

    override fun getRefreshKey(state: PagingState<Int, Participant>): Int? =
        state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Participant> {
        return try {
            val snapshot = firestore.collection("conversations")
                .document(conversationId)
                .get()
                .await()

            val conversation = snapshot.toObject(Conversation::class.java)
                ?: return LoadResult.Error(Exception("Conversation not found"))

            val filtered = if (roleFilter == null) {
                conversation.participants
            } else {
                conversation.participants.filter { it.toRole() == roleFilter }
            }

            val pageStart = params.key ?: 0
            val pageEnd   = minOf(pageStart + params.loadSize, filtered.size)
            val page      = filtered.subList(pageStart, pageEnd)

            LoadResult.Page(
                data    = page,
                prevKey = if (pageStart == 0) null else pageStart - params.loadSize,
                nextKey = if (pageEnd >= filtered.size) null else pageEnd
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// MESSAGE REPOSITORY
// Single entry-point for all chat features.
// Orchestrates MessageManager (messages) and ConversationManager (conversations).
// ═════════════════════════════════════════════════════════════════════════════

@javax.inject.Singleton
class MessageRepository @javax.inject.Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messageManager: MessageManager,
    private val conversationManager: ConversationManager
) {

    companion object {
        private const val DEFAULT_PAGE_SIZE   = 20
        private const val COL_CONVERSATIONS   = "conversations"
        private const val COL_MESSAGES        = "messages"
    }

    private fun conversationCol(): CollectionReference =
        firestore.collection(COL_CONVERSATIONS)

    private fun messagesSubCol(conversationId: String): CollectionReference =
        firestore.collection(COL_MESSAGES)
            .document(conversationId)
            .collection(COL_MESSAGES)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 1 — CONVERSATION CREATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new conversation.
     *
     * - PRIVATE: checks for an existing 1-on-1 before creating a new one.
     *   Returns the existing conversation if found.
     * - GROUP / CHANNEL: always creates a new document.
     *
     * Usage:
     *   val result = repository.createConversation(
     *       creatorId = currentUserId,
     *       title = "Team Chat",
     *       type = ConversationType.GROUP,
     *       initialParticipantIds = listOf(userId2, userId3)
     *   )
     */
    suspend fun createConversation(
        creatorId: String,
        title: String,
        type: ConversationType,
        initialParticipantIds: List<String> = emptyList(),
        photoUrl: String? = null
    ): Result<Conversation> = withContext(Dispatchers.IO) {
        // For PRIVATE chats — return existing conversation if one already exists
        if (type == ConversationType.PRIVATE) {
            val targetId = initialParticipantIds.firstOrNull { it != creatorId }
            if (targetId != null) {
                val existing = conversationManager.findExistingPrivateConversation(
                    userId     = creatorId,
                    opponentId = targetId
                )
                existing.getOrNull()?.let { return@withContext Result.success(it) }
            }
        }

        conversationManager.createConversation(
            creatorId             = creatorId,
            title                 = title,
            type                  = type,
            initialParticipantIds = initialParticipantIds,
            photoUrl              = photoUrl
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 2 — CONVERSATION ID LOOKUP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves the conversation ID for a given type + user pair.
     *
     * - PRIVATE: returns the existing private conversation ID between [userId]
     *   and [targetUserId], or null if none exists yet.
     * - GROUP / CHANNEL: returns null (use [getConversationsByType] to list them).
     *
     * Usage:
     *   val convId = repository.resolveConversationId(
     *       type = ConversationType.PRIVATE,
     *       userId = me,
     *       targetUserId = them
     *   )
     */
    suspend fun resolveConversationId(
        type: ConversationType,
        userId: String,
        targetUserId: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            when (type) {
                ConversationType.PRIVATE -> {
                    val result = conversationManager.findExistingPrivateConversation(
                        userId     = userId,
                        opponentId = targetUserId
                    )
                    Result.success(result.getOrNull()?.conversationId)
                }
                else -> Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 3 — SEND PERMISSION CHECK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks whether [userId] is allowed to send a message in [conversationId].
     *
     * Rules enforced:
     *  1. User must be an active participant.
     *  2. User must not have muted themselves (configurable — you may relax this).
     *  3. CHANNEL conversations only allow ADMIN/OWNER to post.
     *  4. User must not be blocked by the other participant (PRIVATE only).
     *  5. User must not have blocked the other participant (PRIVATE only).
     *
     * Usage:
     *   when (val perm = repository.isAllowedToSendMessage(convId, currentUserId)) {
     *       is SendPermission.Allowed -> sendMessage()
     *       is SendPermission.Denied  -> showError(perm.reason)
     *   }
     */
    suspend fun isAllowedToSendMessage(
        conversationId: String,
        userId: String
    ): SendPermission = withContext(Dispatchers.IO) {
        val convResult = conversationManager.getConversation(conversationId)
        val conversation = convResult.getOrNull()
            ?: return@withContext SendPermission.Denied(SendDeniedReason.CONVERSATION_NOT_FOUND)

        val self = conversation.participants.find { it.uid == userId }
            ?: return@withContext SendPermission.Denied(SendDeniedReason.NOT_A_PARTICIPANT)

        // Rule: channel — only admins/owners can post
        if (conversation.conversationType == ConversationType.CHANNEL) {
            val role = self.toRole()
            if (role != ParticipantRole.ADMIN && role != ParticipantRole.OWNER) {
                return@withContext SendPermission.Denied(SendDeniedReason.CHANNEL_ADMIN_ONLY)
            }
        }

        // Rule: private chat — check block lists on both sides
        if (conversation.conversationType == ConversationType.PRIVATE) {
            val other = conversation.participants.firstOrNull { it.uid != userId }
            if (other != null) {
                if (userId in other.blocked) {
                    return@withContext SendPermission.Denied(SendDeniedReason.BLOCKED_BY_TARGET)
                }
                if (other.uid in self.blocked) {
                    return@withContext SendPermission.Denied(SendDeniedReason.BLOCKED_TARGET)
                }
            }
        }

        SendPermission.Allowed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 4 — SEND MESSAGE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a message and atomically updates the Conversation document with:
     *  - `message_count`    incremented by 1
     *  - `subscriber_count` refreshed to the actual current participant count
     *  - `last_message`     lightweight preview (id, sender, content snippet, type, timestamp)
     *  - `updated_at`       set to now
     *
     * Performs a permission check before writing. Fails fast if denied.
     *
     * Usage:
     *   val result = repository.sendMessage(
     *       conversationId = convId,
     *       senderId = currentUserId,
     *       content = "Hello!",
     *       type = MessageType.TEXT
     *   )
     */
    suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        content: String,
        type: MessageType = MessageType.TEXT,
        replyToId: String? = null,
        attachments: List<Attachment> = emptyList()
    ): Result<Message> = withContext(Dispatchers.IO) {
        try {
            // Step 1 — Permission gate
            val permission = isAllowedToSendMessage(conversationId, senderId)
            if (permission is SendPermission.Denied) {
                return@withContext Result.failure(
                    Exception("Send denied: ${permission.reason.name}")
                )
            }

            // Step 2 — Fetch conversation to get current participant count
            val convSnapshot = conversationCol().document(conversationId).get().await()
            val conversation = convSnapshot.toObject(Conversation::class.java)
                ?: return@withContext Result.failure(Exception("Conversation not found"))

            val now = Timestamp.now()

            // Step 3 — Prepare message document reference
            val messagesCol = messagesSubCol(conversationId)
            val msgDocRef   = messagesCol.document()

            val outgoing = Message(
                messageId         = msgDocRef.id,
                conversationId    = conversationId,
                senderId          = senderId,
                content           = content.trim(),
                timestamp         = now,
                type              = type.name.lowercase(Locale.getDefault()),
                status            = MessageStatus.SENDING.name.lowercase(Locale.getDefault()),
                replyTo           = replyToId,
                attachments       = attachments,
                searchableContent = content.trim().lowercase(Locale.getDefault())
            )

            // Step 4 — Build LastMessagePreview for the Conversation doc
            val preview = LastMessagePreview(
                messageId   = outgoing.messageId,
                senderId    = senderId,
                content     = content.trim().take(100),
                type        = outgoing.type,
                timestamp   = now,
                unreadCount = 0   // updated per-participant via syncParticipantReadCount
            )

            // Step 5 — Atomic batch:
            //   a) Write the message to messages/{convId}/messages/{msgId}
            //   b) Update conversations/{convId} with counter + preview
            val batch = firestore.batch()

            batch.set(msgDocRef, outgoing)

            batch.update(
                conversationCol().document(conversationId),
                mapOf(
                    "message_count"    to FieldValue.increment(1),
                    // Refresh to actual current count — guards against drift
                    "subscriber_count" to conversation.participants.size.toLong(),
                    "last_message"     to preview,
                    "updated_at"       to now
                )
            )

            batch.commit().await()

            // Step 6 — Promote status to SENT now that Firestore confirmed the write
            val sentStatus = MessageStatus.SENT.name.lowercase(Locale.getDefault())
            msgDocRef.update("status", sentStatus).await()

            Result.success(outgoing.copy(status = sentStatus))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 5 — MESSAGE UPDATES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Edits a message's content. Only the original sender should call this.
     * Updates `content`, `searchable_content`, and `edited_at`.
     */
    suspend fun updateMessage(
        conversationId: String,
        messageId: String,
        newContent: String
    ): Result<Unit> = messageManager.updateMessageContent(
        conversationId = conversationId,
        messageId      = messageId,
        newContent     = newContent
    )

    /**
     * Unsends a message — either for everyone (ALL) or just for the sender (ONLY_ME).
     */
    suspend fun unsendMessage(
        conversationId: String,
        messageId: String,
        forEveryone: Boolean
    ): Result<Unit> = messageManager.unsendMessage(
        conversationId = conversationId,
        messageId      = messageId,
        status         = if (forEveryone) UnsentStatus.ALL else UnsentStatus.ONLY_ME
    )

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 6 — REAL-TIME MESSAGE STREAMS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits fine-grained [MessageEvent]s (ADDED / MODIFIED / REMOVED).
     * Use this to patch a local message list in-place.
     */
    fun observeMessageEvents(
        conversationId: String,
        windowSize: Int = 30
    ): Flow<MessageEvent> = messageManager.observeMessageEvents(conversationId, windowSize)

    /**
     * Emits the full live message list on every Firestore change.
     * Simplest option for small windows.
     */
    fun observeLiveMessages(
        conversationId: String,
        windowSize: Int = 30
    ): Flow<List<Message>> = messageManager.observeLiveMessages(conversationId, windowSize)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 7 — PAGED MESSAGE HISTORY
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns paginated older messages (scroll-up history).
     * Call `.cachedIn(viewModelScope)` on the result.
     */
    fun getMessageHistory(
        conversationId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Message>> = messageManager.getMessageHistory(conversationId, pageSize)

    /**
     * Returns paged prefix-search results within a conversation.
     * Pair with `.debounce(300).flatMapLatest { }` in your ViewModel.
     */
    fun searchMessages(
        conversationId: String,
        query: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Message>> = messageManager.searchMessagesPaged(conversationId, query, pageSize)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 8 — PAGED CONVERSATION LIST & SEARCH
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a paged list of all conversations the user belongs to,
     * ordered by most recently updated first.
     */
    fun getConversations(
        userId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Conversation>> =
        conversationManager.getConversationPagingFlow(userId, pageSize)

    /**
     * Returns a paged prefix-search on conversation titles.
     * Requires composite Firestore index: participants_ids (Array) + title (ASC).
     */
    fun searchConversations(
        userId: String,
        query: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Conversation>> =
        conversationManager.searchConversationsPagingFlow(userId, query, pageSize)

    /**
     * Returns paged conversations filtered by type (PRIVATE / GROUP / CHANNEL).
     * Requires composite Firestore index: participants_ids (Array) + type + updated_at.
     */
    fun getConversationsByType(
        userId: String,
        type: ConversationType,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            ConversationByTypePagingSource(firestore, userId, type)
        }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 9 — REAL-TIME CONVERSATION STREAM
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Observes a single conversation document in real time.
     * Use this to keep participant count, last message, and settings live.
     *
     * Usage (ViewModel):
     *   val conversation = repository
     *       .observeConversation(conversationId)
     *       .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
     */
    fun observeConversation(conversationId: String): Flow<Conversation?> = callbackFlow {
        val registration = conversationCol()
            .document(conversationId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot.toObject(Conversation::class.java))
            }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 10 — PARTICIPANT LISTS (paged)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all participants (any role), paged.
     */
    fun getParticipants(
        conversationId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            ParticipantPagingSource(firestore, conversationId, roleFilter = null)
        }
    ).flow

    /**
     * Returns only ADMIN and OWNER participants, paged.
     */
    fun getAdmins(
        conversationId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            // Admins are rare — we fetch both ADMIN and OWNER then union client-side
            AdminsPagingSource(firestore, conversationId)
        }
    ).flow

    /**
     * Returns only MEMBER participants, paged.
     */
    fun getMembers(
        conversationId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            ParticipantPagingSource(firestore, conversationId, roleFilter = ParticipantRole.MEMBER)
        }
    ).flow

    /**
     * One-shot fetch of all admins + owner as a plain list.
     * Useful for permission checks that need the full admin list without paging.
     */
    suspend fun fetchAdminList(conversationId: String): Result<List<Participant>> =
        withContext(Dispatchers.IO) {
            try {
                val snapshot = conversationCol().document(conversationId).get().await()
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: return@withContext Result.failure(Exception("Conversation not found"))
                val admins = conversation.participants.filter {
                    val role = it.toRole()
                    role == ParticipantRole.ADMIN || role == ParticipantRole.OWNER
                }
                Result.success(admins)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * One-shot fetch of all members (MEMBER role only) as a plain list.
     */
    suspend fun fetchMemberList(conversationId: String): Result<List<Participant>> =
        withContext(Dispatchers.IO) {
            try {
                val snapshot = conversationCol().document(conversationId).get().await()
                val conversation = snapshot.toObject(Conversation::class.java)
                    ?: return@withContext Result.failure(Exception("Conversation not found"))
                Result.success(conversation.participants.filter {
                    it.toRole() == ParticipantRole.MEMBER
                })
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * One-shot fetch of a single participant's details.
     */
    suspend fun fetchParticipant(
        conversationId: String,
        userId: String
    ): Result<Participant> = withContext(Dispatchers.IO) {
        try {
            val snapshot = conversationCol().document(conversationId).get().await()
            val conversation = snapshot.toObject(Conversation::class.java)
                ?: return@withContext Result.failure(Exception("Conversation not found"))
            val participant = conversation.participants.find { it.uid == userId }
                ?: return@withContext Result.failure(Exception("Participant not found"))
            Result.success(participant)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 11 — PARTICIPANT MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a user to a conversation.
     * For GROUP/CHANNEL with adminApproval = true, only call this after approval.
     */
    suspend fun addParticipant(
        conversationId: String,
        userId: String,
        role: ParticipantRole = ParticipantRole.MEMBER
    ): Result<Unit> = conversationManager.addParticipant(conversationId, userId, role)

    /**
     * Removes a participant (admin action). Decrements subscriber_count automatically.
     */
    suspend fun removeParticipant(
        conversationId: String,
        userId: String
    ): Result<Unit> = conversationManager.removeParticipant(conversationId, userId)

    /**
     * Leaves a conversation. Owners must transfer ownership first.
     */
    suspend fun leaveConversation(
        conversationId: String,
        userId: String
    ): Result<Unit> = conversationManager.leaveConversation(conversationId, userId)

    /**
     * Promotes or demotes a participant's role (OWNER/ADMIN action only).
     * Authorization is the caller's responsibility — check [fetchAdminList] first.
     */
    suspend fun updateParticipantRole(
        conversationId: String,
        userId: String,
        newRole: ParticipantRole
    ): Result<Unit> = conversationManager.updateParticipantRole(conversationId, userId, newRole)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 12 — TYPING INDICATORS
    // ─────────────────────────────────────────────────────────────────────────

    /** Sets or clears the typing indicator for [userId] in [conversationId]. */
    suspend fun setTyping(
        conversationId: String,
        userId: String,
        isTyping: Boolean
    ): Result<Unit> = messageManager.setTyping(conversationId, userId, isTyping)

    /**
     * Observes who is currently typing (excludes [currentUserId], filters stale entries).
     */
    fun observeTyping(
        conversationId: String,
        currentUserId: String
    ): Flow<TypingState> = messageManager.observeTyping(conversationId, currentUserId)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 13 — DELIVERY & READ RECEIPTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Marks all SENT messages from others as DELIVERED.
     * Call once when the chat screen becomes visible.
     */
    suspend fun markAllDelivered(
        conversationId: String,
        currentUserId: String
    ): Result<Unit> = messageManager.markAllDelivered(conversationId, currentUserId)

    /**
     * Marks a single message as READ by [userId] and syncs the conversation
     * read cursor (`last_message_count`) for accurate unread badge counts.
     */
    suspend fun markMessageRead(
        conversationId: String,
        messageId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        messageManager.markMessageRead(conversationId, messageId, userId)
        conversationManager.syncParticipantReadCount(conversationId, userId)
        Result.success(Unit)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 14 — UNREAD COUNTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the current unread message count for [userId] in [conversationId].
     */
    suspend fun getUnreadCount(
        conversationId: String,
        userId: String
    ): Result<Int> = conversationManager.getUnreadCount(conversationId, userId)

    /**
     * Resets the unread count for [userId] to zero (e.g. on chat screen open).
     * Returns the number of messages that were unread before the sync.
     */
    suspend fun syncReadPosition(
        conversationId: String,
        userId: String
    ): Result<Int> = conversationManager.syncParticipantReadCount(conversationId, userId)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 15 — CONVERSATION DETAILS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * One-shot fetch of a single conversation document.
     */
    suspend fun getConversation(conversationId: String): Result<Conversation> =
        conversationManager.getConversation(conversationId)

    /**
     * Updates mutable conversation metadata (title, photo, adminApproval).
     */
    suspend fun updateConversationDetails(
        conversationId: String,
        title: String? = null,
        photoUrl: String? = null,
        adminApproval: Boolean? = null
    ): Result<Unit> = conversationManager.updateConversationDetails(
        conversationId = conversationId,
        title          = title,
        photoUrl       = photoUrl,
        adminApproval  = adminApproval
    )

    /**
     * Deletes the conversation document.
     * The messages sub-collection must be cleaned up via a Cloud Function
     * (Firestore does not auto-delete nested collections).
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit> =
        conversationManager.deleteConversation(conversationId)

    /**
     * Updates the `last_seen` timestamp for [userId] in this conversation.
     * Call when the user opens or closes the chat screen.
     */
    suspend fun updateLastSeen(
        conversationId: String,
        userId: String
    ): Result<Unit> = conversationManager.updateLastSeen(conversationId, userId)
}

// ═════════════════════════════════════════════════════════════════════════════
// SUPPLEMENTARY PAGING SOURCES
// ═════════════════════════════════════════════════════════════════════════════

/**
 * PagingSource that returns ADMIN + OWNER participants together.
 * Fetches the document once and pages the filtered list client-side.
 */
private class AdminsPagingSource(
    private val firestore: FirebaseFirestore,
    private val conversationId: String
) : PagingSource<Int, Participant>() {

    override fun getRefreshKey(state: PagingState<Int, Participant>): Int? =
        state.anchorPosition

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Participant> {
        return try {
            val snapshot = firestore.collection("conversations")
                .document(conversationId)
                .get()
                .await()

            val conversation = snapshot.toObject(Conversation::class.java)
                ?: return LoadResult.Error(Exception("Conversation not found"))

            val admins = conversation.participants.filter {
                val role = it.toRole()
                role == ParticipantRole.ADMIN || role == ParticipantRole.OWNER
            }

            val pageStart = params.key ?: 0
            val pageEnd   = minOf(pageStart + params.loadSize, admins.size)

            LoadResult.Page(
                data    = admins.subList(pageStart, pageEnd),
                prevKey = if (pageStart == 0) null else pageStart - params.loadSize,
                nextKey = if (pageEnd >= admins.size) null else pageEnd
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}

/**
 * PagingSource for conversations filtered by [ConversationType].
 * Requires composite Firestore index: participants_ids (Array) + type + updated_at.
 */
private class ConversationByTypePagingSource(
    private val firestore: FirebaseFirestore,
    private val userId: String,
    private val type: ConversationType
) : PagingSource<DocumentSnapshot, Conversation>() {

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Conversation>): DocumentSnapshot? = null

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, Conversation> {
        return try {
            var query: Query = firestore.collection("conversations")
                .whereArrayContains("participants_ids", userId)
                .whereEqualTo("type", type.name.lowercase(Locale.getDefault()))
                .orderBy("updated_at", Query.Direction.DESCENDING)
                .limit(params.loadSize.toLong())

            params.key?.let { query = query.startAfter(it) }

            val snapshot = query.get().await()
            val conversations = snapshot.documents
                .mapNotNull { it.toObject(Conversation::class.java) }

            LoadResult.Page(
                data    = conversations,
                prevKey = null,
                nextKey = if (snapshot.documents.size < params.loadSize) null
                else snapshot.documents.lastOrNull()
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }
}