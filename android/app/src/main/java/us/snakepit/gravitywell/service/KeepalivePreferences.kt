package us.snakepit.gravitywell.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-secret local preference backing the Background Keepalive & Notifications toggle
 * in Control Deck (SPEC.md D16). Zero credentials or tokens are ever stored here.
 */
class KeepalivePreferences private constructor(
    private val prefs: SharedPreferences?,
    initialDefault: Boolean = true,
) {
    private val _keepaliveEnabled = MutableStateFlow(
        prefs?.getBoolean(KEY_KEEPALIVE_ENABLED, initialDefault) ?: initialDefault,
    )
    val keepaliveEnabled: StateFlow<Boolean> = _keepaliveEnabled.asStateFlow()

    val isKeepaliveEnabled: Boolean
        get() = _keepaliveEnabled.value

    fun setKeepaliveEnabled(enabled: Boolean) {
        prefs?.edit { putBoolean(KEY_KEEPALIVE_ENABLED, enabled) }
        _keepaliveEnabled.value = enabled
    }

    fun reload() {
        if (prefs != null) {
            _keepaliveEnabled.value = prefs.getBoolean(KEY_KEEPALIVE_ENABLED, true)
        }
    }

    companion object {
        private const val PREFS_NAME = "gravity_well_keepalive_prefs"
        private const val KEY_KEEPALIVE_ENABLED = "keepalive_enabled"

        @Volatile
        private var instance: KeepalivePreferences? = null

        fun get(context: Context): KeepalivePreferences {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: KeepalivePreferences(
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
        }

        fun inMemory(initialEnabled: Boolean = true): KeepalivePreferences =
            KeepalivePreferences(prefs = null, initialDefault = initialEnabled)
    }
}
