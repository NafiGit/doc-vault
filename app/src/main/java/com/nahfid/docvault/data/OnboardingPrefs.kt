package com.nahfid.docvault.data

import android.content.Context

/**
 * Remembers whether the first-run onboarding was completed.
 *
 * Kept in SharedPreferences, separate from the vault.json store:
 * it is one boolean about the app, not vault data.
 */
class OnboardingPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDone(): Boolean = prefs.getBoolean(KEY_DONE, false)

    fun setDone() {
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "doc_vault_onboarding"
        private const val KEY_DONE = "onboarding_done"
    }
}
