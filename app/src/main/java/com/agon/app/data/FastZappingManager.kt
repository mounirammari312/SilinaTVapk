package com.agon.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object FastZappingManager {
    private const val PREFS_NAME = "fast_zapping_prefs"
    private const val KEY_ENABLED = "fast_zapping_enabled"

    // Default to true (Fast Zapping ON)
    private const val DEFAULT_VALUE = true

    private val _isEnabledFlow = MutableStateFlow(DEFAULT_VALUE)
    val isEnabledFlow: StateFlow<Boolean> = _isEnabledFlow

    fun init(context: Context) {
        val prefs = getPrefs(context)
        _isEnabledFlow.value = prefs.getBoolean(KEY_ENABLED, DEFAULT_VALUE)
    }

    fun isFastZappingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, DEFAULT_VALUE)
    }

    fun setFastZappingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabledFlow.value = enabled
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
