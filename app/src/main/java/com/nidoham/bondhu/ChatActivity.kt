package com.nidoham.bondhu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.chat.ChatScreen
import com.nidoham.bondhu.presentation.viewmodel.ChatViewModel
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Host activity for the one-to-one chat screen.
 *
 * Expects [NavigationHelper.EXTRA_CONVERSATION_ID] in the launching intent.
 * If [NavigationHelper.EXTRA_TARGET_ID] is present, the conversation is treated
 * as an AI chat, and the ViewModel is configured accordingly.
 */
@AndroidEntryPoint
class ChatActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract and validate required Conversation ID
        val conversationId = intent.getStringExtra(NavigationHelper.EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }

        if (conversationId == null) {
            // Invalid state, cannot proceed without a conversation ID
            finish()
            return
        }

        // Extract optional AI User ID (Target ID)
        val aiUserId = intent.getStringExtra(NavigationHelper.EXTRA_TARGET_ID)
            ?.takeIf { it.isNotBlank() }

        // Initialize the chat session
        viewModel.initChat(conversationId)

        // Configure AI mode if an AI User ID is provided
        aiUserId?.let { viewModel.configureAi(it) }

        setContent {
            AppTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                ChatScreen(
                    uiState = uiState,
                    onBack = { finish() },
                    onInputChanged = viewModel::onInputChanged,
                    onSend = viewModel::sendMessage,
                    isMine = viewModel::isMine,
                    isReadByPeer = viewModel::isReadByPeer,
                )
            }
        }
    }
}