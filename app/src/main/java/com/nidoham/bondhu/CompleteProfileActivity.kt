package com.nidoham.bondhu

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nidoham.bondhu.presentation.navigation.NavigationHelper
import com.nidoham.bondhu.presentation.screen.auth.CompleteProfileScreen
import com.nidoham.bondhu.presentation.viewmodel.AuthState
import com.nidoham.bondhu.presentation.viewmodel.AuthViewModel
import com.nidoham.bondhu.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nidoham.server.util.ImageCompressor
import org.nidoham.server.util.ImgBBStorage
import timber.log.Timber

@AndroidEntryPoint
class CompleteProfileActivity : BaseActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (authViewModel.isProfileCompleted) {
            Timber.d("Profile already completed, skipping to MainActivity")
            NavigationHelper.navigateToMain(this)
            finish()
            return
        }

        enableEdgeToEdge()

        setContent {
            AppTheme {
                CompleteProfileContent()
            }
        }
    }

    @Composable
    private fun CompleteProfileContent() {
        val scope = rememberCoroutineScope()
        val authState: AuthState by authViewModel.authState.collectAsStateWithLifecycle()
        val currentUser = authViewModel.currentUser

        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(authState) {
            when (authState) {
                is AuthState.Loading -> isLoading = true

                is AuthState.ActionSuccess -> {
                    isLoading = false
                    Timber.i("Profile saved — transitioning to Success")
                    authViewModel.resetAuthState()
                }

                is AuthState.Success -> {
                    isLoading = false
                    if (authViewModel.isProfileCompleted) {
                        NavigationHelper.navigateToMain(this@CompleteProfileActivity)
                        finish()
                    }
                }

                is AuthState.Error -> {
                    isLoading = false
                    errorMessage = (authState as AuthState.Error).message
                }

                else -> isLoading = false
            }
        }

        CompleteProfileScreen(
            modifier = Modifier.fillMaxSize(),
            currentDisplayName = currentUser?.displayName.orEmpty(),
            currentPhotoUrl = currentUser?.photoUrl?.toString(),
            isLoading = isLoading,
            errorMessage = errorMessage,
            onComplete = { displayName, photoUri ->
                scope.launch {
                    isLoading = true

                    val photoUrl = photoUri?.let { uri ->
                        uploadWithCompression(uri, currentUser?.uid ?: "")
                    }

                    authViewModel.updateProfile(
                        displayName = displayName,
                        photoUrl = photoUrl
                    )
                }
            },
            onErrorConsumed = {
                errorMessage = null
                authViewModel.resetAuthState()
            }
        )
    }

    private suspend fun uploadWithCompression(uri: Uri, uid: String): String? {
        return try {
            val compressor = ImageCompressor(this)

            val compressed: ImageCompressor.CompressionResult = withContext(Dispatchers.IO) {
                compressor.compress(uri)
            }

            Timber.d("Compressed: ${compressed.originalSize} → ${compressed.compressedSize} bytes (quality: ${compressed.quality})")

            val fileName = "avatar_${uid}_${System.currentTimeMillis()}.jpg"
            val result = withContext(Dispatchers.IO) {
                ImgBBStorage.upload(
                    file = compressed.file,
                    path = "profile_images/$uid/$fileName"
                )
            }

            compressed.file.delete()
            compressor.cleanup()

            Timber.i("ImgBB upload succeeded: ${result.displayUrl}")
            result.displayUrl

        } catch (e: Exception) {
            Timber.e(e, "Upload failed for uid=$uid")
            null
        }
    }
}