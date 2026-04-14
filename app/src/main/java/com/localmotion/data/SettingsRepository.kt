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
    val defaultGuidanceScale: Float = 7.5f,
    val defaultInferenceSteps: Int = 20,
    val defaultImg2ImgStrength: Float = 0.75f,
)

class SettingsRepository(private val context: Context) {
    private val guidanceScaleKey = floatPreferencesKey("default_guidance_scale")
    private val inferenceStepsKey = intPreferencesKey("default_inference_steps")
    private val img2imgStrengthKey = floatPreferencesKey("default_img2img_strength")

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        UserSettings(
            defaultGuidanceScale = prefs[guidanceScaleKey] ?: 7.5f,
            defaultInferenceSteps = prefs[inferenceStepsKey] ?: 20,
            defaultImg2ImgStrength = prefs[img2imgStrengthKey] ?: 0.75f,
        )
    }

    suspend fun updateGenerationDefaults(
        guidanceScale: Float,
        inferenceSteps: Int,
        img2imgStrength: Float,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[guidanceScaleKey] = guidanceScale
            prefs[inferenceStepsKey] = inferenceSteps
            prefs[img2imgStrengthKey] = img2imgStrength
        }
    }
}
