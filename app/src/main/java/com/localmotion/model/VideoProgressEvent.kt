package com.localmotion.model

import org.json.JSONObject

data class VideoProgressEvent(
    val stage: String,
    val progress: Float,
    val previewFrameBase64: String? = null,
    val message: String? = null,
) {
    companion object {
        fun fromJson(json: String): VideoProgressEvent {
            val obj = JSONObject(json)
            return VideoProgressEvent(
                stage = obj.optString("stage", "pending"),
                progress = obj.optDouble("progress", 0.0).toFloat(),
                previewFrameBase64 = obj.optString("previewFrameBase64").takeIf { it.isNotBlank() },
                message = obj.optString("message").takeIf { it.isNotBlank() },
            )
        }
    }
}
