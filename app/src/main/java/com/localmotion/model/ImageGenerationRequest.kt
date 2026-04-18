package com.localmotion.model

import org.json.JSONObject

data class ImageGenerationRequest(
    val referenceImageBase64: String? = null,
    val prompt: String,
    val negativePrompt: String = "",
    val guidanceScale: Float = 7.5f,
    val inferenceSteps: Int = 20,
    val strength: Float = 0.75f,
    val seed: Long? = null,
    val outputSize: Int = 512,
) {
    val mode: String
        get() = if (referenceImageBase64.isNullOrBlank()) "txt2img" else "img2img"

    fun toJson(): String = JSONObject().apply {
        if (!referenceImageBase64.isNullOrBlank()) {
            put("referenceImageBase64", referenceImageBase64)
        }
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("guidanceScale", guidanceScale.toDouble())
        put("inferenceSteps", inferenceSteps)
        put("strength", strength.toDouble())
        put("outputSize", outputSize)
        if (seed != null) {
            put("seed", seed)
        }
    }.toString()
}
