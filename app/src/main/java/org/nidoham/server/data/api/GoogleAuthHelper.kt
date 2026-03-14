package org.nidoham.server.data.api

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.nidoham.bondhu.R
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleAuthHelper @Inject constructor(
    private val credentialManager: CredentialManager
) {

    /**
     * Entry point. Attempts silent sign-in with a previously authorized account first.
     * Falls back to the full account picker if none is found.
     *
     * @param activityContext MUST be an Activity context to show the bottom sheet UI.
     */
    suspend fun getIdToken(activityContext: Context): Result<String> {
        return try {
            // 1. Try silent sign-in first (Authorized accounts only)
            val token = fetchGoogleIdToken(activityContext, filterByAuthorizedAccounts = true)
            Timber.d("Silent sign-in successful")
            Result.success(token)

        } catch (e: NoCredentialException) {
            Timber.d("No pre-authorised account — falling back to account picker")

            // 2. Fallback to UI Account Picker
            try {
                val token = fetchGoogleIdToken(activityContext, filterByAuthorizedAccounts = false)
                Timber.d("Account picker sign-in successful")
                Result.success(token)

            } catch (e: GetCredentialCancellationException) {
                Timber.i("Account picker cancelled by user")
                Result.failure(Exception("Sign-in cancelled by user"))
            } catch (e: Exception) {
                Timber.e(e, "Account picker failed")
                Result.failure(Exception(e.message ?: "Google sign-in failed"))
            }

        } catch (e: GetCredentialCancellationException) {
            Timber.i("Silent sign-in cancelled")
            Result.failure(Exception("Sign-in cancelled by user"))
        } catch (e: Exception) {
            Timber.e(e, "Unexpected Google sign-in error")
            Result.failure(e)
        }
    }

    private suspend fun fetchGoogleIdToken(
        context: Context,
        filterByAuthorizedAccounts: Boolean
    ): String {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(context.getString(R.string.default_web_client_id))
            .setAutoSelectEnabled(filterByAuthorizedAccounts)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        // Throws natively if it fails (e.g. NoCredentialException)
        val response = credentialManager.getCredential(context, request)

        if (response.credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw Exception("Unexpected credential type: ${response.credential.type}")
        }

        // Parse the raw credential
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(response.credential.data)

        return googleIdTokenCredential.idToken
    }
}