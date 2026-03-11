package com.nidoham.server.repository.message

import androidx.paging.PagingData
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.message.Message
import com.nidoham.server.domain.participant.Participant
import com.nidoham.server.manager.ConversationFilter
import com.nidoham.server.manager.ConversationManager
import com.nidoham.server.manager.MessageFilter
import com.nidoham.server.manager.MessageManager
import com.nidoham.server.manager.ParticipantFilter
import com.nidoham.server.manager.ParticipantManager
import com.nidoham.server.manager.TypingManager
import com.nidoham.server.manager.TypingState
import com.nidoham.server.util.ParticipantRole
import com.nidoham.server.util.ParticipantType
import com.nidoham.server.util.MessageStatus
import kotlinx.coroutines.flow.Flow

/**
 * The single entry point for all messaging, conversation, participant, and
 * typing operations within the application.
 *
 * [MessageRepository] acts as a facade over four dedicated managers:
 *
 *  - [ConversationManager]  — conversation lifecycle and real-time observation.
 *  - [MessageManager]       — message send, edit, delete, and pagination.
 *  - [ParticipantManager]   — participant membership, roles, and filtered queries.
 *  - [TypingManager]        — typing indicator writes and live state observation.
 *
 * All public methods delegate directly to the appropriate manager. No business
 * logic is duplicated here; the repository's sole responsibility is to provide
 * a stable, unified API surface that ViewModels and use-case classes depend on,
 * decoupling them from any knowledge of the underlying manager layer.
 *
 * Every write operation returns [Result], giving the caller full control over
 * error handling. Every live operation returns a [Flow], which integrates
 * cleanly with `stateIn` and `collectAsStateWithLifecycle` in the ViewModel.
 *
 * @param conversationManager Injectable [ConversationManager] instance.
 * @param messageManager      Injectable [MessageManager] instance.
 * @param participantManager  Injectable [ParticipantManager] instance.
 * @param typingManager       Injectable [TypingManager] instance.
 */
class MessageRepository(
    private val conversationManager: ConversationManager = ConversationManager(),
    private val messageManager: MessageManager           = MessageManager(),
    private val participantManager: ParticipantManager   = ParticipantManager(),
    private val typingManager: TypingManager             = TypingManager()
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation — Write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new conversation and atomically adds the currently authenticated
     * user as its first participant with the [ParticipantRole.ADMIN] role.
     *
     * @param conversation The [Conversation] object to persist. If [Conversation.id]
     *                     is blank, Firestore will generate the ID automatically.
     * @return The generated conversation ID on success.
     */
    suspend fun createConversation(conversation: Conversation): Result<String> =
        conversationManager.createConversation(conversation)

    /**
     * Updates specific fields on an existing conversation document. The
     * [updated_at] timestamp is stamped automatically.
     *
     * @param conversationId The ID of the conversation to update.
     * @param fields         A map of Firestore field names to their new values.
     */
    suspend fun updateConversation(
        conversationId: String,
        fields: Map<String, Any?>
    ): Result<Unit> = conversationManager.updateConversation(conversationId, fields)

    /**
     * Writes a new last-message preview string to the conversation document.
     * This should be called immediately after a message is successfully sent.
     *
     * @param conversationId The target conversation ID.
     * @param preview        The content string to store as the preview.
     */
    suspend fun updateLastMessage(
        conversationId: String,
        preview: String
    ): Result<Unit> = conversationManager.updateLastMessage(conversationId, preview)

    /**
     * Atomically increments the message count on the given conversation by 1.
     */
    suspend fun incrementMessageCount(conversationId: String): Result<Unit> =
        conversationManager.incrementMessageCount(conversationId)

    /**
     * Atomically decrements the message count on the given conversation by 1.
     */
    suspend fun decrementMessageCount(conversationId: String): Result<Unit> =
        conversationManager.decrementMessageCount(conversationId)

    /**
     * Permanently deletes a conversation document. Call [deleteAllMessages]
     * first if the messages sub-collection should also be removed.
     *
     * @param conversationId The ID of the conversation to delete.
     */
    suspend fun deleteConversation(conversationId: String): Result<Unit> =
        conversationManager.deleteConversation(conversationId)

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation — Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single [Conversation] by its document ID.
     *
     * @return The [Conversation] object, or null if it does not exist.
     */
    suspend fun fetchConversation(conversationId: String): Result<Conversation?> =
        conversationManager.fetchConversation(conversationId)

    /**
     * Fetches all conversations that a specific user has joined, filtered by
     * participant type.
     *
     * @param userId The Firebase UID of the target user.
     * @param type   Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    suspend fun fetchConversationsForUser(
        userId: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Conversation>> =
        conversationManager.fetchConversationsForUser(userId, type)

    /**
     * Fetches all conversations joined by the currently authenticated user.
     *
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    suspend fun fetchCurrentUserConversations(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Conversation>> =
        conversationManager.fetchCurrentUserConversations(type)

    /**
     * Applies a [ConversationFilter] and returns all matching conversations as
     * a plain list.
     *
     * @param filter The [ConversationFilter] describing the desired constraints.
     */
    suspend fun fetchConversationsFiltered(
        filter: ConversationFilter
    ): Result<List<Conversation>> =
        conversationManager.fetchConversationsFiltered(filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation — Real-Time
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits the latest state of a single [Conversation] document in real time.
     * Emits null if the document is deleted.
     *
     * @param conversationId The conversation document ID to observe.
     */
    fun observeConversation(conversationId: String): Flow<Conversation?> =
        conversationManager.observeConversation(conversationId)

    /**
     * Emits an updated list of [Conversation] objects whenever any document
     * matching the optional [ConversationFilter] changes. If no filter is
     * supplied, the entire conversations collection is observed.
     *
     * @param filter Optional [ConversationFilter] to narrow the query.
     */
    fun observeConversations(
        filter: ConversationFilter? = null
    ): Flow<List<Conversation>> =
        conversationManager.observeConversations(filter)

    /**
     * Emits an updated list of conversation IDs whenever the current user's
     * membership in any conversation changes.
     *
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    fun observeCurrentUserConversationIds(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Flow<List<String>> =
        conversationManager.observeCurrentUserConversationIds(type)

    /**
     * Emits a fully hydrated list of [Conversation] objects for every conversation
     * the current user has joined. Updates in real time when membership changes or
     * when any joined conversation document is modified.
     *
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    fun observeCurrentUserConversations(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Flow<List<Conversation>> =
        conversationManager.observeCurrentUserConversations(type)

    // ─────────────────────────────────────────────────────────────────────────
    // Conversation — Paging
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] over the conversations
     * collection, ordered by last-updated descending.
     *
     * @param pageSize Number of documents to load per page.
     */
    fun fetchConversationsPaged(
        pageSize: Int = 20
    ): Flow<PagingData<Conversation>> =
        conversationManager.fetchConversationsPaged(pageSize)

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] filtered by a
     * [ConversationFilter], ordered by last-updated descending.
     *
     * @param filter   The [ConversationFilter] to apply.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchConversationsFilteredPaged(
        filter: ConversationFilter,
        pageSize: Int = 20
    ): Flow<PagingData<Conversation>> =
        conversationManager.fetchConversationsFilteredPaged(filter, pageSize)

    // ─────────────────────────────────────────────────────────────────────────
    // Message — Write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a new message to the given conversation. The sender is validated
     * against the currently authenticated user to prevent spoofing. If
     * [Message.messageId] is blank, a Firestore-generated ID is used.
     *
     * @param conversationId The parent conversation ID.
     * @param message        The [Message] object to persist.
     * @return The message document ID on success.
     */
    suspend fun sendMessage(
        conversationId: String,
        message: Message
    ): Result<String> = messageManager.sendMessage(conversationId, message)

    /**
     * Sends multiple messages to the same conversation in a single batch write.
     *
     * @param conversationId The parent conversation ID.
     * @param messages       The list of [Message] objects to persist.
     * @return The list of generated message IDs in the same order as [messages].
     */
    suspend fun sendMessagesBatch(
        conversationId: String,
        messages: List<Message>
    ): Result<List<String>> = messageManager.sendMessagesBatch(conversationId, messages)

    /**
     * Edits the content of an existing message. Only the original sender may
     * perform this operation. The [edited_at] timestamp is set automatically.
     *
     * @param conversationId The parent conversation ID.
     * @param messageId      The document ID of the message to edit.
     * @param newContent     The replacement content string.
     */
    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        newContent: String
    ): Result<Unit> = messageManager.editMessage(conversationId, messageId, newContent)

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
    ): Result<Unit> = messageManager.updateMessageStatus(conversationId, messageId, status)

    /**
     * Updates the status of multiple messages in a single batch write.
     *
     * @param conversationId The parent conversation ID.
     * @param messageIds     The message document IDs to update.
     * @param status         The new [MessageStatus] to apply to all entries.
     */
    suspend fun updateMessageStatusBatch(
        conversationId: String,
        messageIds: List<String>,
        status: MessageStatus
    ): Result<Unit> =
        messageManager.updateMessageStatusBatch(conversationId, messageIds, status)

    /**
     * Deletes a single message document.
     *
     * @param conversationId The parent conversation ID.
     * @param messageId      The message document ID to delete.
     */
    suspend fun deleteMessage(
        conversationId: String,
        messageId: String
    ): Result<Unit> = messageManager.deleteMessage(conversationId, messageId)

    /**
     * Deletes multiple message documents in a single batch write.
     *
     * @param conversationId The parent conversation ID.
     * @param messageIds     The message document IDs to delete.
     */
    suspend fun deleteMessagesBatch(
        conversationId: String,
        messageIds: List<String>
    ): Result<Unit> = messageManager.deleteMessagesBatch(conversationId, messageIds)

    /**
     * Deletes every message within a conversation, processing documents in
     * chunks of 500 to respect Firestore's per-batch limit. This should be
     * called before [deleteConversation] when a full wipe is required.
     *
     * @param conversationId The parent conversation ID.
     */
    suspend fun deleteAllMessages(conversationId: String): Result<Unit> =
        messageManager.deleteAllMessages(conversationId)

    // ─────────────────────────────────────────────────────────────────────────
    // Message — Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single [Message] by its document ID.
     *
     * @return The [Message] object, or null if it does not exist.
     */
    suspend fun fetchMessage(
        conversationId: String,
        messageId: String
    ): Result<Message?> = messageManager.fetchMessage(conversationId, messageId)

    /**
     * Fetches multiple messages by their document IDs in parallel. Documents
     * that do not exist are silently omitted from the result.
     *
     * @param conversationId The parent conversation ID.
     * @param messageIds     The message document IDs to retrieve.
     */
    suspend fun fetchMessagesByIds(
        conversationId: String,
        messageIds: List<String>
    ): Result<List<Message>> = messageManager.fetchMessagesByIds(conversationId, messageIds)

    /**
     * Applies a [MessageFilter] and returns all matching messages as a plain list.
     *
     * @param conversationId The parent conversation ID.
     * @param filter         The [MessageFilter] describing the desired constraints.
     */
    suspend fun fetchMessagesFiltered(
        conversationId: String,
        filter: MessageFilter
    ): Result<List<Message>> =
        messageManager.fetchMessagesFiltered(conversationId, filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Message — Real-Time
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits the latest state of a single [Message] document in real time.
     * Emits null if the document is deleted.
     *
     * @param conversationId The parent conversation ID.
     * @param messageId      The message document ID to observe.
     */
    fun observeMessage(
        conversationId: String,
        messageId: String
    ): Flow<Message?> = messageManager.observeMessage(conversationId, messageId)

    /**
     * Emits an updated list of [Message] objects whenever the messages
     * sub-collection changes, ordered by timestamp descending. An optional
     * [MessageFilter] narrows the listener query.
     *
     * @param conversationId The parent conversation ID.
     * @param filter         Optional [MessageFilter] to restrict the observed query.
     */
    fun observeMessages(
        conversationId: String,
        filter: MessageFilter? = null
    ): Flow<List<Message>> = messageManager.observeMessages(conversationId, filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Message — Paging
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] over the messages
     * sub-collection, ordered by timestamp descending — the standard pattern
     * for chat UIs.
     *
     * @param conversationId The parent conversation ID.
     * @param pageSize       Number of documents to load per page.
     */
    fun fetchMessagesPaged(
        conversationId: String,
        pageSize: Int = 30
    ): Flow<PagingData<Message>> =
        messageManager.fetchMessagesPaged(conversationId, pageSize)

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] applying a
     * [MessageFilter], allowing filtered message lists to benefit from
     * incremental loading.
     *
     * @param conversationId The parent conversation ID.
     * @param filter         The [MessageFilter] to apply.
     * @param pageSize       Number of documents to load per page.
     */
    fun fetchMessagesFilteredPaged(
        conversationId: String,
        filter: MessageFilter,
        pageSize: Int = 30
    ): Flow<PagingData<Message>> =
        messageManager.fetchMessagesFilteredPaged(conversationId, filter, pageSize)

    // ─────────────────────────────────────────────────────────────────────────
    // Participant — Write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a participant to the given conversation. Both [Participant.uid] and
     * [Participant.id] are validated before the write is issued.
     *
     * @param id          The parent conversation ID.
     * @param userId      The participant's Firebase UID.
     * @param participant The [Participant] object to persist.
     */
    suspend fun addParticipant(
        id: String,
        userId: String,
        participant: Participant
    ): Result<Unit> = participantManager.addParticipant(id, userId, participant)

    /**
     * Adds multiple participants to the same conversation in a single atomic
     * batch write.
     *
     * @param id           The parent conversation ID.
     * @param participants A map of userId to [Participant] entries to persist.
     */
    suspend fun addParticipantsBatch(
        id: String,
        participants: Map<String, Participant>
    ): Result<Unit> = participantManager.addParticipantsBatch(id, participants)

    /**
     * Updates specific fields on an existing participant document.
     *
     * @param id     The parent conversation ID.
     * @param userId The participant's Firebase UID.
     * @param fields A map of Firestore field names to their new values.
     */
    suspend fun updateParticipant(
        id: String,
        userId: String,
        fields: Map<String, Any?>
    ): Result<Unit> = participantManager.updateParticipant(id, userId, fields)

    /**
     * Promotes a participant to the [ParticipantRole.ADMIN] role.
     *
     * @param id     The parent conversation ID.
     * @param userId The participant's Firebase UID.
     */
    suspend fun promoteToAdmin(id: String, userId: String): Result<Unit> =
        participantManager.promoteToAdmin(id, userId)

    /**
     * Demotes a participant back to the [ParticipantRole.MEMBER] role.
     *
     * @param id     The parent conversation ID.
     * @param userId The participant's Firebase UID.
     */
    suspend fun demoteToMember(id: String, userId: String): Result<Unit> =
        participantManager.demoteToMember(id, userId)

    /**
     * Removes a specific participant from the given conversation.
     *
     * @param id     The parent conversation ID.
     * @param userId The participant's Firebase UID.
     */
    suspend fun removeParticipant(id: String, userId: String): Result<Unit> =
        participantManager.removeParticipant(id, userId)

    /**
     * Removes multiple participants from the same conversation in a single
     * batch write.
     *
     * @param id      The parent conversation ID.
     * @param userIds The UIDs of the participants to remove.
     */
    suspend fun removeParticipantsBatch(
        id: String,
        userIds: List<String>
    ): Result<Unit> = participantManager.removeParticipantsBatch(id, userIds)

    /**
     * Removes the currently authenticated user from the given conversation.
     *
     * @param id The parent conversation ID.
     */
    suspend fun leaveConversation(id: String): Result<Unit> =
        participantManager.leaveTarget(id)

    // ─────────────────────────────────────────────────────────────────────────
    // Participant — Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single participant document by conversation ID and user ID.
     *
     * @return The [Participant] object, or null if the document does not exist.
     */
    suspend fun fetchParticipant(id: String, userId: String): Result<Participant?> =
        participantManager.fetchParticipant(id, userId)

    /**
     * Fetches multiple participant documents by their user IDs in a single
     * batch read. Documents that do not exist are silently omitted.
     *
     * @param id      The parent conversation ID.
     * @param userIds The UIDs to retrieve.
     */
    suspend fun fetchParticipantsByIds(
        id: String,
        userIds: List<String>
    ): Result<List<Participant>> =
        participantManager.fetchParticipantsByIds(id, userIds)

    /**
     * Returns true if a participant document exists for the given user in the
     * given conversation.
     *
     * @param id     The parent conversation ID.
     * @param userId The participant's Firebase UID.
     */
    suspend fun isParticipant(id: String, userId: String): Result<Boolean> =
        participantManager.isParticipant(id, userId)

    /**
     * Fetches the participant record for the currently authenticated user within
     * the given conversation.
     *
     * @param id The parent conversation ID.
     */
    suspend fun fetchCurrentUserParticipant(id: String): Result<Participant?> =
        participantManager.fetchCurrentUserParticipant(id)

    /**
     * Resolves the stored [Participant.id] field for a given user within a
     * conversation, filtered by participant type. Defaults to
     * [ParticipantType.PERSONAL].
     *
     * @param userId   The participant's Firebase UID.
     * @param targetId The parent conversation ID.
     * @param type     Participant type filter.
     */
    suspend fun fetchParticipantId(
        userId: String,
        targetId: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<String?> = participantManager.fetchId(userId, targetId, type)

    /**
     * Fetches all parent conversation IDs that a given user has joined,
     * filtered by participant type.
     *
     * @param userId The UID to search across all participant sub-collections.
     * @param type   Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    suspend fun fetchJoinedIds(
        userId: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<String>> = participantManager.fetchJoinedIds(userId, type)

    /**
     * Fetches all participant records for the currently authenticated user,
     * filtered by participant type.
     *
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    suspend fun fetchCurrentUserJoinedList(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Participant>> =
        participantManager.fetchCurrentUserJoinedList(type)

    /**
     * Returns the total number of participants within the given conversation.
     *
     * @param id The parent conversation ID.
     */
    suspend fun fetchParticipantCount(id: String): Result<Int> =
        participantManager.fetchParticipantCount(id)

    /**
     * Returns the number of participants matching a specific role within the
     * given conversation.
     *
     * @param id   The parent conversation ID.
     * @param role The role to count.
     */
    suspend fun fetchParticipantCountByRole(
        id: String,
        role: ParticipantRole
    ): Result<Int> = participantManager.fetchParticipantCountByRole(id, role)

    /**
     * Applies a [ParticipantFilter] and returns all matching participant records
     * as a plain list.
     *
     * @param id     The parent conversation ID.
     * @param filter The [ParticipantFilter] describing the desired constraints.
     */
    suspend fun fetchParticipantsFiltered(
        id: String,
        filter: ParticipantFilter
    ): Result<List<Participant>> =
        participantManager.fetchParticipantsFiltered(id, filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Participant — Real-Time
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits the latest state of a single participant document in real time.
     * Emits null if the document is deleted.
     *
     * @param id     The parent conversation ID.
     * @param userId The participant's Firebase UID.
     */
    fun observeParticipant(id: String, userId: String): Flow<Participant?> =
        participantManager.observeParticipant(id, userId)

    /**
     * Emits an updated list of [Participant] objects whenever the participants
     * sub-collection changes. An optional [ParticipantFilter] narrows the query.
     *
     * @param id     The parent conversation ID.
     * @param filter Optional [ParticipantFilter] to restrict the observed query.
     */
    fun observeParticipants(
        id: String,
        filter: ParticipantFilter? = null
    ): Flow<List<Participant>> =
        participantManager.observeParticipants(id, filter)

    // ─────────────────────────────────────────────────────────────────────────
    // Participant — Paging
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] over the participants
     * sub-collection, ordered by join date ascending.
     *
     * @param id       The parent conversation ID.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchParticipantsPaged(
        id: String,
        pageSize: Int = 20
    ): Flow<PagingData<Participant>> =
        participantManager.fetchParticipantsPaged(id, pageSize)

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] filtered to a specific
     * [ParticipantRole].
     *
     * @param id       The parent conversation ID.
     * @param role     The role to filter by.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchParticipantsByRolePaged(
        id: String,
        role: ParticipantRole,
        pageSize: Int = 20
    ): Flow<PagingData<Participant>> =
        participantManager.fetchParticipantsByRolePaged(id, role, pageSize)

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] filtered to a specific
     * [ParticipantType], defaulting to [ParticipantType.PERSONAL].
     *
     * @param id       The parent conversation ID.
     * @param type     The participant type to filter by.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchParticipantsByTypePaged(
        id: String,
        type: ParticipantType = ParticipantType.PERSONAL,
        pageSize: Int = 20
    ): Flow<PagingData<Participant>> =
        participantManager.fetchParticipantsByTypePaged(id, type, pageSize)

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] applying a
     * [ParticipantFilter] for fully custom participant queries.
     *
     * @param id       The parent conversation ID.
     * @param filter   The [ParticipantFilter] to apply.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchParticipantsFilteredPaged(
        id: String,
        filter: ParticipantFilter,
        pageSize: Int = 20
    ): Flow<PagingData<Participant>> =
        participantManager.fetchParticipantsFilteredPaged(id, filter, pageSize)

    // ─────────────────────────────────────────────────────────────────────────
    // Search — Conversation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches conversations whose [title] field begins with [prefix] using a
     * Firestore lexicographic range query. Results are ordered by title ascending.
     *
     * Firestore does not support native full-text search. This method performs
     * prefix matching only. For case-insensitive matching, store titles in
     * lowercase and supply a lowercased [prefix].
     *
     * An ascending index on `title` is required. An optional [ConversationFilter]
     * may add equality constraints (type, creatorId, translated) without requiring
     * a separate query.
     *
     * @param prefix The title prefix to match. Must not be blank.
     * @param filter Optional [ConversationFilter] for additional equality constraints.
     * @param limit  Maximum number of results to return. Defaults to 20.
     * @return A list of matching [Conversation] objects ordered by title ascending.
     */
    suspend fun searchConversationsByTitle(
        prefix: String,
        filter: ConversationFilter? = null,
        limit: Long = 20
    ): Result<List<Conversation>> =
        conversationManager.searchConversationsByTitle(prefix, filter, limit)

    /**
     * Searches conversations whose [subtitle] field begins with [prefix].
     * Uses the same lexicographic range strategy as [searchConversationsByTitle].
     *
     * Requires an ascending index on `subtitle`.
     *
     * @param prefix The subtitle prefix to match. Must not be blank.
     * @param limit  Maximum number of results to return. Defaults to 20.
     * @return A list of matching [Conversation] objects ordered by subtitle ascending.
     */
    suspend fun searchConversationsBySubtitle(
        prefix: String,
        limit: Long = 20
    ): Result<List<Conversation>> =
        conversationManager.searchConversationsBySubtitle(prefix, limit)

    /**
     * Fetches all conversations created by a specific user, ordered by creation
     * date descending. Optionally filtered by [ParticipantType].
     *
     * @param creatorId The Firebase UID of the creator to filter by.
     * @param type      Optional participant type constraint.
     * @param limit     Maximum number of results to return. Defaults to 20.
     */
    suspend fun searchConversationsByCreator(
        creatorId: String,
        type: ParticipantType? = null,
        limit: Long = 20
    ): Result<List<Conversation>> =
        conversationManager.searchConversationsByCreator(creatorId, type, limit)

    /**
     * Returns a live [Flow] that emits an updated result list whenever Firestore
     * documents matching the given title [prefix] change. Suitable for a search
     * bar that refreshes results as the user types.
     *
     * The underlying listener is removed automatically when the flow is cancelled.
     *
     * @param prefix The title prefix to observe. An empty or blank string emits
     *               an empty list immediately without attaching a listener.
     * @param filter Optional [ConversationFilter] for additional constraints.
     * @param limit  Maximum number of live results to stream. Defaults to 20.
     */
    fun observeSearchConversationsByTitle(
        prefix: String,
        filter: ConversationFilter? = null,
        limit: Long = 20
    ): Flow<List<Conversation>> =
        conversationManager.observeSearchByTitle(prefix, filter, limit)

    // ─────────────────────────────────────────────────────────────────────────
    // Search — Message
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Searches messages within a conversation whose [content] field begins with
     * [prefix]. Results are ordered by content ascending. An optional
     * [MessageFilter] may add sender, type, or status constraints.
     *
     * Requires an ascending index on `content`. For case-insensitive matching,
     * normalise message content to lowercase at write time.
     *
     * @param conversationId The parent conversation ID to search within.
     * @param prefix         The content prefix to match. Must not be blank.
     * @param filter         Optional [MessageFilter] for additional constraints.
     * @param limit          Maximum number of results to return. Defaults to 20.
     * @return A list of matching [Message] objects ordered by content ascending.
     */
    suspend fun searchMessagesByContent(
        conversationId: String,
        prefix: String,
        filter: MessageFilter? = null,
        limit: Long = 20
    ): Result<List<Message>> =
        messageManager.searchMessagesByContent(conversationId, prefix, filter, limit)

    /**
     * Fetches all messages sent by a specific user within the given conversation,
     * ordered by timestamp descending. Optionally filtered by [MessageStatus].
     *
     * @param conversationId The parent conversation ID.
     * @param senderId       The Firebase UID of the sender.
     * @param status         Optional [MessageStatus] to narrow results.
     * @param limit          Maximum number of results to return. Defaults to 30.
     */
    suspend fun searchMessagesBySender(
        conversationId: String,
        senderId: String,
        status: MessageStatus? = null,
        limit: Long = 30
    ): Result<List<Message>> =
        messageManager.searchMessagesBySender(conversationId, senderId, status, limit)

    /**
     * Fetches all messages that are replies to a specific parent message, ordered
     * by timestamp ascending so the thread reads chronologically.
     *
     * @param conversationId  The parent conversation ID.
     * @param parentMessageId The message ID referenced in each reply's [reply_to] field.
     * @param limit           Maximum number of results to return. Defaults to 30.
     */
    suspend fun fetchReplies(
        conversationId: String,
        parentMessageId: String,
        limit: Long = 30
    ): Result<List<Message>> =
        messageManager.fetchReplies(conversationId, parentMessageId, limit)

    /**
     * Returns a live [Flow] that emits an updated list of reply messages whenever
     * the reply thread for [parentMessageId] changes. Results are ordered by
     * timestamp ascending.
     *
     * The underlying listener is removed automatically when the flow is cancelled.
     *
     * @param conversationId  The parent conversation ID.
     * @param parentMessageId The message ID referenced in each reply's [reply_to] field.
     * @param limit           Maximum number of live results to stream. Defaults to 30.
     */
    fun observeReplies(
        conversationId: String,
        parentMessageId: String,
        limit: Long = 30
    ): Flow<List<Message>> =
        messageManager.observeReplies(conversationId, parentMessageId, limit)

    // ─────────────────────────────────────────────────────────────────────────
    // Typing — Write
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Records that the currently authenticated user has started or stopped
     * typing in the given conversation.
     *
     * When [typing] is true, a server timestamp heartbeat is written for the
     * user. When false, the field is deleted. Safe to call repeatedly from a
     * debounced text-change listener.
     *
     * @param conversationId The conversation in which the typing event occurred.
     * @param typing         True if the user is currently typing; false otherwise.
     */
    suspend fun setTyping(
        conversationId: String,
        typing: Boolean
    ): Result<Unit> = typingManager.setTyping(conversationId, typing)

    /**
     * Explicitly clears the typing indicator for the currently authenticated
     * user. Should be called when the user navigates away or the screen is
     * destroyed to prevent ghost indicators.
     *
     * @param conversationId The conversation to clear the indicator for.
     */
    suspend fun clearTyping(conversationId: String): Result<Unit> =
        typingManager.clearTyping(conversationId)

    /**
     * Clears the typing indicator for a specific user, regardless of which user
     * is currently authenticated. Intended for administrative use cases.
     *
     * @param conversationId The conversation to clear the indicator for.
     * @param userId         The UID of the user whose indicator should be removed.
     */
    suspend fun clearTypingForUser(
        conversationId: String,
        userId: String
    ): Result<Unit> = typingManager.clearTypingForUser(conversationId, userId)

    /**
     * Deletes the entire typing document for the given conversation, clearing
     * all active indicators simultaneously. Appropriate when a conversation is
     * archived, closed, or all participants have left.
     *
     * @param conversationId The conversation whose typing document should be deleted.
     */
    suspend fun clearAllTyping(conversationId: String): Result<Unit> =
        typingManager.clearAllTyping(conversationId)

    // ─────────────────────────────────────────────────────────────────────────
    // Typing — Real-Time & Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a [Flow] that emits an updated [TypingState] whenever the set of
     * actively typing users in the given conversation changes. Stale entries
     * older than [TypingManager.TYPING_TIMEOUT_MS] are automatically excluded.
     *
     * The flow degrades gracefully on transient Firestore errors, always emitting
     * a safe empty state rather than propagating an exception to the UI.
     *
     * Recommended ViewModel usage:
     * ```kotlin
     * val typingState = repository
     *     .observeTyping(conversationId)
     *     .stateIn(
     *         scope        = viewModelScope,
     *         started      = SharingStarted.WhileSubscribed(5_000),
     *         initialValue = TypingState(conversationId, emptyList())
     *     )
     * ```
     *
     * @param conversationId The conversation to observe.
     * @param onError        Optional callback for non-fatal Firestore errors.
     */
    fun observeTyping(
        conversationId: String,
        onError: (Exception) -> Unit = {}
    ): Flow<TypingState> = typingManager.observeTyping(conversationId, onError)

    /**
     * Returns a one-shot snapshot of the current [TypingState] without attaching
     * a persistent listener. Suitable for notifications or summary views.
     *
     * @param conversationId The conversation to query.
     */
    suspend fun fetchTypingState(conversationId: String): Result<TypingState> =
        typingManager.fetchTypingState(conversationId)
}