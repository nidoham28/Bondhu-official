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
 * Hosts the chat UI for a single conversation.
 *
 * Expects [NavigationHelper.EXTRA_CONVERSATION_ID] in the launching intent.
 * All business logic lives in [ChatViewModel]; this activity only wires
 * the ViewModel to [ChatScreen] and handles back-navigation.
 */
@AndroidEntryPoint
class ChatActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read the conversation ID passed by NavigationHelper.navigateToChat().
        val conversationId = intent.getStringExtra(NavigationHelper.EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }

        if (conversationId == null) {
            // Guard: no ID means we were launched incorrectly — bail out immediately.
            finish()
            return
        }

        // Start streaming messages, peer profile, and presence.
        viewModel.initChat(conversationId)

        setContent {
            AppTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                ChatScreen(
                    uiState      = uiState,
                    onBack       = { finish() },
                    onInputChanged = viewModel::onInputChanged,
                    onSend       = viewModel::sendMessage,
                    isMine       = viewModel::isMine,
                    isReadByPeer = viewModel::isReadByPeer
                )
            }
        }
    }
}