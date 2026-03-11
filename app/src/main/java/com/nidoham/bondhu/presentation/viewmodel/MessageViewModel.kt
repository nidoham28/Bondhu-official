package com.nidoham.bondhu.presentation.viewmodel

import android.nidoham.server.repository.ParticipantManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.nidoham.bondhu.data.repository.message.MessageRepository
import com.nidoham.bondhu.data.repository.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
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
    private val userRepository: UserRepository,
    // FIX: ParticipantRepository is now required to resolve the peer UID,
    //      since participantsIds was removed from the Conversation document.
    private val participantRepository: ParticipantManager
) : ViewModel() {

    val currentUserId: String
        get() = userRepository.getCurrentUserId() ?: ""

    /**
     * Paginated stream of conversations enriched with peer-user data.
     *
     * Peer UID resolution uses [ParticipantRepository.getParticipants] because
     * the participantsIds array no longer exists on the Conversation document.
     * The profile fetch is wrapped in runCatching so a transient Firestore error
     * on any single item does not collapse the entire page.
     */
    val conversations: Flow<PagingData<ConversationWithUser>> =
        userRepository.getCurrentUserId()
            ?.let { uid ->
                messageRepository.getConversations(uid)
                    .map { pagingData: PagingData<Conversation> ->
                        pagingData.map { conversation: Conversation ->
                            // FIX: resolve peer UID from the participants subcollection.
                            val peerId = participantRepository
                                .getParticipants(conversation.conversationId)
                                .getOrNull()
                                ?.firstOrNull { it.uid != uid }
                                ?.uid

                            // Profile fetch is best-effort — a failure yields null
                            // rather than breaking the page.
                            val peerUser: User? = peerId?.let {
                                runCatching { userRepository.getUserProfile(it) }.getOrNull()
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