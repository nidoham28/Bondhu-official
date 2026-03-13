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
 * Manages all Firestore operations for the participant sub-collection.
 *
 * Schema: participant/{parentId}/members/{uid}
 *
 * All writes and reads are keyed by two identifiers:
 *   - [parentId] : the owning entity document ID (community, group, channel, page).
 *   - [uid]      : the participant's Firebase UID, which doubles as the Firestore document ID.
 *
 * @param firestore Injectable [FirebaseFirestore] instance.
 * @param auth      Injectable [FirebaseAuth] instance.
 */
class ParticipantManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val ROOT       = "participant"
        private const val MEMBERS    = "members"
        private const val PAGE_SIZE  = 20

        private const val FIELD_UID       = "uid"
        private const val FIELD_PARENT_ID = "parentId"
        private const val FIELD_ROLE      = "role"
        private const val FIELD_TYPE      = "type"
        private const val FIELD_JOINED_AT = "joinedAt"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun memberCollection(parentId: String) =
        firestore.collection(ROOT).document(parentId).collection(MEMBERS)

    private fun memberDocument(parentId: String, uid: String) =
        memberCollection(parentId).document(uid)

    // ─────────────────────────────────────────────────────────────────────────
    // Write — Single
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a [Participant] document at participant/{parentId}/members/{uid}.
     *
     * The [participant] object must carry matching [Participant.uid] and
     * [Participant.parentId] values to prevent path/data mismatches.
     *
     * @param parentId    Owning entity document ID.
     * @param uid         Participant's Firebase UID.
     * @param participant The [Participant] to persist.
     */
    suspend fun addParticipant(
        parentId: String,
        uid: String,
        participant: Participant
    ): Result<Unit> = runCatching {
        require(participant.uid == uid) {
            "Participant.uid ('${participant.uid}') must match path uid ('$uid')."
        }
        require(participant.parentId == parentId) {
            "Participant.parentId ('${participant.parentId}') must match path parentId ('$parentId')."
        }
        memberDocument(parentId, uid).set(participant).await()
    }

    /**
     * Updates specific fields of an existing participant document.
     *
     * @param parentId Owning entity document ID.
     * @param uid      Participant's Firebase UID.
     * @param fields   Map of Firestore field names to updated values.
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
     * Deletes the participant document at participant/{parentId}/members/{uid}.
     *
     * @param parentId Owning entity document ID.
     * @param uid      Participant's Firebase UID.
     */
    suspend fun removeParticipant(parentId: String, uid: String): Result<Unit> = runCatching {
        memberDocument(parentId, uid).delete().await()
    }

    /**
     * Removes the currently authenticated user from the given parent document.
     *
     * @param parentId Owning entity document ID.
     */
    suspend fun leaveTarget(parentId: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("No authenticated user is signed in."))
        return removeParticipant(parentId, uid)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write — Batch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes multiple participant documents to the same parent in a single
     * atomic Firestore batch. All entries are validated before the batch
     * is committed; one failure aborts the entire operation.
     *
     * @param parentId     Owning entity document ID.
     * @param participants Map of uid → [Participant] to persist.
     */
    suspend fun addParticipantsBatch(
        parentId: String,
        participants: Map<String, Participant>
    ): Result<Unit> = runCatching {
        require(participants.isNotEmpty()) { "Participants map must not be empty." }
        val batch = firestore.batch()
        participants.forEach { (uid, participant) ->
            require(participant.uid == uid) {
                "Participant.uid ('${participant.uid}') must match key uid ('$uid')."
            }
            require(participant.parentId == parentId) {
                "Participant.parentId must match parentId '$parentId' for uid '$uid'."
            }
            batch.set(memberDocument(parentId, uid), participant)
        }
        batch.commit().await()
    }

    /**
     * Deletes multiple participant documents from the same parent in a single
     * atomic Firestore batch.
     *
     * @param parentId Owning entity document ID.
     * @param uids     UIDs of participants to remove.
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

    // ─────────────────────────────────────────────────────────────────────────
    // Read — Single
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a single [Participant] document, or null if it does not exist.
     *
     * @param parentId Owning entity document ID.
     * @param uid      Participant's Firebase UID.
     */
    suspend fun fetchParticipant(parentId: String, uid: String): Result<Participant?> = runCatching {
        memberDocument(parentId, uid).get().await().toObject(Participant::class.java)
    }

    /**
     * Returns true if a participant document exists for [uid] under [parentId].
     *
     * @param parentId Owning entity document ID.
     * @param uid      Participant's Firebase UID.
     */
    suspend fun isParticipant(parentId: String, uid: String): Result<Boolean> = runCatching {
        memberDocument(parentId, uid).get().await().exists()
    }

    /**
     * Fetches the [Participant] record for the currently authenticated user
     * within the given parent document.
     *
     * @param parentId Owning entity document ID.
     */
    suspend fun fetchCurrentUserParticipant(parentId: String): Result<Participant?> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("No authenticated user is signed in."))
        return fetchParticipant(parentId, uid)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read — Collection Group
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns all parentIds that [uid] has joined, filtered by [type].
     *
     * Uses a collection group query because parentIds are not known in advance.
     * The parentId is read from the stored [FIELD_PARENT_ID] field, not from
     * path traversal.
     *
     * Requires a composite Firestore index on (uid, type).
     *
     * @param uid  Participant's Firebase UID.
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    suspend fun fetchJoinedIds(
        uid: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Result<List<String>> = runCatching {
        firestore.collectionGroup(MEMBERS)
            .whereEqualTo(FIELD_UID, uid)
            .whereEqualTo(FIELD_TYPE, type.value)
            .get()
            .await()
            .documents
            .mapNotNull { it.getString(FIELD_PARENT_ID) }
    }

    /**
     * Finds the shared [parentId] where both [uid1] and [uid2] are participants,
     * scoped exclusively to [ParticipantType.PERSONAL].
     *
     * Strategy: fetch all PERSONAL parentIds for [uid1] via a collection group
     * query, then check each one for the presence of [uid2]. Returns the first
     * match, or null if no shared parent exists.
     *
     * Requires a composite Firestore index on (uid, type).
     *
     * @param uid1 First participant's Firebase UID.
     * @param uid2 Second participant's Firebase UID.
     * @return The shared parentId if found, null otherwise.
     */
    suspend fun fetchSharedParentId(uid1: String, uid2: String): Result<String?> = runCatching {
        val parentIds = firestore.collectionGroup(MEMBERS)
            .whereEqualTo(FIELD_UID, uid1)
            .whereEqualTo(FIELD_TYPE, ParticipantType.PERSONAL.value)
            .get()
            .await()
            .documents
            .mapNotNull { it.getString(FIELD_PARENT_ID) }

        parentIds.firstOrNull { parentId ->
            memberDocument(parentId, uid2).get().await().exists()
        }
    }

    /**
     * Emits an updated list of parentIds the given [uid] has joined whenever their
     * membership changes, filtered by [type].
     *
     * Requires a composite Firestore index on (uid, type) in the members collection group.
     *
     * @param uid  Participant's Firebase UID.
     * @param type Participant type filter; defaults to [ParticipantType.PERSONAL].
     */
    fun observeJoinedIds(
        uid: String,
        type: ParticipantType = ParticipantType.PERSONAL
    ): Flow<List<String>> = callbackFlow {
        val reg: ListenerRegistration = firestore.collectionGroup(MEMBERS)
            .whereEqualTo(FIELD_UID, uid)
            .whereEqualTo(FIELD_TYPE, type.value)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                trySend(
                    snapshot?.documents?.mapNotNull { it.getString(FIELD_PARENT_ID) } ?: emptyList()
                )
            }
        awaitClose { reg.remove() }
    }

    /**
     * Applies a [ParticipantFilter] to the member sub-collection and returns all
     * matching records as a plain list.
     *
     * @param parentId Owning entity document ID.
     * @param filter   [ParticipantFilter] describing the desired constraints.
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
     * Returns a cursor-paginated [Flow] of [PagingData] filtered to a specific
     * [ParticipantType], ordered by join date ascending.
     *
     * @param parentId Owning entity document ID.
     * @param type     Participant type to filter by.
     * @param pageSize Documents per page.
     */
    fun fetchParticipantsByTypePaged(
        parentId: String,
        type: ParticipantType = ParticipantType.PERSONAL,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            ParticipantPagingSource(
                memberCollection(parentId)
                    .whereEqualTo(FIELD_TYPE, type.value)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.ASCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    // ─────────────────────────────────────────────────────────────────────────
    // Real-Time Listeners
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits an updated list of [Participant] objects whenever the member
     * sub-collection changes. An optional [ParticipantFilter] narrows the
     * observed query; if omitted, the entire sub-collection is observed.
     *
     * The underlying [ListenerRegistration] is removed automatically when
     * the returned [Flow] is cancelled.
     *
     * @param parentId Owning entity document ID.
     * @param filter   Optional filter to restrict the listener query.
     */
    fun observeParticipants(
        parentId: String,
        filter: ParticipantFilter? = null
    ): Flow<List<Participant>> = callbackFlow {
        val base  = memberCollection(parentId)
        val query = filter?.applyTo(base) ?: base

        val reg: ListenerRegistration = query.addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObjects(Participant::class.java) ?: emptyList())
        }
        awaitClose { reg.remove() }
    }

    /**
     * Emits the [Participant] record for a specific user whenever it changes,
     * or null if the document does not exist.
     *
     * @param parentId Owning entity document ID.
     * @param uid      Participant's Firebase UID.
     */
    fun observeParticipant(parentId: String, uid: String): Flow<Participant?> = callbackFlow {
        val reg: ListenerRegistration =
            memberDocument(parentId, uid).addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                trySend(snapshot?.toObject(Participant::class.java))
            }
        awaitClose { reg.remove() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Paging3 — Cursor-Paginated Flows
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] for all participants
     * within [parentId], ordered by join date ascending.
     *
     * @param parentId Owning entity document ID.
     * @param pageSize Documents per page; defaults to [PAGE_SIZE].
     */
    fun fetchParticipantsPaged(
        parentId: String,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false, prefetchDistance = pageSize / 2),
        pagingSourceFactory = {
            ParticipantPagingSource(
                memberCollection(parentId)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.ASCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] filtered to a specific
     * [ParticipantRole], ordered by join date ascending.
     *
     * @param parentId Owning entity document ID.
     * @param role     Role to filter by.
     * @param pageSize Documents per page; defaults to [PAGE_SIZE].
     */
    fun fetchParticipantsByRolePaged(
        parentId: String,
        role: ParticipantRole,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = {
            ParticipantPagingSource(
                memberCollection(parentId)
                    .whereEqualTo(FIELD_ROLE, role.value)
                    .orderBy(FIELD_JOINED_AT, Query.Direction.ASCENDING)
                    .limit(pageSize.toLong())
            )
        }
    ).flow

    /**
     * Returns a cursor-paginated [Flow] of [PagingData] for all participants
     * matching [filter], ordered by join date ascending by default unless the
     * filter overrides the sort order.
     *
     * @param parentId Owning entity document ID.
     * @param filter   [ParticipantFilter] to apply before paginating.
     * @param pageSize Documents per page; defaults to [PAGE_SIZE].
     */
    fun fetchParticipantsFilteredPaged(
        parentId: String,
        filter: ParticipantFilter,
        pageSize: Int = PAGE_SIZE
    ): Flow<PagingData<Participant>> = Pager(
        config = PagingConfig(pageSize = pageSize, enablePlaceholders = false, prefetchDistance = pageSize / 2),
        pagingSourceFactory = {
            ParticipantPagingSource(
                filter.applyTo(memberCollection(parentId)).limit(pageSize.toLong())
            )
        }
    ).flow
}

// ─────────────────────────────────────────────────────────────────────────────
// ParticipantFilter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable, composable filter descriptor for participant queries.
 *
 * Only non-null fields are applied to the Firestore query. Combining multiple
 * inequality or ordering constraints may require a composite Firestore index.
 *
 * Usage:
 * ```kotlin
 * val filter = ParticipantFilter(
 *     role      = ParticipantRole.ADMIN,
 *     type      = ParticipantType.PERSONAL,
 *     sortOrder = Query.Direction.DESCENDING
 * )
 * ```
 *
 * @param role         Filters by [ParticipantRole].
 * @param type         Filters by [ParticipantType].
 * @param joinedAfter  Includes only participants who joined after this [Timestamp].
 * @param joinedBefore Includes only participants who joined before this [Timestamp].
 * @param sortOrder    Sort direction on [FIELD_JOINED_AT]. Omit to skip ordering.
 * @param limit        Caps the result set to this many documents.
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
        private const val FIELD_JOINED_AT = "joinedAt"   // must match Participant property name

        val ADMINS = ParticipantFilter(
            role      = ParticipantRole.ADMIN,
            type      = ParticipantType.PERSONAL,
            sortOrder = Query.Direction.DESCENDING
        )

        val MEMBERS = ParticipantFilter(
            role      = ParticipantRole.MEMBER,
            type      = ParticipantType.PERSONAL,
            sortOrder = Query.Direction.ASCENDING
        )
    }

    /** Applies all non-null constraints to [query] and returns the result. */
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
// ParticipantPagingSource
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Firestore-backed [PagingSource] for [Participant] documents.
 *
 * Uses cursor-based pagination via [DocumentSnapshot.startAfter] to avoid
 * re-reading previously loaded pages. Only forward pagination is supported;
 * a refresh always restarts from the first page.
 *
 * @param query Base Firestore [Query]; must include a [Query.limit] clause.
 */
class ParticipantPagingSource(
    private val query: Query
) : PagingSource<DocumentSnapshot, Participant>() {

    override suspend fun load(
        params: LoadParams<DocumentSnapshot>
    ): LoadResult<DocumentSnapshot, Participant> = try {
        val pageQuery    = params.key?.let { query.startAfter(it) } ?: query
        val snapshot     = pageQuery.get().await()
        val participants = snapshot.toObjects(Participant::class.java)
        val nextKey      = snapshot.documents.lastOrNull().takeUnless { snapshot.isEmpty }

        LoadResult.Page(data = participants, prevKey = null, nextKey = nextKey)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<DocumentSnapshot, Participant>): DocumentSnapshot? = null
}