package com.nidoham.bondhu

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.nidoham.server.util.ImgBBStorage
import timber.log.Timber

/**
 * Application entry-point.
 *
 * Responsibilities:
 * - Bootstraps Hilt dependency injection.
 * - Initializes logging (Timber).
 * - Provides an application-wide CoroutineScope for background tasks.
 */
@HiltAndroidApp
class BondhuApp : Application() {

    // Application-wide scope using SupervisorJob so one failure doesn't cancel others.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for debugging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Pre-fetch or initialize ImgBB API key in the background
        appScope.launch {
            runCatching { ImgBBStorage.apiKey() }
        }
    }
}