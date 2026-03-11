package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.participant.User
import com.nidoham.server.manager.ParticipantFilter
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.repository.participant.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// UI model — pairs a Conversation with its resolved peer User (null while loading).
data class ConversationWithUser(
    val conversation: Conversation,
    val peerUser: User? = null
)

@HiltViewModel
class MessageViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    // FIX: getCurrentUserId() did not exist on UserRepository.
    //      FirebaseAuth is the authoritative source for the current user's UID.
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    // FIX: getCurrentUserId() did not exist on UserRepository.
    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid ?: ""

    /**
     * Paginated stream of conversations enriched with peer-user data.
     *
     * FIX: ParticipantManager was previously injected directly into the ViewModel,
     *      bypassing the repository layer. All participant access is now routed
     *      through MessageRepository, which owns ParticipantManager internally.
     *
     * FIX: getConversations(uid) did not exist on MessageRepository.
     *      Replaced with fetchConversationsPaged(), the correct paginated API.
     *
     * The peer-user profile fetch is wrapped in runCatching so a transient
     * Firestore error on any single item does not collapse the entire page.
     */
    val conversations: Flow<PagingData<ConversationWithUser>> =
        firebaseAuth.currentUser?.uid
            ?.let { uid ->
                messageRepository.fetchConversationsPaged()
                    .map { pagingData: PagingData<Conversation> ->
                        pagingData.map { conversation: Conversation ->
                            // FIX: ParticipantManager.getParticipants() was called directly.
                            //      Replaced with messageRepository.fetchParticipantsFiltered(),
                            //      which routes through the correct repository boundary.
                            //
                            // FIX: conversation.conversationId does not exist on the
                            //      Conversation domain model. The document ID field is id.
                            val peerId = runCatching {
                                messageRepository.fetchParticipantsFiltered(
                                    id     = conversation.id,
                                    filter = ParticipantFilter()
                                ).getOrNull()
                                    ?.firstOrNull { it.uid != uid }
                                    ?.uid
                            }.getOrNull()

                            // FIX: userRepository.getUserProfile() did not exist.
                            //      Replaced with fetchUserById(), the correct API.
                            //      Wrapped in runCatching so a failure on any single
                            //      item yields null rather than breaking the page.
                            val peerUser: User? = peerId?.let {
                                runCatching { userRepository.fetchUserById(it) }.getOrNull()
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