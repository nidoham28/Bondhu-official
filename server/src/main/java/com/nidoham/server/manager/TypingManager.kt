package com.nidoham.server.manager

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// TypingState
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents the current typing state for a given conversation.
 *
 * @param conversationId The conversation this state belongs to.
 * @param typingUserIds  A list of user IDs who are actively typing, excluding
 *                       the current user. An empty list means no one is typing.
 */
data class TypingState(
    val conversationId: String,
    val typingUserIds: List<String>
)

// ─────────────────────────────────────────────────────────────────────────────
// TypingManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Manages all real-time typing indicator operations for a messaging system.
 *
 * Firestore schema:
 *   typing/{conversationId}  —  { userId: Timestamp | FieldValue.delete() }
 *
 * Each field in the typing document represents a single user. When a user begins
 * typing, their field is set to the server timestamp. When they stop, their field
 * is deleted via [FieldValue.delete()]. Entries older than [TYPING_TIMEOUT_MS]
 * are treated as stale and excluded from the emitted [TypingState], providing
 * resilience against clients that disconnect without sending an explicit
 * stop-typing signal.
 *
 * All write operations return [Result] wrappers so the caller decides how to
 * handle failures. The observer flow degrades gracefully on transient errors,
 * always emitting a safe empty state rather than crashing the UI.
 *
 * @param firestore Injectable [FirebaseFirestore] instance.
 * @param auth      Injectable [FirebaseAuth] instance.
 */
class TypingManager(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val TYPING_COLLECTION = "typing"

        /**
         * Duration in milliseconds after which a typing entry is considered stale.
         * Align this value with the debounce interval used by the UI to send
         * typing heartbeats so that indicators expire naturally if a client
         * disconnects without sending an explicit stop-typing signal.
         *
         * Default: 10 seconds.
         */
        const val TYPING_TIMEOUT_MS = 10_000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun typingDocument(conversationId: String) =
        firestore.collection(TYPING_COLLECTION).document(conversationId)

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Records that the currently authenticated user has started or stopped typing
     * in the given conversation.
     *
     * When [typing] is true, the user's field is set to the server timestamp,
     * serving as a heartbeat. When false, the field is deleted via
     * [FieldValue.delete()], keeping the typing document clean. The write uses
     * [SetOptions.merge()] so no other participant's field is ever overwritten.
     *
     * Firestore's local write queue ensures this call is safe to make while
     * offline — the operation will be retried automatically once connectivity
     * is restored.
     *
     * @param conversationId The conversation in which the typing event occurred.
     * @param typing         True if the user is currently typing; false if they
     *                       have stopped or cleared the input field.
     * @return [Result.success] on completion, or [Result.failure] with the
     *         underlying exception if the write could not be enqueued.
     */
    suspend fun setTyping(
        conversationId: String,
        typing: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val currentUserId = auth.currentUser?.uid
                ?: error("No authenticated user is signed in.")

            val value: Any = if (typing) FieldValue.serverTimestamp() else FieldValue.delete()

            typingDocument(conversationId)
                .set(mapOf(currentUserId to value), SetOptions.merge())
                .await()

            // Explicit Unit coerces Result<Void!> from the Firestore Task<Void>
            // into the declared return type of Result<Unit>.
            Unit
        }
    }

    /**
     * Explicitly marks the currently authenticated user as no longer typing.
     *
     * This is a convenience wrapper around [setTyping] with [typing] set to
     * false. It should be called from lifecycle callbacks — for example when the
     * user navigates away, the app moves to the background, or the conversation
     * screen is destroyed — to prevent stale indicators from persisting for
     * other participants.
     *
     * @param conversationId The conversation to clear the typing indicator for.
     * @return [Result.success] on completion, or [Result.failure] on error.
     */
    suspend fun clearTyping(conversationId: String): Result<Unit> =
        setTyping(conversationId, false)

    /**
     * Clears the typing indicator for a specific [userId] regardless of which
     * user is currently authenticated. This overload is intended for
     * administrative use cases such as removing a user's indicator after they
     * are forcibly removed from a conversation.
     *
     * @param conversationId The conversation to clear the typing indicator for.
     * @param userId         The UID of the user whose indicator should be removed.
     * @return [Result.success] on completion, or [Result.failure] on error.
     */
    suspend fun clearTypingForUser(
        conversationId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            typingDocument(conversationId)
                .set(mapOf(userId to FieldValue.delete()), SetOptions.merge())
                .await()
            Unit
        }
    }

    /**
     * Deletes the entire typing document for the given conversation, clearing
     * all active indicators simultaneously. This is appropriate when a
     * conversation is archived, closed, or when all participants have left.
     *
     * @param conversationId The conversation whose typing document should be deleted.
     * @return [Result.success] on completion, or [Result.failure] on error.
     */
    suspend fun clearAllTyping(conversationId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                typingDocument(conversationId).delete().await()
                Unit
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Real-Time Listener
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a [Flow] that emits an updated [TypingState] whenever the set of
     * actively typing users in the given conversation changes.
     *
     * **Stale entry filtering.** Each entry's timestamp is compared against
     * [TYPING_TIMEOUT_MS]. Entries older than the threshold are excluded from
     * the emitted state, preventing ghost indicators from persisting when a
     * client disconnects without sending a stop-typing signal.
     *
     * **Offline behaviour.** When the device is offline, Firestore's local
     * cache delivers the last known snapshot and the flow continues to emit
     * without error. If no cached data is available, a [TypingState] with an
     * empty list is emitted immediately so the UI always has a safe default.
     *
     * **Error handling.** A [FirebaseFirestoreException] with code
     * [FirebaseFirestoreException.Code.PERMISSION_DENIED] is treated as
     * unrecoverable and closes the flow. All other errors are transient;
     * an empty [TypingState] is emitted and the optional [onError] callback
     * is invoked so the caller can log without disrupting the UI.
     *
     * **Lifecycle.** The underlying listener registration is removed
     * automatically when the flow's collector is cancelled, preventing
     * listener leaks across screen transitions.
     *
     * Recommended ViewModel usage:
     * ```kotlin
     * val typingState = typingManager
     *     .observeTyping(conversationId)
     *     .stateIn(
     *         scope        = viewModelScope,
     *         started      = SharingStarted.WhileSubscribed(5_000),
     *         initialValue = TypingState(conversationId, emptyList())
     *     )
     * ```
     *
     * @param conversationId The conversation to observe.
     * @param onError        Optional callback invoked with any non-fatal Firestore
     *                       error encountered during observation. Defaults to a
     *                       no-op. Use this to forward errors to Crashlytics or
     *                       a similar reporting system.
     * @return A cold [Flow] of [TypingState] updates, executing on [Dispatchers.IO].
     */
    fun observeTyping(
        conversationId: String,
        onError: (Exception) -> Unit = {}
    ): Flow<TypingState> = callbackFlow {
        val currentUserId = auth.currentUser?.uid

        val registration = typingDocument(conversationId)
            .addSnapshotListener { snapshot, error ->

                // ── Error path ────────────────────────────────────────────────
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        close(error)
                        return@addSnapshotListener
                    }
                    // Transient errors: emit a safe empty state and notify the
                    // caller via the optional error callback.
                    onError(error)
                    trySend(TypingState(conversationId, emptyList()))
                    return@addSnapshotListener
                }

                // ── Empty or non-existent document ────────────────────────────
                if (snapshot == null || !snapshot.exists() || snapshot.data.isNullOrEmpty()) {
                    trySend(TypingState(conversationId, emptyList()))
                    return@addSnapshotListener
                }

                // ── Build active typing user list ─────────────────────────────
                val now = System.currentTimeMillis()

                val typingUserIds = snapshot.data!!
                    .entries
                    .filter { (uid, value) ->
                        // Exclude the current user from the emitted list so the
                        // UI never shows the current user as typing to themselves.
                        if (uid == currentUserId) return@filter false

                        // Exclude stale entries whose timestamp has exceeded the
                        // timeout threshold.
                        val timestampMs = (value as? Timestamp)
                            ?.toDate()
                            ?.time
                            ?: return@filter false

                        (now - timestampMs) < TYPING_TIMEOUT_MS
                    }
                    .map { it.key }

                trySend(TypingState(conversationId, typingUserIds))
            }

        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    // One-Shot Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a one-shot snapshot of the current [TypingState] for the given
     * conversation without attaching a persistent listener. This is appropriate
     * for scenarios where a single read is sufficient, such as populating a
     * notification without requiring a live subscription.
     *
     * The same stale-entry filtering applied by [observeTyping] is used here,
     * ensuring consistent behaviour between the live and one-shot APIs.
     *
     * @param conversationId The conversation to query.
     * @return [Result] wrapping a [TypingState], or a failure if the read
     *         could not be completed.
     */
    suspend fun fetchTypingState(conversationId: String): Result<TypingState> =
        withContext(Dispatchers.IO) {
            runCatching {
                val currentUserId = auth.currentUser?.uid
                val snapshot      = typingDocument(conversationId).get().await()

                if (!snapshot.exists() || snapshot.data.isNullOrEmpty()) {
                    return@runCatching TypingState(conversationId, emptyList())
                }

                val now = System.currentTimeMillis()
                val typingUserIds = snapshot.data!!
                    .entries
                    .filter { (uid, value) ->
                        if (uid == currentUserId) return@filter false
                        val timestampMs = (value as? Timestamp)
                            ?.toDate()
                            ?.time
                            ?: return@filter false
                        (now - timestampMs) < TYPING_TIMEOUT_MS
                    }
                    .map { it.key }

                TypingState(conversationId, typingUserIds)
            }
        }
}