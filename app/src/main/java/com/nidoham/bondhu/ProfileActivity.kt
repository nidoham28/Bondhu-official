package com.nidoham.bondhu

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nidoham.bondhu.presentation.screen.ProfileScreen
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ProfileActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Bondhu)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val userId = intent.getStringExtra("USER_ID") ?: ""

        setContent {
            AppTheme {
                ProfileScreen(
                    profileUserId = userId.ifBlank { null },
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}