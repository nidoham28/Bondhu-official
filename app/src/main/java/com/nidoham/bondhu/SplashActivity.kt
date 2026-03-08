package com.nidoham.bondhu

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.splash.SplashScreen
import com.nidoham.bondhu.ui.theme.AppTheme

class SplashActivity : BaseActivity() {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Bondhu)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                SplashScreen(onTimeout = {
                    checkAuthAndNavigate()
                })
            }
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