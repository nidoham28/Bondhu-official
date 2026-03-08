package com.nidoham.bondhu

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * সমস্ত Activity এর জন্য বেস ক্লাস।
 *
 * বৈশিষ্ট্য:
 * - ভাষা পরিবর্তন সাপোর্ট (পারসিস্টেন্ট)
 * - Lifecycle-aware Flow কালেকশন
 */
abstract class BaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.applyAppLanguage())
    }

    /**
     * Flow কালেক্ট করুন lifecycle-aware ভাবে।
     * UI State এবং Event দুটোর জন্যই ব্যবহার করুন।
     *
     * @param flow কালেক্ট করার Flow
     * @param action প্রতিটি ইমিশনে কী করবে
     */
    protected fun <T> collectFlow(
        flow: Flow<T>,
        action: suspend (T) -> Unit
    ) {
        lifecycleScope.launch {
            // STARTED state এ শুধুমাত্র কালেক্ট করবে, অটোমেটিক্যালি pause হলে বন্ধ হবে
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect(action)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_SYSTEM_DEFAULT = "system_default"

        /**
         * অ্যাপের ভাষা আপডেট করুন।
         * null দিলে সিস্টেম ডিফল্ট ব্যবহার হবে।
         */
        fun ComponentActivity.updateLanguage(languageCode: String?) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .apply {
                    if (languageCode == null) {
                        putBoolean(KEY_SYSTEM_DEFAULT, true)
                        remove(KEY_LANGUAGE)
                    } else {
                        putBoolean(KEY_SYSTEM_DEFAULT, false)
                        putString(KEY_LANGUAGE, languageCode)
                    }
                    apply()
                }
            recreate()
        }

        /**
         * Context এ সেভ করা ভাষা অ্যাপ্লাই করুন।
         */
        private fun Context.applyAppLanguage(): Context {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

            // যদি সিস্টেম ডিফল্ট সেট করা থাকে, কনটেক্সট আনচেঞ্জড রিটার্ন করবে
            if (prefs.getBoolean(KEY_SYSTEM_DEFAULT, true)) {
                return this
            }

            val languageCode = prefs.getString(KEY_LANGUAGE, null) ?: return this

            return try {
                val locale = Locale(languageCode)
                Locale.setDefault(locale)

                val config = Configuration(resources.configuration)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val localeList = LocaleList(locale)
                    LocaleList.setDefault(localeList)
                    config.setLocales(localeList)
                } else {
                    config.setLocale(locale)
                }

                createConfigurationContext(config)
            } catch (e: Exception) {
                // কোনো এরর হলে ডিফল্ট কনটেক্সট রিটার্ন
                this
            }
        }
    }
}