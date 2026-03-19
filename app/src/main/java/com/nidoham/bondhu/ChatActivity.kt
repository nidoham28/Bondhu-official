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
import com.nidoham.server.api.API
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts the chat UI for a single conversation.
 *
 * Expects [NavigationHelper.EXTRA_CONVERSATION_ID] in the launching intent.
 * When [NavigationHelper.EXTRA_TARGET_ID] is also present the conversation is
 * treated as an AI chat and [ChatViewModel.configureAi] is called before the
 * first render.
 *
 * All business logic lives in [ChatViewModel]; this activity only wires the
 * ViewModel to [ChatScreen] and handles back-navigation.
 */
@AndroidEntryPoint
class ChatActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val conversationId = intent.getStringExtra(NavigationHelper.EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }
        val targetUid      = intent.getStringExtra(NavigationHelper.EXTRA_TARGET_ID)
            ?.takeIf { it.isNotBlank() }

        if (conversationId == null) {
            finish()
            return
        }

        viewModel.initChat(conversationId)

        // Arm AI mode when a target UID is present.
        if (targetUid != null) {
            viewModel.configureAi(
                targetId = targetUid,
                apiKey   = API.apiKey,
            )
        }

        setContent {
            AppTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                ChatScreen(
                    uiState        = uiState,
                    onBack         = { finish() },
                    onInputChanged = viewModel::onInputChanged,
                    onSend         = viewModel::sendMessage,
                    isMine         = viewModel::isMine,
                    isReadByPeer   = viewModel::isReadByPeer,
                )
            }
        }
    }
}