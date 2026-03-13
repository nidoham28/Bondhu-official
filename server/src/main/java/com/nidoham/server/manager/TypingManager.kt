package com.nidoham.server.manager

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

// ─────────────────────────────────────────────────────────────────────────────
// TypingState
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents the current typing state for a given conversation.
 *
 * @property conversationId The conversation this state belongs to.
 * @property typingUserIds  UIDs of participants currently typing, excluding the
 *                          current user. An empty list means no one is typing.
 */
data class TypingState(
    val conversationId: String,
    val typingUserIds: List<String>
)

// ─────────────────────────────────────────────────────────────────────────────
// TypingManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Manages real-time typing indicators using Firebase Realtime Database.
 *
 * RTDB schema:
 *   typing/{conversationId}/{userId}  →  Long (server timestamp)
 *
 * Each child under a conversation node represents one participant. Setting a
 * user's field to [ServerValue.TIMESTAMP] marks them as typing; removing the
 * field marks them as stopped. Every write registers an [onDisconnect] removal
 * on the server, so indicators are cleaned up automatically if a client
 * disconnects without sending an explicit stop-typing signal. This replaces the
 * client-side stale-timestamp filtering required in a Firestore implementation.
 *
 * A secondary [TYPING_TIMEOUT_MS] guard is retained as a belt-and-suspenders
 * measure for edge cases where the server's [onDisconnect] execution is delayed.
 *
 * All write operations return [Result] so the caller decides how to handle
 * failures. The observer flow emits a safe empty [TypingState] on transient
 * errors rather than crashing the UI.
 *
 * @param database Injectable [FirebaseDatabase] instance.
 * @param auth     Injectable [FirebaseAuth] instance.
 */
class TypingManager(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val TYPING_ROOT = "typing"

        /**
         * Duration in milliseconds after which a typing entry is treated as stale.
         * Align this with the debounce interval used by the UI to send heartbeats.
         * Default: 10 seconds.
         */
        const val TYPING_TIMEOUT_MS = 10_000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // References
    // ─────────────────────────────────────────────────────────────────────────

    private fun conversationRef(conversationId: String): DatabaseReference =
        database.getReference(TYPING_ROOT).child(conversationId)

    private fun userRef(conversationId: String, userId: String): DatabaseReference =
        conversationRef(conversationId).child(userId)

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Records that the currently authenticated user has started or stopped typing.
     *
     * When [typing] is true, the user's field is set to [ServerValue.TIMESTAMP]
     * and an [onDisconnect] removal is registered on the server so the indicator
     * is cleared automatically if the client disconnects unexpectedly. When
     * [typing] is false, the field is removed and the [onDisconnect] handler is
     * cancelled, since no cleanup will be needed.
     *
     * @param conversationId The conversation in which the typing event occurred.
     * @param typing         True if the user is currently typing; false otherwise.
     */
    suspend fun setTyping(
        conversationId: String,
        typing: Boolean
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid
            ?: error("No authenticated user is signed in.")
        val ref = userRef(conversationId, uid)

        if (typing) {
            ref.onDisconnect().removeValue().await()
            ref.setValue(ServerValue.TIMESTAMP).await()
        } else {
            ref.onDisconnect().cancel().await()
            ref.removeValue().await()
        }
    }

    /**
     * Marks the currently authenticated user as no longer typing. Convenience
     * wrapper around [setTyping] with [typing] set to false. Should be called
     * from lifecycle callbacks — e.g. when the user navigates away, the app
     * backgrounds, or the conversation screen is destroyed.
     *
     * @param conversationId The conversation to clear the indicator for.
     */
    suspend fun clearTyping(conversationId: String): Result<Unit> =
        setTyping(conversationId, false)

    /**
     * Removes the typing indicator for a specific [userId] regardless of which
     * user is currently authenticated. Intended for administrative use cases
     * such as clearing a user's indicator after they are removed from a
     * conversation.
     *
     * @param conversationId The conversation to clear the indicator for.
     * @param userId         The UID of the user whose indicator should be removed.
     */
    suspend fun clearTypingForUser(
        conversationId: String,
        userId: String
    ): Result<Unit> = runCatching {
        userRef(conversationId, userId).removeValue().await()
    }

    /**
     * Removes the entire typing node for a conversation, clearing all active
     * indicators simultaneously. Appropriate when a conversation is archived,
     * closed, or when all participants have left.
     *
     * @param conversationId The conversation whose typing node should be deleted.
     */
    suspend fun clearAllTyping(conversationId: String): Result<Unit> = runCatching {
        conversationRef(conversationId).removeValue().await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Real-Time Listener
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a [Flow] that emits an updated [TypingState] whenever the set of
     * actively typing users in the given conversation changes.
     *
     * The current user is excluded from the emitted list. A secondary
     * [TYPING_TIMEOUT_MS] check filters any entries whose timestamp exceeds the
     * threshold, guarding against edge cases where [onDisconnect] execution is
     * delayed by the server.
     *
     * On a [DatabaseError], a safe empty [TypingState] is emitted and the
     * optional [onError] callback is invoked so the caller can forward the
     * error to a reporting system without disrupting the UI. The underlying
     * listener is removed automatically when the flow's collector is cancelled.
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
     * @param onError        Optional callback invoked with non-fatal RTDB errors.
     *                       Defaults to a no-op.
     */
    fun observeTyping(
        conversationId: String,
        onError: (DatabaseError) -> Unit = {}
    ): Flow<TypingState> = callbackFlow {
        val currentUid = auth.currentUser?.uid
        val empty      = TypingState(conversationId, emptyList())

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(empty)
                    return
                }

                val now = System.currentTimeMillis()
                val typingUserIds = snapshot.children
                    .filter { child ->
                        if (child.key == currentUid) return@filter false
                        val timestampMs = child.getValue(Long::class.java) ?: return@filter false
                        (now - timestampMs) < TYPING_TIMEOUT_MS
                    }
                    .mapNotNull { it.key }

                trySend(TypingState(conversationId, typingUserIds))
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error)
                trySend(empty)
            }
        }

        val ref = conversationRef(conversationId)
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // One-Shot Read
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a one-shot snapshot of the current [TypingState] for the given
     * conversation without attaching a persistent listener. Appropriate for
     * scenarios where a single read is sufficient, such as populating a
     * notification payload.
     *
     * The same stale-entry filtering applied by [observeTyping] is used here
     * to ensure consistent behaviour between the live and one-shot APIs.
     *
     * @param conversationId The conversation to query.
     */
    suspend fun fetchTypingState(conversationId: String): Result<TypingState> = runCatching {
        val currentUid = auth.currentUser?.uid
        val snapshot   = conversationRef(conversationId).get().await()

        if (!snapshot.exists()) return@runCatching TypingState(conversationId, emptyList())

        val now = System.currentTimeMillis()
        val typingUserIds = snapshot.children
            .filter { child ->
                if (child.key == currentUid) return@filter false
                val timestampMs = child.getValue(Long::class.java) ?: return@filter false
                (now - timestampMs) < TYPING_TIMEOUT_MS
            }
            .mapNotNull { it.key }

        TypingState(conversationId, typingUserIds)
    }
}