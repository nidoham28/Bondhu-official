package com.nidoham.bondhu

import android.app.Application
import com.nidoham.extractor.Downloader
import com.nidoham.server.manager.PresenceManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.nidoham.server.util.ImgBBStorage
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry-point.
 *
 * Responsibilities:
 * - Bootstraps Hilt dependency injection.
 * - Initializes Timber logging.
 * - Initializes [PresenceManager] for online/offline presence tracking.
 * - Provides an application-wide [CoroutineScope] for background work.
 */
@HiltAndroidApp
class BondhuApp : Application() {

    @Inject
    lateinit var presenceManager: PresenceManager

    /** Application-wide scope; [SupervisorJob] ensures one failure does not cancel siblings. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Initialize presence tracking immediately so the init block inside
        // PresenceManager runs as early as possible after Hilt injection.
        presenceManager.initialize()
        Downloader.init(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        appScope.launch {
            runCatching { ImgBBStorage.apiKey() }
                .onFailure { e -> Timber.e(e, "BondhuApp: failed to initialize ImgBB API key") }
        }
    }
}