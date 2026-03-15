package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.bondhu.presentation.util.ConversationUiState
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.participant.User
import com.nidoham.server.manager.ParticipantFilter
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.repository.participant.UserRepository
import com.nidoham.server.util.ParticipantType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.onSuccess

/**
 * Pairs a [Conversation] with its resolved peer [User].
 *
 * [peerUser] is null while the profile fetch is in-flight, or when
 * the Firestore document for that peer does not exist.
 */
data class ConversationWithUser(
    val conversation: Conversation,
    val peerUser: User? = null
)

/**
 * ViewModel for the conversation list screen.
 *
 * Responsibilities:
 * - Observe the current user's personal conversation stream from Firebase.
 * - Resolve each conversation's peer [User] in parallel, with an in-memory cache
 *   to avoid redundant Firestore reads on unrelated stream updates.
 * - Expose a well-typed [ConversationUiState] so the UI never has to inspect raw
 *   data to decide what to render.
 * - Support pull-to-refresh by re-subscribing the upstream and clearing the cache.
 *
 * @since 1.0 Initial implementation.
 * @since 1.1 Added retry trigger, [isRefreshing] state, and graceful error recovery
 *            via [ConversationUiState.Error.cachedItems].
 */
@HiltViewModel
class MessageViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    // ─────────────────────────────────────────────────────────────────────────
    // Auth
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Always reads live auth state so this is never an empty string when
     * the ViewModel is created before the Firebase auth callback fires.
     */
    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid.orEmpty()

    // ─────────────────────────────────────────────────────────────────────────
    // Refresh
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Signals whether a pull-to-refresh cycle is currently running.
     * The UI uses this to drive the [PullToRefreshBox] indicator independently
     * from [uiState], which keeps the two concerns cleanly separated.
     */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Emitting into this flow re-triggers [flatMapLatest] below, which
     * effectively re-subscribes the Firebase stream from scratch.
     *
     * [replay = 1] ensures the initial subscription fires immediately on
     * collection without waiting for an explicit [refresh] call.
     */
    private val retryTrigger = MutableSharedFlow<Unit>(replay = 1).apply {
        tryEmit(Unit) // seed the first subscription
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Session-scoped cache of peer UID → [User] profile.
     *
     * We deliberately avoid [ConcurrentHashMap.getOrPut] because its lambda
     * is not guaranteed to execute only once under concurrent access. The
     * manual check-then-put pattern used in [resolvePeerUser] is safe: at
     * worst two coroutines race to write the same value, which is harmless
     * for this read-dominant cache.
     */
    private val peerUserCache = ConcurrentHashMap<String, User>()

    /**
     * Holds the last successfully resolved list so that [ConversationUiState.Error]
     * can carry stale data and keep the screen visible on transient failures.
     */
    private var lastKnownItems: List<ConversationWithUser> = emptyList()

    // ─────────────────────────────────────────────────────────────────────────
    // UI State
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Single source of truth for the conversation list screen.
     *
     * Flow graph:
     * ```
     * retryTrigger
     *   └─ flatMapLatest ─> observeCurrentUserConversations
     *        └─ map        ─> resolveConversationsWithPeers
     *             └─ catch  ─> ConversationUiState.Error (with cached items)
     *   └─ stateIn         ─> initialValue = Loading
     * ```
     *
     * [SharingStarted.WhileSubscribed] with a 5 s grace period keeps the
     * upstream alive across configuration changes (rotation) while releasing
     * resources on genuine navigations away from the screen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ConversationUiState> =
        retryTrigger
            .flatMapLatest {
                val isManualRefresh = _isRefreshing.value
                messageRepository
                    .observeCurrentUserConversations(ParticipantType.PERSONAL)
                    .map { list ->
                        val resolved = resolveConversationsWithPeers(list)
                        // Cache the result before emitting so the catch block can read it.
                        lastKnownItems = resolved
                        // Turn off the pull-to-refresh spinner as soon as real data arrives.
                        _isRefreshing.value = false

                        when {
                            resolved.isEmpty() -> ConversationUiState.Empty
                            isManualRefresh -> ConversationUiState.Refreshing(resolved)
                            else -> ConversationUiState.Success(resolved)
                        }
                    }
                    .catch { e ->
                        Timber.e(e, "MessageViewModel: conversation stream error")
                        _isRefreshing.value = false
                        // Pass the last known list so the screen stays populated on
                        // transient failures instead of replacing the list with an error page.
                        emit(
                            ConversationUiState.Error(
                                cause       = e,
                                cachedItems = lastKnownItems.takeIf { it.isNotEmpty() }
                            )
                        )
                    }
            }
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConversationUiState.Loading
            )

    // ─────────────────────────────────────────────────────────────────────────
    // Public Actions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Triggers a pull-to-refresh cycle.
     *
     * Clears the peer-user cache so stale profile data (e.g. changed display
     * names or avatars) is re-fetched on the next resolution pass, then
     * re-emits into [retryTrigger] to restart the upstream Firebase stream.
     *
     * The [_isRefreshing] flag is set immediately so the UI indicator appears
     * without waiting for the first new emission.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            peerUserCache.clear()
            retryTrigger.emit(Unit)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolves peer users for all conversations in [list] concurrently.
     *
     * Each conversation gets its own [async] coroutine so a slow Firestore read
     * for one peer does not delay the resolution of all the others.
     *
     * @param list Raw conversations from the Firebase stream.
     * @return     Paired list ready for the UI. Empty when [currentUserId] is blank.
     */
    private suspend fun resolveConversationsWithPeers(
        list: List<Conversation>
    ): List<ConversationWithUser> {
        val uid = currentUserId.takeIf { it.isNotBlank() } ?: return emptyList()
        return coroutineScope {
            list.map { conversation ->
                async {
                    ConversationWithUser(
                        conversation = conversation,
                        peerUser     = resolvePeerUser(conversation.parentId, uid)
                    )
                }
            }.awaitAll()
        }
    }

    /**
     * Resolves the peer [User] for a single conversation.
     *
     * Fast path: returns the cached profile if the peer UID was seen before,
     * avoiding a redundant Firestore read on every stream update.
     *
     * Slow path: fetches the participant list to identify the peer UID (the
     * participant whose UID differs from [currentUid]), then fetches and caches
     * the full profile. Any failure returns null without propagating upstream,
     * so one bad profile fetch cannot break the entire list.
     *
     * @param parentId   Conversation document ID used to query participants.
     * @param currentUid Authenticated user's UID, used to exclude self from results.
     * @return           The resolved [User], or null on any failure.
     */
    private suspend fun resolvePeerUser(parentId: String, currentUid: String): User? {
        val peerId = messageRepository
            .fetchParticipantsFiltered(
                parentId = parentId,
                filter   = ParticipantFilter(type = ParticipantType.PERSONAL)
            )
            .getOrNull()
            ?.firstOrNull { it.uid != currentUid }
            ?.uid
            ?: return null

        // Fast path — profile already in memory.
        peerUserCache[peerId]?.let { return it }

        // Slow path — fetch from Firestore, cache on success.
        return userRepository.fetchUser(peerId)
            .onSuccess { user -> peerUserCache[peerId] = user as User }
            .onFailure { e -> Timber.w(e, "MessageViewModel: failed to fetch peer $peerId") }
            .getOrNull()
    }
}