package com.nidoham.bondhu.presentation.navigation

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import com.nidoham.bondhu.ChatActivity
import com.nidoham.bondhu.CompleteProfileActivity
import com.nidoham.bondhu.ForgotPasswordActivity
import com.nidoham.bondhu.LoginActivity
import com.nidoham.bondhu.MainActivity
import com.nidoham.bondhu.PlayerActivity
import com.nidoham.bondhu.ProfileActivity
import com.nidoham.bondhu.R
import com.nidoham.bondhu.RegisterActivity
import com.nidoham.bondhu.SearchActivity

object NavigationHelper {

    // Intent extras
    const val EXTRA_USER_ID = "extra_user_id"
    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    const val EXTRA_TARGET_ID = "extra_target_id"
    const val EXTRA_TARGET_AI = "extra_target_ai"
    const val EXTRA_STREAM_URL = "extra_stream_url"
    const val EXTRA_TITLE = "extra_title"

    private fun fadeTransition(context: Context): ActivityOptions =
        ActivityOptions.makeCustomAnimation(context, R.anim.fade_in, R.anim.fade_out)

    private fun slideTransition(context: Context): ActivityOptions =
        ActivityOptions.makeCustomAnimation(context, R.anim.slide_in_right, R.anim.slide_out_left)

    private fun launch(
        context: Context,
        intent: Intent,
        transition: ActivityOptions,
        clearStack: Boolean = false
    ) {
        if (clearStack) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent, transition.toBundle())
    }

    fun navigateToMain(context: Context) = launch(
        context = context,
        intent = Intent(context, MainActivity::class.java),
        transition = fadeTransition(context),
        clearStack = true
    )

    fun navigateToLogin(context: Context) = launch(
        context = context,
        intent = Intent(context, LoginActivity::class.java),
        transition = fadeTransition(context),
        clearStack = true
    )

    fun registerIntent(context: Context): Intent =
        Intent(context, RegisterActivity::class.java)

    fun navigateToUpdateProfile(context: Context, userId: String? = null) {
        val intent = Intent(context, CompleteProfileActivity::class.java).apply {
            userId?.let { putExtra(EXTRA_USER_ID, it) }
        }
        launch(context = context, intent = intent, transition = slideTransition(context))
    }

    fun navigateToForgotPassword(context: Context) = launch(
        context = context,
        intent = Intent(context, ForgotPasswordActivity::class.java),
        transition = slideTransition(context)
    )

    /**
     * Opens [ChatActivity].
     * Can be opened via an existing [conversationId] OR by providing a [targetUid] for a new/existing chat.
     */
    fun navigateToChat(
        context: Context,
        conversationId: String? = null,
        targetUid: String? = null,
        ai: Boolean = false
    ) {
        // Validation: Ensure at least one ID is provided to open the chat
        require(!conversationId.isNullOrBlank() || !targetUid.isNullOrBlank()) {
            "Either conversationId or targetUid must be provided"
        }

        val intent = Intent(context, ChatActivity::class.java).apply {
            conversationId?.takeIf { it.isNotBlank() }?.let {
                putExtra(EXTRA_CONVERSATION_ID, it)
            }
            targetUid?.takeIf { it.isNotBlank() }?.let {
                putExtra(EXTRA_TARGET_ID, it)
            }
            putExtra(EXTRA_TARGET_AI, ai)
        }
        launch(context = context, intent = intent, transition = slideTransition(context))
    }

    fun navigateToPlayer(context: Context, streamUrl: String, title: String? = null) {
        require(streamUrl.isNotBlank()) { "streamUrl must not be blank" }
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_STREAM_URL, streamUrl)
            title?.let { putExtra(EXTRA_TITLE, it) }
        }
        launch(context = context, intent = intent, transition = slideTransition(context))
    }

    fun navigateToSearch(context: Context) = launch(
        context = context,
        intent = Intent(context, SearchActivity::class.java),
        transition = slideTransition(context)
    )

    fun navigateToProfile(context: Context, userId: String) {
        val intent = Intent(context, ProfileActivity::class.java).apply {
            putExtra(EXTRA_USER_ID, userId)
        }
        launch(context = context, intent = intent, transition = slideTransition(context))
    }
}