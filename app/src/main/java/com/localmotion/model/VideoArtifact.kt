package com.localmotion.model

import org.json.JSONObject

data class VideoArtifact(
    val id: String,
    val createdAtEpochMs: Long,
    val prompt: String,
    val styleStrength: Float,
    val mp4Path: String,
    val posterPath: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val durationMs: Long,
    val generationTimeMs: Long,
    val seed: Long?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAtEpochMs", createdAtEpochMs)
        put("prompt", prompt)
        put("styleStrength", styleStrength.toDouble())
        put("mp4Path", mp4Path)
        put("posterPath", posterPath)
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("durationMs", durationMs)
        put("generationTimeMs", generationTimeMs)
        if (seed != null) {
            put("seed", seed)
        }
    }

    companion object {
        fun fromJson(json: String): VideoArtifact = fromJson(JSONObject(json))

        fun fromJson(obj: JSONObject): VideoArtifact = VideoArtifact(
            id = obj.getString("id"),
            createdAtEpochMs = obj.getLong("createdAtEpochMs"),
            prompt = obj.optString("prompt"),
            styleStrength = obj.optDouble("styleStrength", 0.0).toFloat(),
            mp4Path = obj.getString("mp4Path"),
            posterPath = obj.getString("posterPath"),
            width = obj.getInt("width"),
            height = obj.getInt("height"),
            fps = obj.getInt("fps"),
            durationMs = obj.getLong("durationMs"),
            generationTimeMs = obj.getLong("generationTimeMs"),
            seed = if (obj.has("seed")) obj.getLong("seed") else null,
        )
    }
}
