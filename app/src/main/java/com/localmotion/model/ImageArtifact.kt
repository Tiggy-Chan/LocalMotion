package com.localmotion.model

import org.json.JSONObject

data class ImageArtifact(
    val id: String,
    val createdAtEpochMs: Long,
    val prompt: String,
    val negativePrompt: String,
    val guidanceScale: Float,
    val inferenceSteps: Int,
    val strength: Float,
    val imagePath: String,
    val width: Int,
    val height: Int,
    val generationTimeMs: Long,
    val seed: Long?,
    val sourceMode: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAtEpochMs", createdAtEpochMs)
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        put("guidanceScale", guidanceScale.toDouble())
        put("inferenceSteps", inferenceSteps)
        put("strength", strength.toDouble())
        put("imagePath", imagePath)
        put("width", width)
        put("height", height)
        put("generationTimeMs", generationTimeMs)
        put("sourceMode", sourceMode)
        if (seed != null) {
            put("seed", seed)
        }
    }

    companion object {
        fun fromJson(json: String): ImageArtifact = fromJson(JSONObject(json))

        fun fromJson(obj: JSONObject): ImageArtifact = ImageArtifact(
            id = obj.getString("id"),
            createdAtEpochMs = obj.getLong("createdAtEpochMs"),
            prompt = obj.optString("prompt"),
            negativePrompt = obj.optString("negativePrompt"),
            guidanceScale = obj.optDouble("guidanceScale", 7.5).toFloat(),
            inferenceSteps = obj.optInt("inferenceSteps", 20),
            strength = when {
                obj.has("strength") -> obj.optDouble("strength", 0.75).toFloat()
                obj.has("styleStrength") -> obj.optDouble("styleStrength", 0.75).toFloat()
                else -> 0.75f
            },
            imagePath = obj.getString("imagePath"),
            width = obj.getInt("width"),
            height = obj.getInt("height"),
            generationTimeMs = obj.getLong("generationTimeMs"),
            seed = if (obj.has("seed")) obj.getLong("seed") else null,
            sourceMode = obj.optString("sourceMode", "txt2img"),
        )
    }
}
