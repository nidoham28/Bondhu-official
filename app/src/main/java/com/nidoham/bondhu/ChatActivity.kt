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
import timber.log.Timber

@AndroidEntryPoint
class ChatActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Extract Data from Intent
        val conversationId = intent.getStringExtra(NavigationHelper.EXTRA_CONVERSATION_ID)
            ?.takeIf { it.isNotBlank() }

        val targetId = intent.getStringExtra(NavigationHelper.EXTRA_TARGET_ID)
            ?.takeIf { it.isNotBlank() }

        val targetAI = intent.getStringExtra(NavigationHelper.EXTRA_TARGET_AI)

        // 2. Validate Critical Data
        if (conversationId == null) {
            Timber.e("ChatActivity: Missing EXTRA_CONVERSATION_ID. Finishing.")
            finish()
            return
        }

        // 3. Log Activity Startup Info
        Timber.i("======================================================")
        Timber.i("ChatActivity Started")
        Timber.i("Conversation ID: $conversationId")
        Timber.i("Target ID (AI/Peer): $targetId")
        Timber.i("======================================================")

        // 4. Initialize ViewModel
        // We pass the targetId (if exists) directly to initChat to ensure
        // the state is ready before streams begin.
        viewModel.initChat(conversationId = conversationId, targetId = targetId, targetAI = targetAI as Boolean?)

        // 5. Set Content
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