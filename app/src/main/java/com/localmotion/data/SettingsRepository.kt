package com.localmotion.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "localmotion_settings")

data class UserSettings(
    val defaultStyleStrength: Float = 0.25f,
    val defaultDurationSec: Int = 4,
    val defaultFps: Int = 12,
)

class SettingsRepository(private val context: Context) {
    private val styleStrengthKey = floatPreferencesKey("default_style_strength")
    private val durationKey = intPreferencesKey("default_duration_sec")
    private val fpsKey = intPreferencesKey("default_fps")

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        UserSettings(
            defaultStyleStrength = prefs[styleStrengthKey] ?: 0.25f,
            defaultDurationSec = prefs[durationKey] ?: 4,
            defaultFps = prefs[fpsKey] ?: 12,
        )
    }

    suspend fun updateStyleStrength(styleStrength: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[styleStrengthKey] = styleStrength
        }
    }
}
