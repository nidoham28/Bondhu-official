package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.participant.User
import com.nidoham.server.manager.ParticipantFilter
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.repository.participant.UserRepository
import com.nidoham.server.util.ParticipantType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Pairs a [Conversation] with its resolved peer [User], which may be null while
 * the profile fetch is in flight or if the peer document does not exist.
 */
data class ConversationWithUser(
    val conversation: Conversation,
    val peerUser: User? = null
)

@HiltViewModel
class MessageViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    /**
     * Always reads the live Firebase Auth state rather than capturing the UID
     * at construction time, which would yield an empty string if the ViewModel
     * is created before auth resolves.
     */
    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid ?: ""

    /**
     * Session-scoped cache of peer UID → [User] profile. Avoids redundant
     * Firestore reads when the conversation list emits due to unrelated changes
     * (e.g. a lastMessage update) while the peer roster is unchanged.
     *
     * [ConcurrentHashMap] is used because [async] blocks inside [coroutineScope]
     * may access the map concurrently from separate coroutines.
     */
    private val peerUserCache = ConcurrentHashMap<String, User>()

    /**
     * Live stream of the current user's personal conversations, each enriched
     * with the resolved peer [User] profile.
     *
     * Peer resolution and user fetches are parallelised across all conversations
     * in each emission via [coroutineScope] and [async], so list rendering is
     * not gated on sequential round-trips. Peer profiles are cached for the
     * ViewModel's lifetime; only newly seen UIDs trigger a network fetch.
     *
     * A failure on any individual peer-user fetch yields a null [peerUser] for
     * that item rather than collapsing the entire list. A terminal stream error
     * is logged and an empty list is emitted so the UI degrades gracefully.
     */
    val conversations: Flow<List<ConversationWithUser>> =
        messageRepository
            .observeCurrentUserConversations(ParticipantType.PERSONAL)
            .map { list -> resolveConversationsWithPeers(list) }
            .catch { e ->
                Timber.e(e, "MessageViewModel: conversation stream error")
                emit(emptyList())
            }

    // ─────────────────────────────────────────────────────────────────────────
    // Private Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun resolveConversationsWithPeers(
        list: List<Conversation>
    ): List<ConversationWithUser> {
        val uid = currentUserId.takeIf { it.isNotBlank() }
            ?: return emptyList()

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
     * Resolves the peer [User] for a given conversation. Returns the cached
     * value immediately if the peer UID has been seen before; otherwise fetches
     * the participant list to identify the peer, then fetches and caches the
     * profile. A failure at any step returns null without propagating.
     *
     * @param parentId   The conversation document ID.
     * @param currentUid The authenticated user's UID, used to exclude self from
     *                   the participant list.
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

        return peerUserCache.getOrPut(peerId) {
            userRepository.fetchUser(peerId)
                .onFailure { e ->
                    Timber.w(e, "MessageViewModel: failed to fetch peer user $peerId")
                }
                .getOrNull()
        }
    }
}