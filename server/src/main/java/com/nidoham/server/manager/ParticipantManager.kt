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
 * Firestore schema : participant/{parentId}/member/{uid}
 *
 * Every write and read operation is keyed by exactly two identifiers:
 *   - [parentId] : the community, group, channel, or page document ID.
 *   - [uid]      : the participant's Firebase UID (also the Firestore document ID).
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
        private const val SUB_COLLECTION  = "member"          // participant/{parentId}/member/{uid}
        private const val PAGE_SIZE       = 20

        private const val FIELD_UID       = "uid"
        private const val FIELD_PARENT_ID = "parent_id"
        private const val FIELD_ROLE      = "role"
        private const val FIELD_TYPE      = "type"
        private const val FIELD_JOINED_AT = "joined_at"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun memberCollection(parentId: String) =
        firestore.collection(ROOT_COLLECTION)
            .document(parentId)
            .collection(SUB_COLLECTION)

    /**
     * Resolves the document reference for a single participant.
     * Path: participant/{parentId}/member/{uid}
     */
    private fun memberDocument(parentId: String, uid: String) =
        memberCollection(parentId).document(uid)

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Adds a participant under participant/{parentId}/member/{uid}.
     *
     * Both [Participant.uid] and [Participant.parentId] are validated against
     * the provided path identifiers before the write is issued.
     *
     * @param parentId    The community / group / page document ID.
     * @param uid         The participant's Firebase UID.
     * @param participant The [Participant] object to persist.
     */
    suspend fun addParticipant(
        parentId: String,
        uid: String,
        participant: Participant
    ): Result<Unit> = runCatching {
        require(participant.uid == uid) {
            "Participant uid ('${participant.uid}') must match the provided uid ('$uid')."
        }
        require(participant.parentId == parentId) {
            "Participant parentId ('${participant.parentId}') must match the provided parentId ('$parentId')."
        }
        memberDocument(parentId, uid).set(participant).await()
    }

    /**
     * Adds multiple participants to the same parent document in a single
     * Firestore batch, reducing round-trips and ensuring atomic writes.
     *
     * All entries are validated before the batch is committed; a single
     * validation failure aborts the entire operation.
     *
     * @param parentId     The community / group / page document ID.
     * @param participants A map of uid → [Participant] entries to persist.
     */
    suspend fun addParticipantsBatch(
        parentId: String,
        participants: Map<String, Participant>
    ): Result<Unit> = runCatching {
        require(participants.isNotEmpty()) { "Participants map must not be empty." }
        val batch = firestore.batch()
        participants.forEach { (uid, participant) ->
            require(participant.uid == uid) {
                "Participant uid ('${participant.uid}') must match uid ('$uid')."
            }
            require(participant.parentId == parentId) {
                "Participant parentId must match parentId '$parentId' for uid '$uid'."
            }
            batch.set(memberDocument(parentId, uid), participant)
        }
        batch.commit().await()
    }

    /**
     * Updates specific fields of an existing participant document.
     *
     * @param parentId The community / group / page document ID.
     * @param uid      The participant's Firebase UID.
     * @param fields   Map of Firestore field names to their new values.
     */
    suspend fun updateParticipant(
        parentId: String,
        uid: String,
        fields: Map<String, Any?>
    ): Result<Unit> = runCatching {
        require(fields.isNotEmpty()) { "Update fields must not be empty." }
        memberDocument(parentId, uid).update(fields).await()
    }

    /** Promotes a participant to [ParticipantRole.ADMIN]. */
    suspend fun promoteToAdmin(parentId: String, uid: String): Result<Unit> =
        updateParticipant(parentId, uid, mapOf(FIELD_ROLE to ParticipantRole.ADMIN.value))

    /** Demotes a participant back to [ParticipantRole.MEMBER]. */
    suspend fun demoteToMember(parentId: String, uid: String): Result<Unit> =
        updateParticipant(parentId, uid, mapOf(FIELD_ROLE to ParticipantRole.MEMBER.value))

    /**
     * Removes the participant document at participant/{parentId}/member/{uid}.
     *
     * @param parentId The community / group / page document ID.
     * @param uid      The participant's Firebase UID.
     */
    suspend fun removeParticipant(parentId: String, uid: String): Result<Unit> = runCatching {
        memberDocument(parentId, uid).delete().await()
    }

    /**
     * Removes multiple participants from the same parent document in a single
     * Firestore batch.
     *
     * @param parentId The community / group / page document ID.
     * @param uids     The UIDs of the participants to remove.
     */
    suspend fun removeParticipantsBatch(
        parentId: String,
        uids: List<String>
    ): Result<Unit> = runCatching {
        require(uids.isNotEmpty()) { "uids must not be empty." }
        val batch = firestore.batch()
        uids.forEach { uid -> batch.delete(memberDocument(parentId, uid)) }
        batch.commit().await()
    }

    /**
     * Removes the currently authenticated user from the given parent document.
     */
    suspend fun leaveTarget(parentId: String): Result<Unit> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("No authenticated user is signed in."))
        return removeParticipant(parentId, currentUid)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single participant document at participant/{parentId}/member/{uid}.
     *
     * @return The [Participant] object, or null if the document does not exist.
     */
    suspend fun fetchParticipant(parentId: String, uid: String): Result<Participant?> = runCatching {
        memberDocument(parentId, uid)
            .get()
            .await()
            .toObject(Participant::class.java)
    }

    /**
     * Fetches multiple participant documents by their UIDs in a single batch read.
     * Documents that do not exist are silently omitted from the result.
     *
     * @param parentId The community / group / page document ID.
     * @param uids     The UIDs to retrieve.
     */
    suspend fun fetchParticipantsByIds(
        parentId: String,
        uids: List<String>
    ): Result<List<Participant>> = runCatching {
        require(uids.isNotEmpty()) { "uids must not be empty." }
        uids
            .map { uid -> memberDocument(parentId, uid).get().await() }
            .mapNotNull { it.toObject(Participant::class.java) }
    }

    /**
     * Checks whether a participant document exists at
     * participant/{parentId}/member/{uid}.
     */
    suspend fun isParticipant(parentId: String, uid: String): Result<Boolean> = runCatching {
        memberDocument(parentId, uid).get().await().exists()
    }

    /**
     * Fetches the participant record for the currently authenticated user
     * within the given parent document.
     */
    suspend fun fetchCurrentUserParticipant(parentId: String): Result<Participant?> {
        val currentUid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("No authenticated user is signed in."))
        return fetchParticipant(parentId, currentUid)
    }

    /**
     * Resolves the stored [Participant.parentId] for a given user, filtered by
     * participant type (defaults to [ParticipantType.PERSONAL]).
     *
     * Uses a collection group query because the parentId is not known in advance.
     * The parentId is read from the [FIELD_PARENT_ID] field stored in the document,
     * ensuring the returned value always reflects the actual community/group ID.
     *
     * Requires a composite Firestore index on (uid, type).
     *
     * @param uid  The participant's Firebase UID.
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     * @return The parentId (communityId) if found, null otherwise.
     */
    suspend fun fetchParentId(
        uid: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<String?> = runCatching {
        firestore.collectionGroup(SUB_COLLECTION)
            .whereEqualTo(FIELD_UID, uid)
            .whereEqualTo(FIELD_TYPE, type.value)
            .get()
            .await()
            .documents
            .firstOrNull()
            ?.getString(FIELD_PARENT_ID)
    }

    /**
     * Fetches all parentIds (communityIds) that a given user has joined,
     * filtered by participant type (defaults to [ParticipantType.PERSONAL]).
     *
     * The parentId is resolved from the [FIELD_PARENT_ID] field stored in each
     * document rather than from path traversal, making it safe for collection
     * group queries regardless of nesting depth.
     *
     * Requires a composite Firestore index on (uid, type).
     *
     * @param uid  The participant's Firebase UID.
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     * @return A list of parentIds (communityIds) the user has joined.
     */
    suspend fun fetchJoinedIds(
        uid: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<String>> = runCatching {
        firestore.collectionGroup(SUB_COLLECTION)
            .whereEqualTo(FIELD_UID, uid)
            .whereEqualTo(FIELD_TYPE, type.value)
            .get()
            .await()
            .documents
            .mapNotNull { it.getString(FIELD_PARENT_ID) }
    }

    /**
     * Fetches all [Participant] records for the currently authenticated user,
     * filtered by participant type (defaults to [ParticipantType.PERSONAL]).
     */
    suspend fun fetchCurrentUserJoinedList(
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<Participant>> = runCatching {
        val currentUid = auth.currentUser?.uid
            ?: error("No authenticated user is signed in.")
        firestore.collectionGroup(SUB_COLLECTION)
            .whereEqualTo(FIELD_UID, currentUid)
            .whereEqualTo(FIELD_TYPE, type.value)
            .get()
            .await()
            .toObjects(Participant::class.java)
    }

    /**
     * Returns the total number of participants within a given parent document.
     */
    suspend fun fetchParticipantCount(parentId: String): Result<Int> = runCatching {
        memberCollection(parentId).get().await().size()
    }

    /**
     * Returns the number of participants matching a specific role within a
     * given parent document.
     *
     * @param parentId The community / group / page document ID.
     * @param role     The role to count.
     */
    suspend fun fetchParticipantCountByRole(
        parentId: String,
        role: ParticipantRole
    ): Result<Int> = runCatching {
        memberCollection(parentId)
            .whereEqualTo(FIELD_ROLE, role.value)
            .get()
            .await()
            .size()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filter Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies a [ParticipantFilter] to the member sub-collection of the
     * given parent document and returns all matching records as a plain list.
     *
     * @param parentId The community / group / page document ID.
     * @param filter   The [ParticipantFilter] describing the desired constraints.
     */
    suspend fun fetchParticipantsFiltered(
        parentId: String,
        filter: ParticipantFilter
    ): Result<List<Participant>> = runCatching {
        filter.applyTo(memberCollection(parentId))
            .get()
            .await()
            .toObjects(Participant::class.java)
    }

    /**
     * Applies a [ParticipantFilter] and returns results as cursor-paginated
     * [PagingData], suitable for large lists.
     *
     * @param parentId The community / group / page document ID.
     * @param filter   The [ParticipantFilter] describing the desired constraints.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchParticipantsFilteredPaged(
        parentId: String,
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
                query = filter.applyTo(memberCollection(parentId))
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // Real-Time Listeners
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits an updated list of [Participant] objects whenever the member
     * sub-collection of the given parent document changes.
     *
     * An optional [ParticipantFilter] can be supplied to narrow the observed
     * query; if omitted the entire sub-collection is observed.
     *
     * The underlying Firestore [ListenerRegistration] is automatically removed
     * when the returned [Flow] is cancelled.
     *
     * @param parentId The community / group / page document ID.
     * @param filter   Optional filter to restrict the listener query.
     */
    fun observeParticipants(
        parentId: String,
        filter: ParticipantFilter? = null
    ): Flow<List<Participant>> = callbackFlow {
        val baseQuery = memberCollection(parentId)
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
     * @param parentId The community / group / page document ID.
     * @param uid      The participant's Firebase UID.
     */
    fun observeParticipant(parentId: String, uid: String): Flow<Participant?> = callbackFlow {
        val registration: ListenerRegistration =
            memberDocument(parentId, uid).addSnapshotListener { snapshot, error ->
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
     * @param parentId The community / group / page document ID.
     * @param pageSize Number of documents to load per page.
     */
    fun fetchParticipantsPaged(
        parentId: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false,
            prefetchDistance   = pageSize / 2
        ),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = memberCollection(parentId)
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
        parentId: String,
        role: ParticipantRole,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = memberCollection(parentId)
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
        parentId: String,
        type: ParticipantType = ParticipantType.PERSONAL,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(
            pageSize           = pageSize,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            ParticipantPagingSource(
                query = memberCollection(parentId)
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
 *     role         = ParticipantRole.ADMIN,
 *     type         = ParticipantType.PERSONAL,
 *     joinedAfter  = Timestamp(1_700_000_000, 0),
 *     sortOrder    = Query.Direction.DESCENDING
 * )
 * manager.fetchParticipantsFiltered(parentId = "communityAbc", filter = filter)
 * ```
 *
 * @param role         Filters participants by a specific [ParticipantRole].
 * @param type         Filters participants by a specific [ParticipantType].
 * @param joinedAfter  Includes only participants who joined after this [Timestamp].
 * @param joinedBefore Includes only participants who joined before this [Timestamp].
 * @param sortOrder    Controls the sort direction on [FIELD_JOINED_AT].
 * @param limit        Caps the result set to the given number of documents.
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
     */
    fun applyTo(query: Query): Query {
        var q = query

        role?.let         { q = q.whereEqualTo(FIELD_ROLE, it.value) }
        type?.let         { q = q.whereEqualTo(FIELD_TYPE, it.value) }
        joinedAfter?.let  { q = q.whereGreaterThan(FIELD_JOINED_AT, it) }
        joinedBefore?.let { q = q.whereLessThan(FIELD_JOINED_AT, it) }
        sortOrder?.let    { q = q.orderBy(FIELD_JOINED_AT, it) }
        limit?.let        { q = q.limit(it) }

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
 * previously loaded pages are never re-read. Only forward pagination is
 * supported; refreshes always restart from the first page.
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
        val nextKey      = snapshot.documents.lastOrNull().takeUnless { snapshot.isEmpty }

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