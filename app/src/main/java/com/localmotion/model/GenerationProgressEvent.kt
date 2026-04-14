package com.localmotion.model

import org.json.JSONObject

data class GenerationProgressEvent(
    val stage: String,
    val progress: Float,
    val previewImageBase64: String? = null,
    val message: String? = null,
) {
    companion object {
        fun fromJson(json: String): GenerationProgressEvent {
            val obj = JSONObject(json)
            return GenerationProgressEvent(
                stage = obj.optString("stage", "pending"),
                progress = obj.optDouble("progress", 0.0).toFloat(),
                previewImageBase64 = obj.optString("previewImageBase64").takeIf { it.isNotBlank() }
                    ?: obj.optString("previewFrameBase64").takeIf { it.isNotBlank() },
                message = obj.optString("message").takeIf { it.isNotBlank() },
            )
        }
    }
}
