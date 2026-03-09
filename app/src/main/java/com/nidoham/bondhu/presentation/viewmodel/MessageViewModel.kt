package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.nidoham.bondhu.data.repository.message.MessageRepository
import com.nidoham.bondhu.data.repository.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import org.nidoham.server.domain.model.Conversation
import org.nidoham.server.domain.model.User
import javax.inject.Inject

// UI model — pairs a Conversation with its resolved peer User (null while loading)
data class ConversationWithUser(
    val conversation: Conversation,
    val peerUser: User? = null
)

@HiltViewModel
class MessageViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    // FIX: currentUserId comes from UserRepository — MessageRepository has no auth at all
    val currentUserId: String
        get() = userRepository.getCurrentUserId() ?: ""

    /**
     * Paginated stream of conversations enriched with peer-user data.
     *
     * - Uses MessageRepository.getConversations(userId) — the only list API available.
     * - Each page item is mapped to ConversationWithUser by fetching the peer
     *   profile via UserRepository.getUserProfile (suspend, safe inside PagingData.map).
     * - cachedIn keeps the paging state alive across recompositions.
     * - Emits an empty flow when no user is signed in.
     */
    val conversations: Flow<PagingData<ConversationWithUser>> =
        userRepository.getCurrentUserId()
            ?.let { uid ->
                // FIX: real method is getConversations(userId), not observeMyConversations()
                messageRepository.getConversations(uid)
                    .map { pagingData: PagingData<Conversation> ->
                        // PagingData.map supports suspend lambdas — safe to call suspend here
                        pagingData.map { conversation: Conversation ->
                            // FIX: participantsIds (not participantIds) — matches Conversation.kt
                            val peerId = conversation.participantsIds
                                .firstOrNull { it != uid }
                            val peerUser: User? = peerId?.let {
                                userRepository.getUserProfile(it)
                            }
                            ConversationWithUser(
                                conversation = conversation,
                                peerUser     = peerUser
                            )
                        }
                    }
                    .cachedIn(viewModelScope)
            }
            ?: emptyFlow()
}