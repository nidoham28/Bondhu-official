package com.nidoham.bondhu

import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@AndroidEntryPoint
class ForgotPasswordActivity : BaseActivity() {

    @Inject
    lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AppTheme {
                ForgotPasswordScreen(
                    onBackClick = { finish() },
                    onResetClick = { email, onFinally -> sendResetEmail(email, onFinally) }
                )
            }
        }
    }

    // onFinally callback resets isLoading in the composable regardless of success/failure
    private fun sendResetEmail(email: String, onFinally: (success: Boolean) -> Unit) {
        lifecycleScope.launch {
            try {
                auth.sendPasswordResetEmail(email).await()
                Toast.makeText(
                    this@ForgotPasswordActivity,
                    "Password reset email sent to $email",
                    Toast.LENGTH_LONG
                ).show()
                onFinally(true)
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@ForgotPasswordActivity,
                    e.localizedMessage ?: "Failed to send reset email",
                    Toast.LENGTH_LONG
                ).show()
                // Reset loading so the user can correct the email and retry
                onFinally(false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgotPasswordScreen(
    onBackClick: () -> Unit,
    onResetClick: (String, onFinally: (Boolean) -> Unit) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isEmailError by remember { mutableStateOf(false) }

    val isValidEmail = email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reset Password") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enter your email address and we'll send you a link to reset your password.",
                style = MaterialTheme.typography.bodyLarge
            )

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    isEmailError = false
                },
                label = { Text("Email") },
                singleLine = true,
                isError = isEmailError,
                supportingText = {
                    if (isEmailError) Text("Please enter a valid email address")
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (!isValidEmail) {
                        isEmailError = true
                        return@Button
                    }
                    isLoading = true
                    onResetClick(email.trim()) { success ->
                        // Always reset loading — success navigates away, failure lets user retry
                        isLoading = false
                    }
                },
                enabled = email.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Send Reset Link")
                }
            }
        }
    }
}