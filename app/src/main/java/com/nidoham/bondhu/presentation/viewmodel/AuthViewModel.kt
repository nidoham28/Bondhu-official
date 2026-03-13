package com.nidoham.bondhu.presentation.viewmodel

import android.content.Context
import android.util.Patterns
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import androidx.core.net.toUri
import com.nidoham.server.domain.participant.User
import com.nidoham.server.manager.PresenceManager
import com.nidoham.server.repository.participant.UserRepository
import com.nidoham.server.util.AuthProvider
import org.nidoham.server.data.api.GoogleAuthHelper

sealed interface AuthState {
    data object Idle : AuthState
    data object Loading : AuthState
    data class Success(val user: FirebaseUser) : AuthState
    data class ActionSuccess(val message: String) : AuthState
    data class Error(val message: String) : AuthState
}

/**
 * ViewModel for all authentication flows: email sign-in/up, Google sign-in,
 * profile updates, and account deletion.
 *
 * All Firebase Auth calls are performed in [viewModelScope]; results are
 * surfaced via [authState]. Every write to [UserRepository] checks the
 * returned [Result] and rolls back the Firebase Auth side on failure where
 * appropriate.
 *
 * Note: [PresenceManager] is injected directly here only because [onUserLogout]
 * is not yet exposed through [UserRepository]. Once it is, this dependency
 * should be removed from the ViewModel.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val googleAuthHelper: GoogleAuthHelper,
    private val userRepository: UserRepository,
    private val presenceManager: PresenceManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs = context.getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isAuthenticated: Boolean
        get() = auth.currentUser != null

    val isProfileCompleted: Boolean
        get() {
            val uid = auth.currentUser?.uid ?: return false
            return prefs.getBoolean("profileCompleted_$uid", false)
        }

    init {
        auth.currentUser?.let { _authState.value = AuthState.Success(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sign-In
    // ─────────────────────────────────────────────────────────────────────────

    fun signInWithEmail(email: String, password: String) {
        if (!validateCredentials(email, password)) return

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
                val user = result.user ?: throw Exception("User was null after sign-in")
                Timber.i("Email sign-in — uid: ${user.uid}")
                _authState.value = AuthState.Success(user)
            } catch (e: Exception) {
                Timber.e(e, "Email sign-in failed")
                _authState.value = AuthState.Error(e.localizedMessage ?: "Sign-in failed")
            }
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        if (!validateCredentials(email, password, requireMinPasswordLength = true)) return

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
                val firebaseUser = result.user ?: throw Exception("User was null after sign-up")

                val domainUser = firebaseUser.toDomainModel(
                    username = generateUsernameFromEmail(firebaseUser.email ?: "user"),
                    provider = AuthProvider.PASSWORD.value
                )
                userRepository.createUser(domainUser).getOrElse { error ->
                    firebaseUser.delete().await()
                    throw Exception(error.message ?: "Registration failed")
                }

                Timber.i("Email sign-up — uid: ${firebaseUser.uid}")
                _authState.value = AuthState.Success(firebaseUser)
            } catch (e: Exception) {
                Timber.e(e, "Email sign-up failed")
                _authState.value = AuthState.Error(e.localizedMessage ?: "Registration failed")
            }
        }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                googleAuthHelper.getIdToken(context)
                    .onSuccess { idToken ->
                        val credential = GoogleAuthProvider.getCredential(idToken, null)
                        val result = auth.signInWithCredential(credential).await()
                        val firebaseUser = result.user
                            ?: throw Exception("User was null after Google sign-in")

                        if (result.additionalUserInfo?.isNewUser == true) {
                            val domainUser = firebaseUser.toDomainModel(
                                username = generateGoogleUsername(firebaseUser),
                                provider = AuthProvider.GOOGLE.value
                            )
                            userRepository.createUser(domainUser).getOrElse { error ->
                                firebaseUser.delete().await()
                                throw Exception(error.message ?: "Failed to create user profile")
                            }
                        }

                        Timber.i("Google sign-in — uid: ${firebaseUser.uid}")
                        _authState.value = AuthState.Success(firebaseUser)
                    }
                    .onFailure { error ->
                        Timber.e(error, "Failed to obtain Google ID token")
                        _authState.value = AuthState.Error(
                            error.localizedMessage ?: "Google sign-in cancelled"
                        )
                    }
            } catch (e: Exception) {
                Timber.e(e, "Firebase Google auth failed")
                _authState.value = AuthState.Error(e.localizedMessage ?: "Google sign-in failed")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Profile
    // ─────────────────────────────────────────────────────────────────────────

    fun updateProfile(displayName: String? = null, photoUrl: String? = null) {
        val user = auth.currentUser
        if (user == null) {
            _authState.value = AuthState.Error("No authenticated user found")
            return
        }
        if (displayName == null && photoUrl == null) {
            _authState.value = AuthState.Error("Nothing to update")
            return
        }

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                val profileUpdates = UserProfileChangeRequest.Builder().apply {
                    displayName?.let { setDisplayName(it) }
                    photoUrl?.let { photoUri = it.toUri() }
                }.build()
                user.updateProfile(profileUpdates).await()

                val fields = buildMap<String, Any> {
                    displayName?.let { put("displayName", it) }
                    photoUrl?.let { put("photoUrl", it) }
                }
                userRepository.updateUser(user.uid, fields).getOrElse { error ->
                    throw Exception(error.message ?: "Firestore profile update failed")
                }

                setProfileCompleted(true)
                user.reload().await()

                val refreshedUser = auth.currentUser
                    ?: throw Exception("User was null after profile reload")
                Timber.i("Profile updated — uid: ${user.uid}")
                _authState.value = AuthState.Success(refreshedUser)
            } catch (e: Exception) {
                Timber.e(e, "Profile update failed")
                _authState.value = AuthState.Error(e.localizedMessage ?: "Profile update failed")
            }
        }
    }

    fun setProfileCompleted(completed: Boolean) {
        val key = getProfileKey()
        prefs.edit { putBoolean(key, completed) }
        Timber.d("Profile completed set to $completed for key: $key")
    }

    private fun getProfileKey(): String {
        val uid = auth.currentUser?.uid ?: return "profileCompleted_null"
        return "profileCompleted_$uid"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Account Management
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("unused")
    fun deleteAccount() {
        val user = auth.currentUser
        if (user == null) {
            _authState.value = AuthState.Error("No user logged in")
            return
        }

        // Capture uid before deletion — after user.delete(), auth.currentUser
        // becomes null and getProfileKey() would target the wrong prefs key.
        val uidToDelete = user.uid
        presenceManager.onUserLogout()

        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                // Soft-delete via the correct repository path rather than a raw field map,
                // so any future logic inside banUser (audit logging, cascades) is respected.
                userRepository.banUser(uidToDelete).onFailure { error ->
                    Timber.w(error, "Soft-delete failed for uid: $uidToDelete — proceeding with hard delete")
                }

                user.delete().await()
                prefs.edit { putBoolean("profileCompleted_$uidToDelete", false) }

                Timber.i("Account deleted — uid: $uidToDelete")
                _authState.value = AuthState.ActionSuccess("Account deleted successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete account — may require re-authentication")
                _authState.value = AuthState.Error(
                    e.localizedMessage ?: "Failed to delete account. Please sign in again and retry."
                )
            }
        }
    }

    @Suppress("unused")
    fun signOut() {
        Timber.i("Signing out — uid: ${auth.currentUser?.uid}")
        presenceManager.onUserLogout()
        auth.signOut()
        _authState.value = AuthState.Idle
    }

    fun resetAuthState() {
        if (_authState.value is AuthState.Error || _authState.value is AuthState.ActionSuccess) {
            _authState.value = auth.currentUser?.let { AuthState.Success(it) } ?: AuthState.Idle
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates [email] format and [password] non-blankness. When
     * [requireMinPasswordLength] is true, also enforces a 6-character minimum,
     * which is Firebase Auth's hard requirement for email/password sign-up.
     */
    private fun validateCredentials(
        email: String,
        password: String,
        requireMinPasswordLength: Boolean = false
    ): Boolean {
        if (email.isBlank() || password.isBlank()) {
            _authState.value = AuthState.Error("Email and password cannot be empty")
            return false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            _authState.value = AuthState.Error("Please enter a valid email address")
            return false
        }
        if (requireMinPasswordLength && password.length < 6) {
            _authState.value = AuthState.Error("Password must be at least 6 characters")
            return false
        }
        return true
    }

    private fun generateUsernameFromEmail(email: String): String {
        val base = email.substringBefore("@")
            .replace(".", "_")
            .replace("-", "_")
            .lowercase()
            .take(15)
        return "${base}_${(1000..9999).random()}"
    }

    private fun generateGoogleUsername(user: FirebaseUser): String {
        val base = user.displayName?.replace(" ", "")?.lowercase()
            ?: user.email?.substringBefore("@")
            ?: "user"
        return "$base${UUID.randomUUID().toString().take(5)}"
    }

    /**
     * Maps a [FirebaseUser] to the [User] domain model at registration time.
     *
     * [createdAt] and [updatedAt] are intentionally left null — both fields
     * carry [@ServerTimestamp] and must be populated by Firestore on the first
     * write, not by the client clock.
     */
    private fun FirebaseUser.toDomainModel(username: String, provider: String): User = User(
        uid           = uid,
        username      = username,
        displayName   = displayName ?: "Unknown",
        provider      = provider,
        email         = email,
        phoneNumber   = phoneNumber,
        photoUrl      = photoUrl?.toString(),
        status        = "Hey there! I am using Bondhu",
        createdAt     = null, // populated server-side via @ServerTimestamp
        updatedAt     = null  // populated server-side via @ServerTimestamp
    )
}