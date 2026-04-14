package com.localmotion.backend

import com.localmotion.model.GenerationProgressEvent
import com.localmotion.model.ImageGenerationRequest
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BackendCompletion(
    val width: Int,
    val height: Int,
    val seed: Long?,
    val imageBase64: String? = null,
)

data class BackendHealth(
    val serverStatus: String,
    val backendMode: String,
    val runtimeReady: Boolean,
    val canGenerate: Boolean,
    val bundleVersion: String?,
    val detail: String?,
)

class BackendClient(
    private val baseUrl: String = "http://127.0.0.1:8081",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun waitUntilReachable(timeoutMs: Long = 30_000L): BackendHealth? {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            val health = health()
            if (health != null) {
                return health
            }
            delay(250L)
        }
        return null
    }

    fun health(): BackendHealth? {
        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (payload.isBlank()) {
                    return@use null
                }
                val json = JSONObject(payload)
                BackendHealth(
                    serverStatus = json.optString("status", "unknown"),
                    backendMode = json.optString("backendMode", "placeholder"),
                    runtimeReady = json.optBoolean("runtimeReady", false),
                    canGenerate = json.optBoolean("canGenerate", false),
                    bundleVersion = json.optString("bundleVersion").takeIf { it.isNotBlank() },
                    detail = json.optString("detail").takeIf { it.isNotBlank() },
                )
            }
        }.getOrNull()
    }

    fun cancelCurrent() {
        val request = Request.Builder()
            .url("$baseUrl/cancel")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().close()
    }

    @Throws(IOException::class)
    fun generateImage(
        requestModel: ImageGenerationRequest,
        onProgress: (GenerationProgressEvent) -> Unit,
    ): BackendCompletion {
        val request = Request.Builder()
            .url("$baseUrl/generate_image")
            .post(requestModel.toJson().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Backend returned ${response.code}")
            }

            val source = response.body?.source() ?: throw IOException("Backend returned empty body")
            var eventName = "message"
            var eventData: String? = null
            var completion: BackendCompletion? = null

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> {
                        eventName = line.removePrefix("event:").trim()
                    }

                    line.startsWith("data:") -> {
                        eventData = line.removePrefix("data:").trim()
                    }

                    line.isBlank() -> {
                        val payload = eventData
                        if (payload != null) {
                            when (eventName) {
                                "progress" -> onProgress(GenerationProgressEvent.fromJson(payload))
                                "complete" -> completion = parseCompletion(payload)
                                "error" -> {
                                    val message = JSONObject(payload).optString("message", "Unknown backend error")
                                    throw IOException(message)
                                }
                            }
                        }
                        eventName = "message"
                        eventData = null
                    }
                }
            }

            return completion ?: throw IOException("Backend closed before completion")
        }
    }

    private fun parseCompletion(payload: String): BackendCompletion {
        val json = JSONObject(payload)
        return BackendCompletion(
            width = json.optInt("width", 512),
            height = json.optInt("height", 512),
            seed = if (json.has("seed")) json.optLong("seed") else null,
            imageBase64 = json.optString("imageBase64").takeIf { it.isNotBlank() },
        )
    }
}
