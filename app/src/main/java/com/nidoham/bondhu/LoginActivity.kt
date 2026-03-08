package com.nidoham.bondhu

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.auth.LoginScreen
import com.nidoham.bondhu.presentation.viewmodel.AuthState
import com.nidoham.bondhu.presentation.viewmodel.AuthViewModel
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class LoginActivity : BaseActivity() {

    private val authViewModel: AuthViewModel by viewModels()
    private val registerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            NavigationHelper.navigateToMain(this)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (authViewModel.isAuthenticated) {
            NavigationHelper.navigateToMain(this)
            finish()
            return
        }

        setContent {
            AppTheme {
                val authState by authViewModel.authState.collectAsStateWithLifecycle()

                LaunchedEffect(authState) {
                    when (val state = authState) {
                        is AuthState.Success -> handleSuccess(state)
                        is AuthState.Error -> handleError(state)
                        AuthState.Loading -> { /* UI handles */ }
                        AuthState.Idle -> { /* Do nothing */ }
                        is AuthState.ActionSuccess -> { /* Not emitted in login flow */ }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    LoginScreen(
                        modifier = Modifier.padding(paddingValues),
                        isLoading = authState is AuthState.Loading,
                        onLoginClick = { email, password ->
                            authViewModel.signInWithEmail(email, password)
                        },
                        onGoogleSignInClick = {
                            authViewModel.signInWithGoogle(this@LoginActivity)
                        },
                        onForgotPasswordClick = {
                            NavigationHelper.navigateToForgotPassword(this@LoginActivity)
                        },
                        onSignUpClick = {
                            registerLauncher.launch(
                                NavigationHelper.registerIntent(this@LoginActivity)
                            )
                        }
                    )
                }
            }
        }
    }

    private fun handleSuccess(state: AuthState.Success) {
        val user = state.user
        Timber.i("Sign-in success — uid: ${user.uid}")
        Toast.makeText(
            this,
            "Welcome, ${user.displayName ?: user.email ?: "User"}!",
            Toast.LENGTH_SHORT
        ).show()

        if (authViewModel.isProfileCompleted) {
            NavigationHelper.navigateToMain(this)
        } else {
            NavigationHelper.navigateToUpdateProfile(this)
        }
        finish()
    }

    private fun handleError(state: AuthState.Error) {
        Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
        authViewModel.resetAuthState()
    }
}