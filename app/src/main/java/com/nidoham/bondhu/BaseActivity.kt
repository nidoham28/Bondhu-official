package com.nidoham.bondhu

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Base class for all Activities.
 *
 * Provides:
 * - Persistent per-user language selection applied via [attachBaseContext].
 * - Lifecycle-aware [Flow] collection via [collectFlow].
 */
abstract class BaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.applyAppLanguage())
    }

    /**
     * Collects [flow] in a lifecycle-aware manner, suspending collection when
     * the lifecycle drops below [Lifecycle.State.STARTED] and resuming when
     * it returns. Safe for both UI state and one-shot events.
     *
     * @param flow   The [Flow] to collect.
     * @param action Invoked for each emission.
     */
    protected fun <T> collectFlow(
        flow: Flow<T>,
        action: suspend (T) -> Unit
    ) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect(action)
            }
        }
    }

    companion object {
        private const val PREFS_NAME         = "app_prefs"
        private const val KEY_LANGUAGE       = "app_language"
        private const val KEY_SYSTEM_DEFAULT = "system_default"

        /**
         * Persists [languageCode] and recreates the Activity so the change takes
         * effect immediately. Pass null to revert to the system default locale.
         */
        fun ComponentActivity.updateLanguage(languageCode: String?) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                if (languageCode == null) {
                    putBoolean(KEY_SYSTEM_DEFAULT, true)
                    remove(KEY_LANGUAGE)
                } else {
                    putBoolean(KEY_SYSTEM_DEFAULT, false)
                    putString(KEY_LANGUAGE, languageCode)
                }
            }
            recreate()
        }

        /**
         * Returns a [Context] configured with the persisted app language, or the
         * receiver unchanged if the system default is active or no language has
         * been saved.
         *
         * Uses [Locale.Builder] instead of the deprecated [Locale] string constructor
         * to safely resolve ISO 639 language codes on API 26+.
         */
        private fun Context.applyAppLanguage(): Context {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SYSTEM_DEFAULT, true)) return this

            val languageCode = prefs.getString(KEY_LANGUAGE, null) ?: return this

            return try {
                val locale = Locale.Builder()
                    .setLanguage(languageCode)
                    .build()

                val localeList = LocaleList(locale)
                Locale.setDefault(locale)
                LocaleList.setDefault(localeList)

                val config = Configuration(resources.configuration)
                config.setLocales(localeList)
                createConfigurationContext(config)
            } catch (e: Exception) {
                this
            }
        }
    }
}