package org.nidoham.server.data.repository

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PresenceManager is responsible for managing the user's online/offline status
 * in Firebase Realtime Database. It handles app lifecycle events to ensure
 * accurate status updates (Online in foreground/multitasking, Offline in background).
 */
@Singleton
class PresenceManager @Inject constructor(
    @ApplicationContext context: Context
) : DefaultLifecycleObserver {

    // Firebase References
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val connectedRef: DatabaseReference = database.child(".info/connected")

    // User State
    private var currentUserId: String? = null
    private var userStatusRef: DatabaseReference? = null
    private var connectionListener: ValueEventListener? = null

    // Store the auth listener so it tracks logins/logouts globally
    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        if (user != null) {
            onUserLogin()
        } else {
            onUserLogout()
        }
    }

    // Flag to prevent redundant database writes
    private val isOnline = AtomicBoolean(false)

    init {
        // Register this observer to listen for app lifecycle changes
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Listen for authentication changes to handle login/logout automatically
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
    }

    /**
     * Called from Application.onCreate() to force Hilt to instantiate this Singleton
     * and trigger the `init` block immediately at app startup.
     */
    fun initialize() {
        // Intentionally empty.
    }

    // ----------------- Lifecycle Events -----------------

    /**
     * Called when the app enters the foreground (Visible).
     * Handles Multitasking (Split Screen) correctly as the app is visible.
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        if (currentUserId != null) {
            goOnline()
        }
    }

    /**
     * Called when the app goes to the background (Not Visible).
     * Handles Home button press or switching apps.
     */
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        if (currentUserId != null) {
            goOffline()
        }
    }

    // ----------------- Core Logic -----------------

    fun onUserLogin() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (currentUserId == uid) return // Already logged in with this user

        currentUserId = uid
        // This ensures WE ONLY WRITE status for the CURRENT logged-in user.
        userStatusRef = database.child("status").child(uid)
        setupConnectionListener()
    }

    fun onUserLogout() {
        // Manually set offline
        goOffline()

        // Safely cancel the onDisconnect hook ONLY on explicit logout.
        // This prevents the server from setting the user offline if another user logs in on this device.
        userStatusRef?.onDisconnect()?.cancel()

        removeConnectionListener()

        // Note: We DO NOT remove the authStateListener here because PresenceManager is a @Singleton.
        // If we removed it, subsequent logins wouldn't be tracked until the app is forcefully restarted.
        currentUserId = null
        userStatusRef = null
    }

    private fun setupConnectionListener() {
        // Remove existing listener to avoid duplicates
        removeConnectionListener()

        connectionListener = connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false

                if (connected) {
                    // Reset the flag so goOnline() always re-writes status after a reconnect.
                    isOnline.set(false)

                    // 1. Set up the disconnect operation first.
                    userStatusRef?.onDisconnect()?.setValue(
                        mapOf(
                            "online" to false,
                            "lastSeen" to ServerValue.TIMESTAMP
                        )
                    )?.addOnSuccessListener {
                        // 2. Once the disconnect operation is confirmed, check foreground.
                        if (isAppForeground()) {
                            goOnline()
                        }
                    }
                } else {
                    // Actively queue offline status locally when internet drops.
                    // Firebase holds it and flushes it to the server the moment connectivity
                    // is restored. The server executes it just before restoring online status.
                    goOffline()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle possible errors (e.g., permission denied)
            }
        })
    }

    private fun removeConnectionListener() {
        if (connectionListener != null) {
            connectedRef.removeEventListener(connectionListener!!)
            connectionListener = null
        }
    }

    // ----------------- Helper Methods -----------------

    private fun goOnline() {
        // Only write if currently offline to save bandwidth
        if (!isOnline.getAndSet(true)) {
            // Firebase SDK queues natively and is completely non-blocking.
            // Coroutines are not needed here.
            userStatusRef?.setValue(
                mapOf(
                    "online" to true,
                    "lastSeen" to ServerValue.TIMESTAMP
                )
            )
        }
    }

    private fun goOffline() {
        // Only write if currently online to save bandwidth
        if (isOnline.getAndSet(false)) {
            // We use a fire-and-forget setValue. If there's no internet, this writes safely
            // into local cache without suspending/freezing anything.
            // Keeping the hook un-cancelled ensures the server will STILL properly set the
            // user to offline (after the ~60s socket timeout) while they are completely unreachable.
            userStatusRef?.setValue(
                mapOf(
                    "online" to false,
                    "lastSeen" to ServerValue.TIMESTAMP
                )
            )
        }
    }

    // ----------------- Live Status -----------------

    /**
     * Represents a snapshot of any user's presence status.
     *
     * @param online  True if the user is currently online.
     * @param lastSeen  Server-side Unix timestamp (ms) of the last seen time.
     */
    data class UserStatus(
        val online: Boolean = false,
        val lastSeen: Long? = null
    )

    /**
     * Returns a cold [Flow] that emits a fresh [UserStatus] every time the specified
     * user's presence node changes in Firebase Realtime Database.
     *
     * You can pass ANY userId here to observe if that specific friend/user is online.
     *
     * @param userId The UID of the user you want to observe.
     */
    fun observeUserStatus(userId: String): Flow<UserStatus> = callbackFlow {
        if (userId.isBlank()) {
            // Invalid user ID provided — emit a safe default and close.
            trySend(UserStatus())
            close()
            return@callbackFlow
        }

        // Point to the SPECIFIC user's status node, not just the current user
        val statusRef = database.child("status").child(userId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val online = snapshot.child("online").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java)
                trySend(UserStatus(online = online, lastSeen = lastSeen))
            }

            override fun onCancelled(error: DatabaseError) {
                // Propagate the Firebase error into the flow so the collector can handle it.
                close(error.toException())
            }
        }

        statusRef.addValueEventListener(listener)

        // Called when the collecting coroutine is cancelled — removes the Firebase listener
        awaitClose { statusRef.removeEventListener(listener) }
    }.flowOn(Dispatchers.IO)

    private fun isAppForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}