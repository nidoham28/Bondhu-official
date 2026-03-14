package com.nidoham.bondhu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.splash.SplashScreen
import com.nidoham.bondhu.ui.theme.AppTheme

class SplashActivity : BaseActivity() {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // 1. Define the permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether the user grants or denies, we proceed to check auth and navigate.
        // We do not block the user flow if they deny notifications.
        checkAuthAndNavigate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Bondhu)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                SplashScreen(onTimeout = {
                    // 2. Trigger permission check when splash timer ends
                    checkNotificationPermission()
                })
            }
        }
    }

    private fun checkNotificationPermission() {
        // Notification permission is only required for Android 13 (Tiramisu) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Permission already granted, proceed
                checkAuthAndNavigate()
            } else {
                // Request the permission
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Android 12 or lower, permission is auto-granted at install time
            checkAuthAndNavigate()
        }
    }

    private fun checkAuthAndNavigate() {
        val currentUser = auth.currentUser
        val sp = getSharedPreferences("MyPrefs", MODE_PRIVATE)

        if (currentUser != null) {
            val uid = currentUser.uid
            val profileCompleted = sp.getBoolean("profileCompleted_$uid", false)

            if (!profileCompleted) {
                NavigationHelper.navigateToUpdateProfile(this)
            } else {
                NavigationHelper.navigateToMain(this)
            }
        } else {
            NavigationHelper.navigateToLogin(this)
        }
        finish()
    }
}