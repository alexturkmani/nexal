package com.nexal.app.ui.paywall

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers when a free user has already seen Premium at a given moment, so it never nags. */
@Singleton
class PaywallPrompt @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("paywall_prompt", Context.MODE_PRIVATE)

    fun queue() {
        prefs.edit().putBoolean(KEY_PENDING, true).apply()
    }

    /** True once per queue, so the paywall never repeats. */
    fun consume(): Boolean {
        val pending = prefs.getBoolean(KEY_PENDING, false)
        if (pending) prefs.edit().remove(KEY_PENDING).apply()
        return pending
    }

    /** True the first time [moment] happens, then never again. For one-off paywall moments. */
    fun once(moment: String): Boolean {
        val key = "moment_$moment"
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    private companion object {
        const val KEY_PENDING = "after_setup"
    }
}
