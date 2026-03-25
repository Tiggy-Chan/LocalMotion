package com.localmotion.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class RuntimeBundleFile(
    val relativePath: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val role: String,
    val required: Boolean = true,
) {
    fun normalizedRelativePath(): String {
        val normalized = relativePath.replace('\\', '/').trim().trimStart('/')
        require(normalized.isNotBlank()) { "文件路径不能为空" }
        require(!normalized.contains("../")) { "文件路径不能包含父级跳转: $relativePath" }
        return normalized
    }

    fun resolvedUrl(manifestUrl: String?): String {
        val candidate = url.ifBlank { normalizedRelativePath() }
        if (manifestUrl.isNullOrBlank()) {
            return candidate
        }
        return URI(manifestUrl).resolve(candidate).toString()
    }
}

data class RuntimeBundleManifest(
    val bundleId: String,
    val displayName: String,
    val version: String,
    val backend: String,
    val minSoc: String?,
    val supportedSocs: List<String>,
    val createdAt: String?,
    val sourceUrl: String?,
    val files: List<RuntimeBundleFile>,
) {
    fun totalBytes(): Long = files.filter { it.required }.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    fun validate(deviceSoc: String): List<String> {
        val errors = mutableListOf<String>()
        if (backend != "qnn") {
            errors += "运行时后端必须为 qnn，当前为 $backend"
        }
        if (files.isEmpty()) {
            errors += "运行时清单没有任何文件条目"
        }
        val currentSocValue = deviceSoc.extractSocValue()
        val minSocValue = minSoc.extractSocValue()
        if (minSoc != null && currentSocValue != null && minSocValue != null && currentSocValue < minSocValue) {
            errors += "设备 SoC $deviceSoc 低于清单要求的最小型号 $minSoc"
        }
        if (supportedSocs.isNotEmpty() && deviceSoc !in supportedSocs) {
            errors += "设备 SoC $deviceSoc 不在支持列表内"
        }

        val seenPaths = mutableSetOf<String>()
        files.forEach { file ->
            runCatching { file.normalizedRelativePath() }.onFailure { throwable ->
                errors += throwable.message ?: "非法文件路径"
            }.onSuccess { normalized ->
                if (!seenPaths.add(normalized)) {
                    errors += "重复的运行时文件路径: $normalized"
                }
            }
            if (file.sha256.length != 64) {
                errors += "文件 ${file.relativePath} 缺少有效的 SHA-256"
            }
            if (file.sizeBytes <= 0L) {
                errors += "文件 ${file.relativePath} 缺少有效的大小"
            }
        }

        RequiredRuntimeEntries.forEach { (role, path) ->
            val entry = files.firstOrNull { it.role == role }
            if (entry == null) {
                errors += "运行时清单缺少角色 $role"
            } else if (runCatching { entry.normalizedRelativePath() }.getOrNull() != path) {
                errors += "角色 $role 必须映射到 $path"
            }
        }
        return errors
    }

    fun toJson(): String {
        val fileArray = JSONArray()
        files.forEach { file ->
            fileArray.put(
                JSONObject().apply {
                    put("relativePath", file.relativePath)
                    put("url", file.url)
                    put("sha256", file.sha256)
                    put("sizeBytes", file.sizeBytes)
                    put("role", file.role)
                    put("required", file.required)
                },
            )
        }
        return JSONObject().apply {
            put("bundleId", bundleId)
            put("displayName", displayName)
            put("version", version)
            put("backend", backend)
            put("minSoc", minSoc)
            put("createdAt", createdAt)
            put("supportedSocs", JSONArray(supportedSocs))
            put("files", fileArray)
        }.toString(2)
    }

    companion object {
        fun fromJson(payload: String, sourceUrl: String? = null): RuntimeBundleManifest {
            val json = JSONObject(payload)
            val fileArray = json.optJSONArray("files") ?: JSONArray()
            val files = buildList {
                for (index in 0 until fileArray.length()) {
                    val item = fileArray.getJSONObject(index)
                    add(
                        RuntimeBundleFile(
                            relativePath = item.optString("relativePath"),
                            url = item.optString("url"),
                            sha256 = item.optString("sha256").lowercase(),
                            sizeBytes = item.optLong("sizeBytes"),
                            role = item.optString("role"),
                            required = item.optBoolean("required", true),
                        ),
                    )
                }
            }
            return RuntimeBundleManifest(
                bundleId = json.optString("bundleId", "localmotion-video-v1"),
                displayName = json.optString("displayName", "LocalMotion Video V1"),
                version = json.optString("version", "unknown"),
                backend = json.optString("backend", "qnn"),
                minSoc = json.optString("minSoc").takeIf { it.isNotBlank() },
                supportedSocs = buildList {
                    val supportedArray = json.optJSONArray("supportedSocs") ?: JSONArray()
                    for (index in 0 until supportedArray.length()) {
                        val value = supportedArray.optString(index)
                        if (value.isNotBlank()) {
                            add(value)
                        }
                    }
                },
                createdAt = json.optString("createdAt").takeIf { it.isNotBlank() },
                sourceUrl = sourceUrl,
                files = files,
            )
        }
    }
}

val RequiredRuntimeEntries = linkedMapOf(
    "stylizer" to "models/sd15_img2img.bin",
    "depth" to "models/depth_anything_v2_small.bin",
    "interpolator" to "models/rife46_lite.bin",
)

private fun String?.extractSocValue(): Int? =
    this
        ?.let { Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1) }
        ?.toIntOrNull()
