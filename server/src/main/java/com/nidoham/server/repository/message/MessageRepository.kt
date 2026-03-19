package com.nidoham.server.repository.message

import androidx.paging.PagingData
import com.google.firebase.database.DatabaseError
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.message.Message
import com.nidoham.server.domain.message.MessagePreview
import com.nidoham.server.domain.participant.Participant
import com.nidoham.server.manager.ConversationFilter
import com.nidoham.server.manager.ConversationManager
import com.nidoham.server.manager.MessageFilter
import com.nidoham.server.manager.MessageManager
import com.nidoham.server.manager.ParticipantFilter
import com.nidoham.server.manager.ParticipantManager
import com.nidoham.server.manager.TypingManager
import com.nidoham.server.manager.TypingState
import com.nidoham.server.util.MessageStatus
import com.nidoham.server.util.ParticipantRole
import com.nidoham.server.util.ParticipantType
import kotlinx.coroutines.flow.Flow

/**
 * The single entry point for all messaging, conversation, participant, and
 * typing operations within the application.
 *
 * [MessageRepository] acts as a facade over four dedicated managers:
 *   - [ConversationManager]  — conversation lifecycle and real-time observation.
 *   - [MessageManager]       — message send, edit, delete, and pagination.
 *   - [ParticipantManager]   — participant membership, roles, and filtered queries.
 *   - [TypingManager]        — typing indicator writes and live state observation.
 *
 * @param conversationManager Injectable [ConversationManager] instance.
 * @param messageManager      Injectable [MessageManager] instance.
 * @param participantManager  Injectable [ParticipantManager] instance.
 * @param typingManager       Injectable [TypingManager] instance.
 */
class MessageRepository(
    private val conversationManager: ConversationManager = ConversationManager(),
    private val messageManager: MessageManager = MessageManager(),
    private val participantManager: ParticipantManager = ParticipantManager(),
    private val typingManager: TypingManager = TypingManager()
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation — Write
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun createConversation(conversation: Conversation): Result<String> =
        conversationManager.createConversation(conversation)

    suspend fun updateConversation(
        conversationId: String,
        fields: Map<String, Any?>
    ): Result<Unit> = conversationManager.updateConversation(conversationId, fields)

    suspend fun updateLastMessage(
        conversationId: String,
        preview: MessagePreview
    ): Result<Unit> = conversationManager.updateLastMessage(conversationId, preview)

    suspend fun incrementMessageCount(conversationId: String): Result<Unit> =
        conversationManager.incrementMessageCount(conversationId)

    suspend fun decrementMessageCount(conversationId: String): Result<Unit> =
        conversationManager.decrementMessageCount(conversationId)

    suspend fun incrementSubscriberCount(conversationId: String): Result<Unit> =
        conversationManager.incrementSubscriberCount(conversationId)

    suspend fun decrementSubscriberCount(conversationId: String): Result<Unit> =
        conversationManager.decrementSubscriberCount(conversationId)

    suspend fun deleteConversation(conversationId: String): Result<Unit> =
        conversationManager.deleteConversation(conversationId)

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation — Read
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun fetchConversation(conversationId: String): Result<Conversation?> =
        conversationManager.fetchConversation(conversationId)

    suspend fun fetchConversationsForUser(
        userId: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Conversation>> =
        conversationManager.fetchConversationsForUser(userId, type)

    suspend fun fetchCurrentUserConversations(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Conversation>> =
        conversationManager.fetchCurrentUserConversations(type)

    suspend fun fetchConversationsFiltered(
        filter: ConversationFilter
    ): Result<List<Conversation>> =
        conversationManager.fetchConversationsFiltered(filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation — Real-Time
    // ─────────────────────────────────────────────────────────────────────────

    fun observeConversation(conversationId: String): Flow<Conversation?> =
        conversationManager.observeConversation(conversationId)

    fun observeConversations(
        filter: ConversationFilter? = null
    ): Flow<List<Conversation>> =
        conversationManager.observeConversations(filter)

    fun observeCurrentUserConversationIds(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Flow<List<String>> =
        conversationManager.observeCurrentUserConversationIds(type)

    fun observeCurrentUserConversations(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Flow<List<Conversation>> =
        conversationManager.observeCurrentUserConversations(type)

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation — Paging
    // ─────────────────────────────────────────────────────────────────────────

    fun fetchConversationsPaged(pageSize: Int = 20): Flow<PagingData<Conversation>> =
        conversationManager.fetchConversationsPaged(pageSize)

    fun fetchConversationsFilteredPaged(
        filter: ConversationFilter,
        pageSize: Int = 20
    ): Flow<PagingData<Conversation>> =
        conversationManager.fetchConversationsFilteredPaged(filter, pageSize)

    // ─────────────────────────────────────────────────────────────────────────
    // Message — Write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a new message validated against the currently authenticated user.
     * Use this for standard user-to-user messages.
     */
    suspend fun sendMessage(
        conversationId: String,
        message: Message
    ): Result<String> = messageManager.sendMessage(conversationId, message)

    /**
     * Writes a message WITHOUT validating the sender ID against the current user.
     *
     * Use this for system-generated messages, notifications, or AI responses
     * where the sender is a bot ID, not the currently authenticated user.
     *
     * @param conversationId The parent conversation ID.
     * @param message        The [Message] to persist.
     * @return The message document ID on success.
     */
    suspend fun addMessage(
        conversationId: String,
        message: Message
    ): Result<String> = messageManager.addMessage(conversationId, message)

    /**
     * Sends multiple messages validated against the current user.
     */
    suspend fun sendMessagesBatch(
        conversationId: String,
        messages: List<Message>
    ): Result<List<String>> = messageManager.sendMessagesBatch(conversationId, messages)

    /**
     * Writes multiple messages WITHOUT validating the sender.
     * Useful for injecting multiple system messages at once.
     */
    suspend fun addMessagesBatch(
        conversationId: String,
        messages: List<Message>
    ): Result<List<String>> = messageManager.addMessagesBatch(conversationId, messages)

    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        newContent: String
    ): Result<Unit> = messageManager.editMessage(conversationId, messageId, newContent)

    suspend fun updateMessageStatus(
        conversationId: String,
        messageId: String,
        status: MessageStatus
    ): Result<Unit> = messageManager.updateMessageStatus(conversationId, messageId, status)

    suspend fun updateMessageStatusBatch(
        conversationId: String,
        messageIds: List<String>,
        status: MessageStatus
    ): Result<Unit> =
        messageManager.updateMessageStatusBatch(conversationId, messageIds, status)

    suspend fun deleteMessage(
        conversationId: String,
        messageId: String
    ): Result<Unit> = messageManager.deleteMessage(conversationId, messageId)

    suspend fun deleteMessagesBatch(
        conversationId: String,
        messageIds: List<String>
    ): Result<Unit> = messageManager.deleteMessagesBatch(conversationId, messageIds)

    suspend fun deleteAllMessages(conversationId: String): Result<Unit> =
        messageManager.deleteAllMessages(conversationId)

    // ─────────────────────────────────────────────────────────────────────────
    // Message — Read
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun fetchMessage(
        conversationId: String,
        messageId: String
    ): Result<Message?> = messageManager.fetchMessage(conversationId, messageId)

    suspend fun fetchMessagesByIds(
        conversationId: String,
        messageIds: List<String>
    ): Result<List<Message>> = messageManager.fetchMessagesByIds(conversationId, messageIds)

    suspend fun fetchMessagesFiltered(
        conversationId: String,
        filter: MessageFilter
    ): Result<List<Message>> = messageManager.fetchMessagesFiltered(conversationId, filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Message — Real-Time
    // ─────────────────────────────────────────────────────────────────────────

    fun observeMessage(
        conversationId: String,
        messageId: String
    ): Flow<Message?> = messageManager.observeMessage(conversationId, messageId)

    fun observeMessages(
        conversationId: String,
        filter: MessageFilter? = null
    ): Flow<List<Message>> = messageManager.observeMessages(conversationId, filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Message — Paging
    // ─────────────────────────────────────────────────────────────────────────

    fun fetchMessagesPaged(
        conversationId: String,
        pageSize: Int = 30
    ): Flow<PagingData<Message>> = messageManager.fetchMessagesPaged(conversationId, pageSize)

    fun fetchMessagesFilteredPaged(
        conversationId: String,
        filter: MessageFilter,
        pageSize: Int = 30
    ): Flow<PagingData<Message>> =
        messageManager.fetchMessagesFilteredPaged(conversationId, filter, pageSize)

    // ─────────────────────────────────────────────────────────────────────────
    // Participant — Write
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun addParticipant(
        parentId: String,
        uid: String,
        participant: Participant
    ): Result<Unit> = participantManager.addParticipant(parentId, uid, participant)

    suspend fun addParticipantsBatch(
        parentId: String,
        participants: Map<String, Participant>
    ): Result<Unit> = participantManager.addParticipantsBatch(parentId, participants)

    suspend fun updateParticipant(
        parentId: String,
        uid: String,
        fields: Map<String, Any?>
    ): Result<Unit> = participantManager.updateParticipant(parentId, uid, fields)

    suspend fun promoteToAdmin(parentId: String, uid: String): Result<Unit> =
        participantManager.promoteToAdmin(parentId, uid)

    suspend fun demoteToMember(parentId: String, uid: String): Result<Unit> =
        participantManager.demoteToMember(parentId, uid)

    suspend fun removeParticipant(parentId: String, uid: String): Result<Unit> =
        participantManager.removeParticipant(parentId, uid)

    suspend fun removeParticipantsBatch(
        parentId: String,
        uids: List<String>
    ): Result<Unit> = participantManager.removeParticipantsBatch(parentId, uids)

    suspend fun leaveConversation(parentId: String): Result<Unit> =
        participantManager.leaveTarget(parentId)

    // ─────────────────────────────────────────────────────────────────────────
    // Participant — Read
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun fetchParticipant(parentId: String, uid: String): Result<Participant?> =
        participantManager.fetchParticipant(parentId, uid)

    suspend fun isParticipant(parentId: String, uid: String): Result<Boolean> =
        participantManager.isParticipant(parentId, uid)

    suspend fun fetchCurrentUserParticipant(parentId: String): Result<Participant?> =
        participantManager.fetchCurrentUserParticipant(parentId)

    suspend fun fetchJoinedIds(
        uid: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<String>> = participantManager.fetchJoinedIds(uid, type)

    suspend fun fetchSharedParentId(uid1: String, uid2: String): Result<String?> =
        participantManager.fetchSharedParentId(uid1, uid2)

    suspend fun fetchParticipantsFiltered(
        parentId: String,
        filter: ParticipantFilter
    ): Result<List<Participant>> =
        participantManager.fetchParticipantsFiltered(parentId, filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Participant — Real-Time
    // ─────────────────────────────────────────────────────────────────────────

    fun observeParticipant(parentId: String, uid: String): Flow<Participant?> =
        participantManager.observeParticipant(parentId, uid)

    fun observeParticipants(
        parentId: String,
        filter: ParticipantFilter? = null
    ): Flow<List<Participant>> =
        participantManager.observeParticipants(parentId, filter)

    fun observeJoinedIds(
        uid: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Flow<List<String>> = participantManager.observeJoinedIds(uid, type)

    // ─────────────────────────────────────────────────────────────────────────
    // Participant — Paging
    // ─────────────────────────────────────────────────────────────────────────

    fun fetchParticipantsPaged(
        parentId: String,
        pageSize: Int = 20
    ): Flow<PagingData<Participant>> =
        participantManager.fetchParticipantsPaged(parentId, pageSize)

    fun fetchParticipantsByRolePaged(
        parentId: String,
        role: ParticipantRole,
        pageSize: Int = 20
    ): Flow<PagingData<Participant>> =
        participantManager.fetchParticipantsByRolePaged(parentId, role, pageSize)

    fun fetchParticipantsByTypePaged(
        parentId: String,
        type: ParticipantType = ParticipantType.PERSONAL,
        pageSize: Int = 20
    ): Flow<PagingData<Participant>> =
        participantManager.fetchParticipantsByTypePaged(parentId, type, pageSize)

    fun fetchParticipantsFilteredPaged(
        parentId: String,
        filter: ParticipantFilter,
        pageSize: Int = 20
    ): Flow<PagingData<Participant>> =
        participantManager.fetchParticipantsFilteredPaged(parentId, filter, pageSize)

    // ─────────────────────────────────────────────────────────────────────────
    // Search — Conversation
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun searchConversationsByTitle(
        prefix: String,
        filter: ConversationFilter? = null,
        limit: Long = 20
    ): Result<List<Conversation>> =
        conversationManager.searchConversationsByTitle(prefix, filter, limit)

    suspend fun searchConversationsBySubtitle(
        prefix: String,
        limit: Long = 20
    ): Result<List<Conversation>> =
        conversationManager.searchConversationsBySubtitle(prefix, limit)

    suspend fun searchConversationsByCreator(
        creatorId: String,
        type: ParticipantType? = null,
        limit: Long = 20
    ): Result<List<Conversation>> =
        conversationManager.searchConversationsByCreator(creatorId, type, limit)

    fun observeSearchConversationsByTitle(
        prefix: String,
        filter: ConversationFilter? = null,
        limit: Long = 20
    ): Flow<List<Conversation>> =
        conversationManager.observeSearchByTitle(prefix, filter, limit)

    // ─────────────────────────────────────────────────────────────────────────
    // Search — Message
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun searchMessagesByContent(
        conversationId: String,
        prefix: String,
        filter: MessageFilter? = null,
        limit: Long = 20
    ): Result<List<Message>> =
        messageManager.searchMessagesByContent(conversationId, prefix, filter, limit)

    suspend fun searchMessagesBySender(
        conversationId: String,
        senderId: String,
        status: MessageStatus? = null,
        limit: Long = 30
    ): Result<List<Message>> =
        messageManager.searchMessagesBySender(conversationId, senderId, status, limit)

    suspend fun fetchReplies(
        conversationId: String,
        parentMessageId: String,
        limit: Long = 30
    ): Result<List<Message>> =
        messageManager.fetchReplies(conversationId, parentMessageId, limit)

    fun observeReplies(
        conversationId: String,
        parentMessageId: String,
        limit: Long = 30
    ): Flow<List<Message>> =
        messageManager.observeReplies(conversationId, parentMessageId, limit)

    // ─────────────────────────────────────────────────────────────────────────
    // Typing — Write
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun setTyping(
        conversationId: String,
        uid: String,
        typing: Boolean
    ): Result<Unit> = typingManager.setTyping(conversationId, uid, typing)

    suspend fun clearTyping(conversationId: String, targetId: String): Result<Unit> =
        typingManager.clearTyping(conversationId, targetId)

    suspend fun clearTypingForUser(
        conversationId: String,
        userId: String
    ): Result<Unit> = typingManager.clearTypingForUser(conversationId, userId)

    suspend fun clearAllTyping(conversationId: String): Result<Unit> =
        typingManager.clearAllTyping(conversationId)

    // ─────────────────────────────────────────────────────────────────────────
    // Typing — Real-Time & Read
    // ─────────────────────────────────────────────────────────────────────────

    fun observeTyping(
        conversationId: String,
        onError: (DatabaseError) -> Unit = {}
    ): Flow<TypingState> = typingManager.observeTyping(conversationId, onError)

    suspend fun fetchTypingState(conversationId: String): Result<TypingState> =
        typingManager.fetchTypingState(conversationId)
}