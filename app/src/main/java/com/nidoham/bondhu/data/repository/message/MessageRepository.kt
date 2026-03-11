package com.nidoham.bondhu.data.repository.message

import android.nidoham.server.domain.Participant
import android.nidoham.server.domain.ParticipantRole
import android.nidoham.server.domain.ParticipantType
import android.nidoham.server.repository.ParticipantManager
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.nidoham.server.data.repository.ConversationManager
import org.nidoham.server.data.repository.MessageEvent
import org.nidoham.server.data.repository.MessageManager
import org.nidoham.server.data.repository.TypingState
import org.nidoham.server.domain.model.Attachment
import org.nidoham.server.domain.model.Conversation
import org.nidoham.server.domain.model.LastMessagePreview
import org.nidoham.server.domain.model.Message
import org.nidoham.server.domain.model.MessageStatus
import org.nidoham.server.domain.model.MessageType
import org.nidoham.server.domain.model.UnsentStatus
import java.util.Locale

// ═════════════════════════════════════════════════════════════════════════════
// RESULT WRAPPERS
// ═════════════════════════════════════════════════════════════════════════════

/** Reason why a user is not allowed to send a message. */
enum class SendDeniedReason {
    NOT_A_PARTICIPANT,
    CHANNEL_ADMIN_ONLY,
    CONVERSATION_NOT_FOUND
    // NOTE: BLOCKED_BY_TARGET, BLOCKED_TARGET, and MUTED have been removed.
    // The Participant domain model does not carry a blocked or muted field.
    // Block-list enforcement should be implemented in a dedicated UserRelationshipRepository
    // that maintains block state independently of conversation membership.
}

sealed class SendPermission {
    object Allowed : SendPermission()
    data class Denied(val reason: SendDeniedReason) : SendPermission()
}

// ═════════════════════════════════════════════════════════════════════════════
// MESSAGE REPOSITORY
// Single entry-point for all chat features.
// Orchestrates MessageManager (messages), ConversationManager (conversations),
// and ParticipantRepository (participant subcollection).
// ═════════════════════════════════════════════════════════════════════════════

@javax.inject.Singleton
class MessageRepository @javax.inject.Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messageManager: MessageManager,
    private val conversationManager: ConversationManager,
    // FIX: injected so participant subcollection operations do not go through
    //      the Conversation root document, which no longer embeds participants.
    private val participantRepository: ParticipantManager
) {

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
        private const val COL_CONVERSATIONS = "conversations"
        private const val COL_MESSAGES      = "messages"
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
     * For [ParticipantType.PERSONAL], returns the existing 1-on-1 conversation
     * if one already exists. For GROUP and CHANNEL, always creates a new document.
     *
     * FIX: parameter type changed from ConversationType (non-existent) to ParticipantType.
     */
    suspend fun createConversation(
        creatorId: String,
        title: String,
        type: ParticipantType,
        initialParticipantIds: List<String> = emptyList(),
        photoUrl: String? = null
    ): Result<Conversation> = withContext(Dispatchers.IO) {
        if (type == ParticipantType.PERSONAL) {
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
     * Resolves the conversation ID for a PERSONAL chat between [userId] and
     * [targetUserId]. Returns null if none exists yet, or for GROUP/CHANNEL types.
     *
     * FIX: parameter type changed from ConversationType to ParticipantType.
     */
    suspend fun resolveConversationId(
        type: ParticipantType,
        userId: String,
        targetUserId: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            when (type) {
                ParticipantType.PERSONAL -> {
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
     *   1. The user must be a participant (verified via the participants subcollection).
     *   2. CHANNEL conversations permit only ADMIN or OWNER to post.
     *
     * FIX: participant lookup now uses [ParticipantRepository.getParticipant]
     *      rather than the removed embedded list. Block-list checks have been
     *      removed as the Participant domain model carries no such field.
     */
    suspend fun isAllowedToSendMessage(
        conversationId: String,
        userId: String
    ): SendPermission = withContext(Dispatchers.IO) {
        val conversation = conversationManager.getConversation(conversationId).getOrNull()
            ?: return@withContext SendPermission.Denied(SendDeniedReason.CONVERSATION_NOT_FOUND)

        val participant = participantRepository
            .getParticipant(conversationId, userId)
            .getOrNull()
            ?: return@withContext SendPermission.Denied(SendDeniedReason.NOT_A_PARTICIPANT)

        // FIX: conversationType now returns ParticipantType; compared accordingly.
        if (conversation.conversationType == ParticipantType.CHANNEL) {
            val role = participant.participantRole   // FIX: toRole() replaced by participantRole
            if (role != ParticipantRole.ADMIN && role != ParticipantRole.OWNER) {
                return@withContext SendPermission.Denied(SendDeniedReason.CHANNEL_ADMIN_ONLY)
            }
        }

        SendPermission.Allowed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 4 — SEND MESSAGE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a message and atomically updates the Conversation document with
     * an incremented message_count, a LastMessagePreview, and updated_at.
     *
     * FIX: subscriber_count refresh now reads conversation.subscriberCount
     *      rather than conversation.participants.size (embedded list removed).
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
            val permission = isAllowedToSendMessage(conversationId, senderId)
            if (permission is SendPermission.Denied) {
                return@withContext Result.failure(
                    Exception("Send denied: ${permission.reason.name}")
                )
            }

            val conversation = conversationCol().document(conversationId).get().await()
                .toObject(Conversation::class.java)
                ?: return@withContext Result.failure(Exception("Conversation not found"))

            val now         = Timestamp.now()
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

            val preview = LastMessagePreview(
                messageId   = outgoing.messageId,
                senderId    = senderId,
                content     = content.trim().take(100),
                type        = outgoing.type,
                timestamp   = now,
                unreadCount = 0
            )

            val batch = firestore.batch()
            batch.set(msgDocRef, outgoing)
            batch.update(
                conversationCol().document(conversationId),
                mapOf(
                    "message_count"    to FieldValue.increment(1),
                    // FIX: use subscriberCount from the document rather than
                    //      the removed conversation.participants.size.
                    "subscriber_count" to conversation.subscriberCount,
                    "last_message"     to preview,
                    "updated_at"       to now
                )
            )
            batch.commit().await()

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

    /** Edits a message's content. Only the original sender should call this. */
    suspend fun updateMessage(
        conversationId: String,
        messageId: String,
        newContent: String
    ): Result<Unit> = messageManager.updateMessageContent(
        conversationId = conversationId,
        messageId      = messageId,
        newContent     = newContent
    )

    /** Unsends a message for everyone or just for the sender. */
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

    /** Emits fine-grained [MessageEvent]s (ADDED / MODIFIED / REMOVED). */
    fun observeMessageEvents(
        conversationId: String,
        windowSize: Int = 30
    ): Flow<MessageEvent> = messageManager.observeMessageEvents(conversationId, windowSize)

    /** Emits the full live message list on every Firestore change. */
    fun observeLiveMessages(
        conversationId: String,
        windowSize: Int = 30
    ): Flow<List<Message>> = messageManager.observeLiveMessages(conversationId, windowSize)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 7 — PAGED MESSAGE HISTORY
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns paginated older messages for scroll-up history. */
    fun getMessageHistory(
        conversationId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Message>> = messageManager.getMessageHistory(conversationId, pageSize)

    /** Returns paged prefix-search results within a conversation. */
    fun searchMessages(
        conversationId: String,
        query: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Message>> =
        messageManager.searchMessagesPaged(conversationId, query, pageSize)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 8 — PAGED CONVERSATION LIST & SEARCH
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a paged list of conversations the user belongs to, most recent first. */
    fun getConversations(
        userId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Conversation>> =
        conversationManager.getConversationPagingFlow(userId, pageSize)

    /** Returns a paged prefix-search on conversation titles. */
    fun searchConversations(
        userId: String,
        query: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Conversation>> =
        conversationManager.searchConversationsPagingFlow(userId, query, pageSize)

    /**
     * Returns paged conversations filtered by [ParticipantType].
     *
     * FIX: ConversationType replaced with ParticipantType. The underlying
     *      PagingSource uses the collection-group approach (no participants_ids array).
     */
    fun getConversationsByType(
        userId: String,
        type: ParticipantType,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = pageSize.coerceAtMost(30),
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            ConversationByTypePagingSource(firestore, userId, type)
        }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 9 — REAL-TIME CONVERSATION STREAM
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Observes a single conversation document in real time.
     * Use this to keep subscriber_count, last_message, and settings live.
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
     * Returns all participants (any role), paged via the participants subcollection.
     *
     * FIX: removed ParticipantPagingSource that read from the embedded array.
     *      Delegates to ParticipantRepository which pages the subcollection directly.
     */
    fun getParticipants(
        conversationId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Participant>> =
        participantRepository.getParticipantsPaged(conversationId, pageSize)

    /**
     * Returns only ADMIN and OWNER participants, paged.
     *
     * FIX: removed AdminsPagingSource that read from the embedded array.
     *      Delegates to ParticipantRepository.
     */
    fun getAdmins(
        conversationId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Participant>> =
        participantRepository.getParticipantsByRolePaged(
            conversationId, ParticipantRole.ADMIN, pageSize
        )

    /**
     * Returns only MEMBER participants, paged.
     *
     * FIX: removed ParticipantPagingSource that read from the embedded array.
     */
    fun getMembers(
        conversationId: String,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Flow<PagingData<Participant>> =
        participantRepository.getParticipantsByRolePaged(
            conversationId, ParticipantRole.MEMBER, pageSize
        )

    /**
     * One-shot fetch of all admins and the owner as a plain list.
     *
     * FIX: removed embedded-list read; delegates to ParticipantRepository.getAdmins.
     */
    suspend fun fetchAdminList(conversationId: String): Result<List<Participant>> =
        participantRepository.getAdmins(conversationId)

    /**
     * One-shot fetch of all members (MEMBER role only) as a plain list.
     *
     * FIX: ParticipantRepository does not expose a dedicated getMembers one-shot,
     *      so the full participant list is fetched and filtered client-side.
     *      For large channels, add a getParticipantsByRole one-shot to ParticipantRepository.
     */
    suspend fun fetchMemberList(conversationId: String): Result<List<Participant>> =
        withContext(Dispatchers.IO) {
            participantRepository.getParticipants(conversationId).map { list ->
                list.filter { it.participantRole == ParticipantRole.MEMBER }
            }
        }

    /**
     * One-shot fetch of a single participant's details.
     *
     * FIX: delegates to ParticipantRepository rather than scanning the embedded list.
     */
    suspend fun fetchParticipant(
        conversationId: String,
        userId: String
    ): Result<Participant> = withContext(Dispatchers.IO) {
        val result = participantRepository.getParticipant(conversationId, userId)
        result.mapCatching { it ?: throw Exception("Participant not found") }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 11 — PARTICIPANT MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a user to a conversation.
     *
     * FIX: ConversationManager.addParticipant now requires a ParticipantType argument.
     */
    suspend fun addParticipant(
        conversationId: String,
        userId: String,
        type: ParticipantType,
        role: ParticipantRole = ParticipantRole.MEMBER
    ): Result<Unit> = conversationManager.addParticipant(conversationId, userId, type, role)

    /** Removes a participant (admin action). Decrements subscriber_count automatically. */
    suspend fun removeParticipant(
        conversationId: String,
        userId: String
    ): Result<Unit> = conversationManager.removeParticipant(conversationId, userId)

    /** Leaves a conversation voluntarily. Owners must transfer ownership first. */
    suspend fun leaveConversation(
        conversationId: String,
        userId: String
    ): Result<Unit> = conversationManager.leaveConversation(conversationId, userId)

    /** Promotes or demotes a participant's role. Authorization is the caller's responsibility. */
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

    /** Observes who is currently typing, excluding [currentUserId]. */
    fun observeTyping(
        conversationId: String,
        currentUserId: String
    ): Flow<TypingState> = messageManager.observeTyping(conversationId, currentUserId)

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 13 — DELIVERY & READ RECEIPTS
    // ─────────────────────────────────────────────────────────────────────────

    /** Marks all SENT messages from others as DELIVERED. Call when the chat screen opens. */
    suspend fun markAllDelivered(
        conversationId: String,
        currentUserId: String
    ): Result<Unit> = messageManager.markAllDelivered(conversationId, currentUserId)

    /**
     * Marks a single message as READ by [userId].
     *
     * FIX: removed conversationManager.syncParticipantReadCount call — that method
     *      depended on lastMessageCount embedded in the participant list, which no
     *      longer exists. Unread count tracking should be reimplemented in a
     *      dedicated ReadReceiptRepository that writes to the participant subcollection.
     */
    suspend fun markMessageRead(
        conversationId: String,
        messageId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        messageManager.markMessageRead(conversationId, messageId, userId)
    }

    // NOTE: getUnreadCount, syncReadPosition, and updateLastSeen have been removed.
    // All three relied on lastMessageCount and lastSeen fields embedded inside
    // the participant array, which was eliminated. To restore this functionality,
    // add lastMessageCount and lastSeen fields to the Participant subcollection
    // document and implement the corresponding methods in ParticipantRepository.

    // ─────────────────────────────────────────────────────────────────────────
    // SECTION 14 — CONVERSATION DETAILS
    // ─────────────────────────────────────────────────────────────────────────

    /** One-shot fetch of a single conversation document. */
    suspend fun getConversation(conversationId: String): Result<Conversation> =
        conversationManager.getConversation(conversationId)

    /** Updates mutable conversation metadata (title, photo, adminApproval). */
    suspend fun updateConversationDetails(
        conversationId: String,
        title: String?          = null,
        photoUrl: String?       = null,
        adminApproval: Boolean? = null
    ): Result<Unit> = conversationManager.updateConversationDetails(
        conversationId = conversationId,
        title          = title,
        photoUrl       = photoUrl,
        adminApproval  = adminApproval
    )

    /**
     * Deletes all participant subcollection documents and then the conversation root.
     * Message subcollections must be cleaned up via a Cloud Function.
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit> =
        conversationManager.deleteConversation(conversationId)
}

// ═════════════════════════════════════════════════════════════════════════════
// SUPPLEMENTARY PAGING SOURCE
// ═════════════════════════════════════════════════════════════════════════════

/**
 * PagingSource for conversations filtered by [ParticipantType].
 *
 * FIX: whereArrayContains("participants_ids", ...) removed — that array field
 *      no longer exists on the Conversation document. Membership is resolved
 *      via the participants collection group using the same two-step approach
 *      as ConversationManager's main paging source.
 *
 *      Step 1 — page participant entries where uid == userId and type == type.value.
 *      Step 2 — fetch the corresponding Conversation documents via whereIn.
 *
 *      Page size must not exceed 30 due to the Firestore whereIn limit.
 */
private class ConversationByTypePagingSource(
    private val firestore: FirebaseFirestore,
    private val userId: String,
    private val type: ParticipantType
) : PagingSource<DocumentSnapshot, Conversation>() {

    override fun getRefreshKey(
        state: PagingState<DocumentSnapshot, Conversation>
    ): DocumentSnapshot? = null

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, Conversation> = try {
        // Step 1: page participant subcollection entries for this user + type
        var participantQuery = firestore.collectionGroup("participants")
            .whereEqualTo("uid", userId)
            .whereEqualTo("type", type.value)
            .orderBy("joined_at", Query.Direction.DESCENDING)
            .limit(params.loadSize.toLong())

        params.key?.let { participantQuery = participantQuery.startAfter(it) }

        val participantSnap = participantQuery.get().await()

        // Step 2: extract conversationIds from subcollection path
        val conversationIds = participantSnap.documents.mapNotNull { doc ->
            doc.reference.parent.parent?.id
        }

        if (conversationIds.isEmpty()) {
            return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        }

        // Step 3: fetch Conversation documents
        val conversations = firestore.collection("conversations")
            .whereIn(FieldPath.documentId(), conversationIds)
            .get().await()
            .toObjects(Conversation::class.java)
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