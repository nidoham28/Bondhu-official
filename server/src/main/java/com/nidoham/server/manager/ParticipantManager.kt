package com.nidoham.server.manager

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.nidoham.server.domain.participant.Participant
import com.nidoham.server.util.ParticipantRole
import com.nidoham.server.util.ParticipantType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Manages all participant-related Firestore operations.
 *
 * Firestore schema : participant/{id}/participants/{userId}
 *
 * Every write and read operation is keyed by exactly two identifiers:
 *   - [id]     : the parent document ID (group, channel, conversation, etc.)
 *   - [userId] : the participant's Firebase UID
 *
 * @param firestore Injectable [FirebaseFirestore] instance.
 * @param auth      Injectable [FirebaseAuth] instance.
 */
class ParticipantManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val ROOT_COLLECTION = "participant"
        private const val SUB_COLLECTION  = "participants"
        private const val PAGE_SIZE       = 20

        private const val FIELD_USER_ID   = "uid"
        private const val FIELD_ID        = "id"
        private const val FIELD_ROLE      = "role"
        private const val FIELD_TYPE      = "type"
        private const val FIELD_JOINED_AT = "joined_at"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun participantsCollection(id: String) =
        firestore.collection(ROOT_COLLECTION)
            .document(id)
            .collection(SUB_COLLECTION)

    /**
     * Resolves the document reference for a single participant.
     * The document path is always: participant/{id}/participants/{userId}
     */
    private fun participantDocument(id: String, userId: String) =
        participantsCollection(id).document(userId)

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a participant under participant/{id}/participants/{userId}.
     *
     * Both [Participant.uid] and [Participant.id] are validated before the
     * write is issued.
     *
     * @param id          Parent document ID.
     * @param userId      Participant's Firebase UID.
     * @param participant The [Participant] object to persist.
     */
    suspend fun addParticipant(
        id: String,
        userId: String,
        participant: Participant
    ): Result<Unit> = runCatching {
        require(participant.uid == userId) {
            "Participant uid ('${participant.uid}') must match the provided userId ('$userId')."
        }
        require(!participant.id.isNullOrBlank()) {
            "Participant id must not be null or blank."
        }
        participantDocument(id, userId).set(participant).await()
    }

    /**
     * Adds multiple participants to the same parent document in a single
     * Firestore batch, reducing round-trips and write costs.
     *
     * All entries that fail the uid/id validation are rejected and the entire
     * batch is not committed; this ensures atomic, all-or-nothing semantics.
     *
     * @param id           Parent document ID.
     * @param participants A map of userId → [Participant] entries to persist.
     */
    suspend fun addParticipantsBatch(
        id: String,
        participants: Map<String, Participant>
    ): Result<Unit> = runCatching {
        require(participants.isNotEmpty()) { "Participants map must not be empty." }
        val batch = firestore.batch()
        participants.forEach { (userId, participant) ->
            require(participant.uid == userId) {
                "Participant uid ('${participant.uid}') must match userId ('$userId')."
            }
            require(!participant.id.isNullOrBlank()) {
                "Participant id must not be null or blank for userId '$userId'."
            }
            batch.set(participantDocument(id, userId), participant)
        }
        batch.commit().await()
    }

    /**
     * Updates specific fields of an existing participant document.
     *
     * @param id     Parent document ID.
     * @param userId Participant's Firebase UID.
     * @param fields Map of Firestore field names to their new values.
     */
    suspend fun updateParticipant(
        id: String,
        userId: String,
        fields: Map<String, Any?>
    ): Result<Unit> = runCatching {
        require(fields.isNotEmpty()) { "Update fields must not be empty." }
        participantDocument(id, userId).update(fields).await()
    }

    /**
     * Promotes a participant to the [ParticipantRole.ADMIN] role.
     */
    suspend fun promoteToAdmin(id: String, userId: String): Result<Unit> =
        updateParticipant(id, userId, mapOf(FIELD_ROLE to ParticipantRole.ADMIN.value))

    /**
     * Demotes a participant back to [ParticipantRole.MEMBER] role.
     */
    suspend fun demoteToMember(id: String, userId: String): Result<Unit> =
        updateParticipant(id, userId, mapOf(FIELD_ROLE to ParticipantRole.MEMBER.value))

    /**
     * Removes the participant document at participant/{id}/participants/{userId}.
     *
     * @param id     Parent document ID.
     * @param userId Participant's Firebase UID.
     */
    suspend fun removeParticipant(id: String, userId: String): Result<Unit> = runCatching {
        participantDocument(id, userId).delete().await()
    }

    /**
     * Removes multiple participants from the same parent document in a single
     * Firestore batch.
     *
     * @param id      Parent document ID.
     * @param userIds The UIDs of the participants to remove.
     */
    suspend fun removeParticipantsBatch(
        id: String,
        userIds: List<String>
    ): Result<Unit> = runCatching {
        require(userIds.isNotEmpty()) { "userIds must not be empty." }
        val batch = firestore.batch()
        userIds.forEach { userId ->
            batch.delete(participantDocument(id, userId))
        }
        batch.commit().await()
    }

    /**
     * Removes the currently authenticated user from the given parent document.
     */
    suspend fun leaveTarget(id: String): Result<Unit> {
        val currentUserId = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("No authenticated user is signed in."))
        return removeParticipant(id, currentUserId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single participant document at participant/{id}/participants/{userId}.
     *
     * @return The [Participant] object, or null if the document does not exist.
     */
    suspend fun fetchParticipant(id: String, userId: String): Result<Participant?> = runCatching {
        participantDocument(id, userId)
            .get()
            .await()
            .toObject(Participant::class.java)
    }

    /**
     * Fetches multiple participant documents by their UIDs in a single batch read.
     * Documents that do not exist are silently omitted from the result.
     *
     * @param id      Parent document ID.
     * @param userIds The UIDs to retrieve.
     * @return A list of [Participant] objects that were found.
     */
    suspend fun fetchParticipantsByIds(
        id: String,
        userIds: List<String>
    ): Result<List<Participant>> = runCatching {
        require(userIds.isNotEmpty()) { "userIds must not be empty." }
        userIds
            .map { userId -> participantDocument(id, userId).get().await() }
            .mapNotNull { it.toObject(Participant::class.java) }
    }

    /**
     * Checks whether a participant document exists at
     * participant/{id}/participants/{userId}.
     */
    suspend fun isParticipant(id: String, userId: String): Result<Boolean> = runCatching {
        participantDocument(id, userId).get().await().exists()
    }

    /**
     * Fetches the participant record for the currently authenticated user
     * within the given parent document.
     */
    suspend fun fetchCurrentUserParticipant(id: String): Result<Participant?> {
        val currentUserId = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("No authenticated user is signed in."))
        return fetchParticipant(id, currentUserId)
    }

    /**
     * Resolves the stored [Participant.id] field for a given user within a target,
     * filtered by participant type (defaults to [ParticipantType.PERSONAL]).
     *
     * @param userId   The participant's Firebase UID.
     * @param targetId The parent document ID to look up within.
     * @param type     Participant type filter; defaults to [ParticipantType.PERSONAL].
     * @return The [Participant.id] value if the document exists and type matches, null otherwise.
     */
    suspend fun fetchId(
        userId: String,
        targetId: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<String?> = runCatching {
        val participant = participantDocument(targetId, userId)
            .get()
            .await()
            .toObject(Participant::class.java)
        participant?.takeIf { it.type == type.value }?.id
    }

    /**
     * Fetches all parent document IDs that a given user has joined,
     * filtered by participant type (defaults to [ParticipantType.PERSONAL]).
     *
     * Requires a composite Firestore index on (uid, type).
     *
     * @param userId The UID to search across all participant sub-collections.
     * @param type   Participant type filter; defaults to [ParticipantType.PERSONAL].
     * @return A list of parent document IDs (the {id} segment of the Firestore path).
     */
    suspend fun fetchJoinedIds(
        userId: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<String>> = runCatching {
        firestore.collectionGroup(SUB_COLLECTION)
            .whereEqualTo(FIELD_USER_ID, userId)
            .whereEqualTo(FIELD_TYPE, type.value)
            .get()
            .await()
            .documents
            .mapNotNull { it.reference.parent.parent?.id }
    }

    /**
     * Fetches all [Participant] records for the currently authenticated user,
     * filtered by participant type (defaults to [ParticipantType.PERSONAL]).
     */
    suspend fun fetchCurrentUserJoinedList(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Participant>> = runCatching {
        val currentUserId = auth.currentUser?.uid
            ?: error("No authenticated user is signed in.")
        firestore.collectionGroup(SUB_COLLECTION)
            .whereEqualTo(FIELD_USER_ID, currentUserId)
            .whereEqualTo(FIELD_TYPE, type.value)
            .get()
            .await()
            .toObjects(Participant::class.java)
    }

    /**
     * Returns the total number of participants within a given parent document.
     */
    suspend fun fetchParticipantCount(id: String): Result<Int> = runCatching {
        participantsCollection(id).get().await().size()
    }

    /**
     * Returns the number of participants matching a specific role within a
     * given parent document.
     *
     * @param id   Parent document ID.
     * @param role The role to count.
     */
    suspend fun fetchParticipantCountByRole(
        id: String,
        role: ParticipantRole
    ): Result<Int> = runCatching {
        participantsCollection(id)
            .whereEqualTo(FIELD_ROLE, role.value)
            .get()
            .await()
            .size()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filter Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies a [ParticipantFilter] to the participants sub-collection of the
     * given parent document and returns all matching records as a plain list.
     *
     * This is the primary entry point for ad-hoc filtering. All filter fields
     * are optional; unset fields are simply ignored. Combining multiple fields
     * in a single [ParticipantFilter] may require a composite Firestore index.
     *
     * @param id     Parent document ID.
     * @param filter The [ParticipantFilter] describing the desired constraints.
     * @return A list of matching [Participant] objects.
     */
    suspend fun fetchParticipantsFiltered(
        id: String,
        filter: ParticipantFilter
    ): Result<List<Participant>> = runCatching {
        filter.applyTo(participantsCollection(id))
            .get()
            .await()
            .toObjects(Participant::class.java)
    }

    /**
     * Applies a [ParticipantFilter] and returns results as cursor-paginated
     * [PagingData], suitable for large lists.
     *
     * @param id       Parent document ID.
     * @param filter   The [ParticipantFilter] describing the desired constraints.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchParticipantsFilteredPaged(
        id: String,
        filter: ParticipantFilter,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false,
            prefetchDistance   = pageSize / 2
        ),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = filter.applyTo(participantsCollection(id))
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // Real-Time Listeners
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits an updated list of [Participant] objects whenever the participants
     * sub-collection of the given parent document changes.
     *
     * An optional [ParticipantFilter] can be supplied to narrow the listened
     * query; if omitted the entire sub-collection is observed.
     *
     * The underlying Firestore [ListenerRegistration] is automatically removed
     * when the returned [Flow] is cancelled.
     *
     * @param id     Parent document ID.
     * @param filter Optional filter to restrict the listener query.
     */
    fun observeParticipants(
        id: String,
        filter: ParticipantFilter? = null
    ): Flow<List<Participant>> = callbackFlow {
        val baseQuery = participantsCollection(id)
        val query     = filter?.applyTo(baseQuery) ?: baseQuery

        val registration: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.toObjects(Participant::class.java) ?: emptyList())
        }
        awaitClose { registration.remove() }
    }

    /**
     * Emits the [Participant] record for a specific user within the given parent
     * document whenever that document changes, or null if it does not exist.
     *
     * @param id     Parent document ID.
     * @param userId Participant's Firebase UID.
     */
    fun observeParticipant(id: String, userId: String): Flow<Participant?> = callbackFlow {
        val registration: ListenerRegistration =
            participantDocument(id, userId).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(Participant::class.java))
            }
        awaitClose { registration.remove() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Paging3 — Paginated Participant Loading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a [Flow] of cursor-paginated [PagingData] for all participants
     * within the given parent document, ordered by join date ascending.
     *
     * @param id       Parent document ID.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchParticipantsPaged(
        id: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false,
            prefetchDistance   = pageSize / 2
        ),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = participantsCollection(id)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.ASCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    /**
     * Returns a [Flow] of cursor-paginated [PagingData] filtered to a specific
     * [ParticipantRole].
     */
    fun fetchParticipantsByRolePaged(
        id: String,
        role: ParticipantRole,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = participantsCollection(id)
                    .whereEqualTo(FIELD_ROLE, role.value)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.ASCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    /**
     * Returns a [Flow] of cursor-paginated [PagingData] filtered to a specific
     * [ParticipantType] (defaults to [ParticipantType.PERSONAL]).
     */
    fun fetchParticipantsByTypePaged(
        id: String,
        type: ParticipantType = ParticipantType.PERSONAL,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = participantsCollection(id)
                    .whereEqualTo(FIELD_TYPE, type.value)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.ASCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow
}

// ─────────────────────────────────────────────────────────────────────────────
// ParticipantFilter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A composable, immutable filter descriptor for participant queries.
 *
 * Each field is optional. Only non-null fields are applied to the Firestore
 * query, so any combination of constraints is valid. Combining multiple fields
 * on the same query may require a composite Firestore index.
 *
 * Usage example:
 * ```kotlin
 * val filter = ParticipantFilter(
 *     role      = ParticipantRole.ADMIN,
 *     type      = ParticipantType.PERSONAL,
 *     joinedAfter  = Timestamp(1_700_000_000, 0),
 *     sortOrder = Query.Direction.DESCENDING
 * )
 * manager.fetchParticipantsFiltered(id = "abc", filter = filter)
 * ```
 *
 * @param role         Filters participants by a specific [ParticipantRole].
 * @param type         Filters participants by a specific [ParticipantType].
 *                     Defaults to [ParticipantType.PERSONAL] when explicitly set.
 * @param joinedAfter  Includes only participants who joined after this [Timestamp].
 * @param joinedBefore Includes only participants who joined before this [Timestamp].
 * @param sortOrder    Controls the sort direction on [FIELD_JOINED_AT].
 *                     Defaults to [Query.Direction.ASCENDING] when explicitly set.
 * @param limit        Caps the result set to the given number of documents.
 *                     When used with paged variants this is the per-page size.
 */
data class ParticipantFilter(
    val role: ParticipantRole? = null,
    val type: ParticipantType? = null,
    val joinedAfter: Timestamp? = null,
    val joinedBefore: Timestamp? = null,
    val sortOrder: Query.Direction? = null,
    val limit: Long? = null
) {

    companion object {
        private const val FIELD_ROLE      = "role"
        private const val FIELD_TYPE      = "type"
        private const val FIELD_JOINED_AT = "joined_at"

        /** Convenience preset: all admins, personal type, newest first. */
        val ADMINS = ParticipantFilter(
            role      = ParticipantRole.ADMIN,
            type      = ParticipantType.PERSONAL,
            sortOrder = Query.Direction.DESCENDING
        )

        /** Convenience preset: all members, personal type, oldest first. */
        val MEMBERS = ParticipantFilter(
            role      = ParticipantRole.MEMBER,
            type      = ParticipantType.PERSONAL,
            sortOrder = Query.Direction.ASCENDING
        )
    }

    /**
     * Applies all non-null constraints to the given [Query] and returns the
     * resulting constrained [Query].
     *
     * @param query The base Firestore [Query] to build upon.
     */
    fun applyTo(query: Query): Query {
        var q = query

        role?.let          { q = q.whereEqualTo(FIELD_ROLE, it.value) }
        type?.let          { q = q.whereEqualTo(FIELD_TYPE, it.value) }
        joinedAfter?.let   { q = q.whereGreaterThan(FIELD_JOINED_AT, it) }
        joinedBefore?.let  { q = q.whereLessThan(FIELD_JOINED_AT, it) }
        sortOrder?.let     { q = q.orderBy(FIELD_JOINED_AT, it) }
        limit?.let         { q = q.limit(it) }

        return q
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PagingSource
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A Firestore-backed [PagingSource] for [Participant] documents.
 *
 * Uses cursor-based pagination via [DocumentSnapshot.startAfter] so that
 * previously loaded pages are never re-read, keeping Firestore read costs and
 * memory footprint minimal. Only forward pagination is supported; refreshes
 * always restart from the first page.
 *
 * @param query The base Firestore [Query] — must include a [Query.limit] clause.
 */
class ParticipantPagingSource(
    private val query: Query
) : PagingSource<DocumentSnapshot, Participant>() {

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, Participant> = try {
        val pageQuery = params.key
            ?.let { query.startAfter(it) }
            ?: query

        val snapshot     = pageQuery.get().await()
        val participants = snapshot.toObjects(Participant::class.java)
        val nextKey      = snapshot.documents.lastOrNull().takeUnless { snapshot.isEmpty() }

        LoadResult.Page(
            data    = participants,
            prevKey = null,
            nextKey = nextKey
        )
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(
        state: PagingState<DocumentSnapshot, Participant>
    ): DocumentSnapshot? = null
}