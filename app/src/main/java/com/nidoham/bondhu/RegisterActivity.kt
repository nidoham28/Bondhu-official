package com.nidoham.bondhu

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.auth.RegisterScreen
import com.nidoham.bondhu.presentation.viewmodel.AuthState
import com.nidoham.bondhu.presentation.viewmodel.AuthViewModel
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class RegisterActivity : BaseActivity() {

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                val authState by viewModel.authState.collectAsStateWithLifecycle()

                LaunchedEffect(authState) {
                    when (val state = authState) {
                        is AuthState.Success -> handleSuccess(state)
                        is AuthState.Error -> handleError(state)
                        AuthState.Loading -> {}
                        AuthState.Idle -> {}
                        else -> {}
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    RegisterScreen(
                        modifier = Modifier.padding(paddingValues),
                        isLoading = authState is AuthState.Loading,
                        onRegisterClick = { email, password ->
                            viewModel.signUpWithEmail(email, password)
                        },
                        onGoogleSignUpClick = {
                            viewModel.signInWithGoogle(this@RegisterActivity)
                        },
                        onLoginClick = {
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun handleSuccess(state: AuthState.Success) {
        val user = state.user
        Timber.i("Sign-up success — uid: ${user.uid}")
        Toast.makeText(
            this,
            "Welcome, ${user.displayName ?: user.email ?: "User"}!",
            Toast.LENGTH_SHORT
        ).show()
        setResult(Activity.RESULT_OK)
        NavigationHelper.navigateToUpdateProfile(this)
        finish()
    }

    private fun handleError(state: AuthState.Error) {
        Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
        viewModel.resetAuthState()
    }
}