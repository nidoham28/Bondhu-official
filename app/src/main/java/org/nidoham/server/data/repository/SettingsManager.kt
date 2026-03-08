package org.nidoham.server.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.nidoham.server.domain.model.Settings
import javax.inject.Inject

/**
 * Manages read/write operations for user [Settings] in Firestore.
 *
 * **Firestore structure:**
 * ```
 * settings/{uid}  → Settings
 * ```
 */
@Suppress("unused")
class SettingsManager @Inject constructor(
    private val db: FirebaseFirestore
) {
    private fun settingsRef(uid: String) =
        db.collection(SETTINGS_COLLECTION).document(uid)

    /**
     * Writes default [Settings] for a newly registered user.
     * Should be called once during the registration flow.
     */
    suspend fun createDefaultSettings(uid: String): Result<Unit> =
        runCatching { settingsRef(uid).set(Settings()).await() }

    /** Returns the current [Settings] for [uid], or `null` on failure. */
    suspend fun getSettings(uid: String): Settings? =
        runCatching {
            settingsRef(uid).get().await().toObject(Settings::class.java)
        }.getOrNull()

    /**
     * Emits [Settings] in real time whenever the Firestore document changes.
     * Prefer this over [getSettings] on the Settings screen.
     */
    fun observeSettings(uid: String): Flow<Settings?> = callbackFlow {
        val listener = settingsRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            trySend(snapshot?.toObject(Settings::class.java))
        }
        awaitClose { listener.remove() }
    }

    /**
     * Partially updates [Settings] for [uid].
     * Only the fields present in [updates] are written; all others remain unchanged.
     */
    suspend fun updateSettings(uid: String, updates: Map<String, Any>): Result<Unit> =
        runCatching { settingsRef(uid).update(updates).await() }

    /** Overwrites the entire [Settings] document for [uid] with [settings]. */
    suspend fun setSettings(uid: String, settings: Settings): Result<Unit> =
        runCatching { settingsRef(uid).set(settings).await() }

    companion object {
        const val SETTINGS_COLLECTION = "settings"
    }
}