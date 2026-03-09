package com.nidoham.bondhu

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.nidoham.server.data.repository.PresenceManager
import org.nidoham.server.util.ImgBBStorage
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry-point.
 *
 * Responsibilities:
 * - Bootstraps Hilt dependency injection.
 * - Initializes logging (Timber).
 * - Initializes Presence Manager (Online/Offline tracking).
 * - Provides an application-wide CoroutineScope for background tasks.
 */
@HiltAndroidApp
class BondhuApp : Application() {

    // Inject PresenceManager via Hilt instead of constructing it manually.
    // Hilt injects into @HiltAndroidApp Application classes automatically before onCreate().
    @Inject
    lateinit var presenceManager: PresenceManager

    // Application-wide scope using SupervisorJob so one failure doesn't cancel others.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. Force instantiation of PresenceManager to start tracking instantly.
        // This ensures the init{} block inside PresenceManager runs safely.
        presenceManager.initialize()

        // 2. Initialize Timber for debugging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 3. Pre-fetch or initialize ImgBB API key in the background
        appScope.launch {
            runCatching { ImgBBStorage.apiKey() }
        }
    }
}