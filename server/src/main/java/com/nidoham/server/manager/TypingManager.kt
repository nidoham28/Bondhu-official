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

/**
 * Represents the real-time typing state for a conversation.
 *
 * @property conversationId The ID of the conversation.
 * @property typingUserIds A list of user IDs currently typing, excluding the current user.
 */
data class TypingState(
    val conversationId: String,
    val typingUserIds: List<String>
)

/**
 * Manages real-time typing indicators using Firebase Realtime Database.
 *
 * **Database Schema:**
 * ```
 * typing/{conversationId}/{userId} : Long (Server Timestamp)
 * ```
 *
 * **Mechanism:**
 * - **Start Typing:** Writes a server timestamp to the user's node.
 * - **Stop Typing:** Removes the user's node.
 * - **Cleanup:** Uses `onDisconnect` to automatically remove the indicator if the client disconnects unexpectedly.
 *
 * **Staleness Guard:**
 * A client-side timeout ([TYPING_TIMEOUT_MS]) filters out entries that exceed the threshold,
 * handling edge cases where `onDisconnect` execution might be delayed.
 *
 * @param database Injectable [FirebaseDatabase] instance.
 * @param auth Injectable [FirebaseAuth] instance.
 */
class TypingManager(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val TYPING_ROOT = "typing"

        /**
         * Threshold in milliseconds to treat a typing entry as stale.
         * Defaults to 10 seconds.
         */
        const val TYPING_TIMEOUT_MS = 10_000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reference Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun conversationRef(conversationId: String): DatabaseReference =
        database.getReference(TYPING_ROOT).child(conversationId)

    private fun userRef(conversationId: String, userId: String): DatabaseReference =
        conversationRef(conversationId).child(userId)

    // ─────────────────────────────────────────────────────────────────────────
    // Write Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates the typing status for a specific user in a conversation.
     *
     * @param conversationId The target conversation.
     * @param userId The user whose status is changing.
     * @param isTyping True if typing, false if stopped.
     * @return [Result] indicating success or failure.
     */
    suspend fun setTyping(
        conversationId: String,
        userId: String,
        isTyping: Boolean
    ): Result<Unit> = runCatching {
        val ref = userRef(conversationId, userId)

        if (isTyping) {
            // Ensure the indicator is removed on disconnect
            ref.onDisconnect().removeValue().await()
            // Set current timestamp
            ref.setValue(ServerValue.TIMESTAMP).await()
        } else {
            // Cancel the disconnect handler since we are explicitly stopping
            ref.onDisconnect().cancel().await()
            ref.removeValue().await()
        }
    }

    /**
     * Clears the typing indicator for the given user. Convenience wrapper for [setTyping].
     */
    suspend fun clearTyping(conversationId: String, userId: String): Result<Unit> =
        setTyping(conversationId, userId, isTyping = false)

    /**
     * Clears the typing indicator for a specific user. Useful for admin operations.
     */
    suspend fun clearTypingForUser(conversationId: String, userId: String): Result<Unit> = runCatching {
        userRef(conversationId, userId).removeValue().await()
    }

    /**
     * Removes the entire typing node for a conversation.
     * Use when a conversation is deleted or archived.
     */
    suspend fun clearAllTyping(conversationId: String): Result<Unit> = runCatching {
        conversationRef(conversationId).removeValue().await()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Operations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Observes typing activity in a conversation.
     *
     * Automatically excludes the current authenticated user and filters out stale entries
     * older than [TYPING_TIMEOUT_MS].
     *
     * @param conversationId The conversation to observe.
     * @param onError Callback for database errors (does not stop the flow).
     * @return A [Flow] emitting [TypingState] updates.
     */
    fun observeTyping(
        conversationId: String,
        onError: (DatabaseError) -> Unit = {}
    ): Flow<TypingState> = callbackFlow {
        val currentUserId = auth.currentUser?.uid
        val emptyState = TypingState(conversationId, emptyList())

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(emptyState)
                    return
                }

                val now = System.currentTimeMillis()
                val activeUserIds = snapshot.children
                    .mapNotNull { child ->
                        // Filter logic:
                        // 1. Exclude current user.
                        // 2. Validate timestamp exists.
                        // 3. Check staleness.
                        if (child.key == currentUserId) return@mapNotNull null

                        val timestamp = child.getValue(Long::class.java) ?: return@mapNotNull null

                        if (now - timestamp < TYPING_TIMEOUT_MS) child.key else null
                    }

                trySend(TypingState(conversationId, activeUserIds))
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error)
                // Emit empty state to reset UI gracefully
                trySend(emptyState)
            }
        }

        val ref = conversationRef(conversationId)
        ref.addValueEventListener(listener)

        awaitClose { ref.removeEventListener(listener) }
    }

    /**
     * Fetches a one-time snapshot of the typing state.
     */
    suspend fun fetchTypingState(conversationId: String): Result<TypingState> = runCatching {
        val currentUserId = auth.currentUser?.uid
        val snapshot = conversationRef(conversationId).get().await()

        if (!snapshot.exists()) return@runCatching TypingState(conversationId, emptyList())

        val now = System.currentTimeMillis()
        val activeUserIds = snapshot.children
            .mapNotNull { child ->
                if (child.key == currentUserId) return@mapNotNull null
                val timestamp = child.getValue(Long::class.java) ?: return@mapNotNull null
                if (now - timestamp < TYPING_TIMEOUT_MS) child.key else null
            }

        TypingState(conversationId, activeUserIds)
    }
}