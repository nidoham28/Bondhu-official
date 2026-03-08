package com.nidoham.bondhu.presentation.navigation

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import com.nidoham.bondhu.CompleteProfileActivity
import com.nidoham.bondhu.ForgotPasswordActivity
import com.nidoham.bondhu.LoginActivity
import com.nidoham.bondhu.MainActivity
import com.nidoham.bondhu.R
import com.nidoham.bondhu.RegisterActivity

object NavigationHelper {

    // Moved here from CompleteProfileActivity.companion — that class no longer defines it
    private const val EXTRA_USER_ID = "extra_user_id"

    // ─────────────────────────────────────────────────────────────────────────
    // Region: Transitions
    // ─────────────────────────────────────────────────────────────────────────

    private fun fadeTransition(context: Context): ActivityOptions =
        ActivityOptions.makeCustomAnimation(
            context,
            R.anim.fade_in,
            R.anim.fade_out
        )

    private fun slideTransition(context: Context): ActivityOptions =
        ActivityOptions.makeCustomAnimation(
            context,
            R.anim.slide_in_right,
            R.anim.slide_out_left
        )

    // ─────────────────────────────────────────────────────────────────────────
    // Region: Core
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Region: Destinations
    // ─────────────────────────────────────────────────────────────────────────

    fun navigateToMain(context: Context) = launch(
        context    = context,
        intent     = Intent(context, MainActivity::class.java),
        transition = fadeTransition(context),
        clearStack = true
    )

    fun navigateToLogin(context: Context) = launch(
        context    = context,
        intent     = Intent(context, LoginActivity::class.java),
        transition = fadeTransition(context),
        clearStack = true
    )

    /**
     * Returns an Intent for RegisterActivity to be used with registerForActivityResult launcher.
     * LoginActivity must use this — calling navigateToRegister() directly bypasses
     * the launcher's RESULT_OK callback and navigateToMain never fires after registration.
     */
    fun registerIntent(context: Context): Intent =
        Intent(context, RegisterActivity::class.java)

    fun navigateToUpdateProfile(
        context: Context,
        userId: String? = null
    ) {
        val intent = Intent(context, CompleteProfileActivity::class.java).apply {
            userId?.let { putExtra(EXTRA_USER_ID, it) }
        }
        launch(
            context    = context,
            intent     = intent,
            transition = slideTransition(context)
        )
    }

    fun navigateToForgotPassword(context: Context) = launch(
        context    = context,
        intent     = Intent(context, ForgotPasswordActivity::class.java),
        transition = slideTransition(context)
    )
}