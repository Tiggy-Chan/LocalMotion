package com.localmotion.model

import org.json.JSONObject

data class VideoGenerationRequest(
    val imageBase64: String,
    val prompt: String?,
    val styleStrength: Float,
    val durationSec: Int = 4,
    val fps: Int = 12,
    val seed: Long? = null,
    val outputSize: Int = 512,
) {
    fun toJson(): String = JSONObject().apply {
        put("imageBase64", imageBase64)
        put("prompt", prompt.orEmpty())
        put("styleStrength", styleStrength.toDouble())
        put("durationSec", durationSec)
        put("fps", fps)
        put("outputSize", outputSize)
        if (seed != null) {
            put("seed", seed)
        }
    }.toString()
}
