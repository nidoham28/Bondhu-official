package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.server.domain.message.Conversation
import com.nidoham.server.domain.participant.User
import com.nidoham.server.manager.ParticipantFilter
import com.nidoham.server.repository.message.MessageRepository
import com.nidoham.server.repository.participant.UserRepository
import com.nidoham.server.util.ParticipantType
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
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    val currentUserId: String
        get() = firebaseAuth.currentUser?.uid ?: ""

    /**
     * Live stream of the current user's conversations, each enriched with the
     * resolved peer-user profile.
     *
     * FIX: fetchConversationsPaged() was used previously but queries all
     *      conversations globally, ignoring participant membership. Replaced
     *      with observeCurrentUserConversations(), which filters to only the
     *      conversations the authenticated user has joined via a collection
     *      group query on the participant sub-collection.
     *
     * FIX: fetchParticipantsFiltered(id = ...) was a compile error — the
     *      parameter was renamed to parentId in the repository.
     *
     * FIX: runCatching { userRepository.fetchUserById(it) }.getOrNull()
     *      double-wrapped the Result, yielding Result<User?> instead of User?.
     *      Replaced with a direct .getOrNull() call on the returned Result.
     *
     * FIX: ParticipantFilter() passed no type constraint. A PERSONAL type
     *      filter is applied to match the conversation context correctly.
     *
     * A transient failure on any single peer-user fetch yields null for that
     * item rather than collapsing the entire list.
     */
    val conversations: Flow<List<ConversationWithUser>> =
        firebaseAuth.currentUser?.uid
            ?.let { uid ->
                // fixed: observeCurrentUserConversations() scopes to the current
                //        user's participant membership rather than all conversations.
                messageRepository.observeCurrentUserConversations(ParticipantType.PERSONAL)
                    .map { list: List<Conversation> ->
                        list.map { conversation: Conversation ->
                            val peerId = runCatching {
                                messageRepository.fetchParticipantsFiltered(
                                    parentId = conversation.id,           // fixed: was id =
                                    filter   = ParticipantFilter(
                                        type = ParticipantType.PERSONAL   // fixed: no type was set
                                    )
                                ).getOrNull()
                                    ?.firstOrNull { it.uid != uid }
                                    ?.uid
                            }.getOrNull()

                            // fixed: removed outer runCatching wrapper — fetchUserById already
                            //        returns Result<User?>, so .getOrNull() is sufficient.
                            val peerUser: User? = peerId?.let {
                                userRepository.fetchUserById(it)
                            }

                            ConversationWithUser(
                                conversation = conversation,
                                peerUser     = peerUser
                            )
                        }
                    }
            }
            ?: emptyFlow()
}