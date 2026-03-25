package com.localmotion.data

import android.content.Context
import android.os.Build
import com.localmotion.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class RuntimeStatus(
    val isReady: Boolean,
    val modelDir: File,
    val missingFiles: List<String>,
    val validationErrors: List<String>,
    val manifest: RuntimeBundleManifest?,
    val installedBytes: Long,
    val expectedBytes: Long,
    val defaultManifestUrl: String?,
)

class RuntimeRepository(private val context: Context) {
    private val manifestFileName = "runtime-manifest.json"

    fun modelDir(): File = File(context.filesDir, "models/video-v1").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    fun manifestFile(directory: File = modelDir()): File = File(directory, manifestFileName)

    fun status(): RuntimeStatus {
        val dir = modelDir()
        val manifestFile = manifestFile(dir)
        if (!manifestFile.exists()) {
            return RuntimeStatus(
                isReady = false,
                modelDir = dir,
                missingFiles = listOf(manifestFileName),
                validationErrors = emptyList(),
                manifest = null,
                installedBytes = 0L,
                expectedBytes = 0L,
                defaultManifestUrl = BuildConfig.DEFAULT_RUNTIME_MANIFEST_URL.takeIf { it.isNotBlank() },
            )
        }

        val manifest = runCatching {
            RuntimeBundleManifest.fromJson(manifestFile.readText(), manifestFile.toURI().toString())
        }.getOrElse { throwable ->
            return RuntimeStatus(
                isReady = false,
                modelDir = dir,
                missingFiles = emptyList(),
                validationErrors = listOf("运行时清单解析失败: ${throwable.message}"),
                manifest = null,
                installedBytes = 0L,
                expectedBytes = 0L,
                defaultManifestUrl = BuildConfig.DEFAULT_RUNTIME_MANIFEST_URL.takeIf { it.isNotBlank() },
            )
        }

        val validationErrors = manifest.validate(Build.SOC_MODEL).toMutableList()
        val missingFiles = mutableListOf<String>()
        var installedBytes = 0L

        manifest.files
            .filter { it.required }
            .forEach { file ->
                val relativePath = runCatching { file.normalizedRelativePath() }.getOrElse { throwable ->
                    validationErrors += throwable.message ?: "非法文件路径"
                    return@forEach
                }
                val candidate = File(dir, relativePath)
                if (!candidate.exists()) {
                    missingFiles += relativePath
                } else {
                    installedBytes += candidate.length()
                    if (file.sizeBytes > 0L && candidate.length() != file.sizeBytes) {
                        validationErrors += "$relativePath 大小不匹配"
                    }
                }
            }

        val isReady = missingFiles.isEmpty() && validationErrors.isEmpty()
        return RuntimeStatus(
            isReady = isReady,
            modelDir = dir,
            missingFiles = missingFiles,
            validationErrors = validationErrors,
            manifest = manifest,
            installedBytes = installedBytes,
            expectedBytes = manifest.totalBytes(),
            defaultManifestUrl = BuildConfig.DEFAULT_RUNTIME_MANIFEST_URL.takeIf { it.isNotBlank() },
        )
    }

    fun verifyInstalledBundle(): RuntimeStatus {
        val baseStatus = status()
        val manifest = baseStatus.manifest ?: return baseStatus
        val validationErrors = baseStatus.validationErrors.toMutableList()
        manifest.files
            .filter { it.required }
            .forEach { file ->
                val relativePath = runCatching { file.normalizedRelativePath() }.getOrElse { throwable ->
                    validationErrors += throwable.message ?: "非法文件路径"
                    return@forEach
                }
                val candidate = File(baseStatus.modelDir, relativePath)
                if (!candidate.exists()) {
                    return@forEach
                }
                val actualSha256 = sha256(candidate)
                if (!actualSha256.equals(file.sha256, ignoreCase = true)) {
                    validationErrors += "$relativePath SHA-256 不匹配"
                }
            }
        return baseStatus.copy(
            isReady = baseStatus.missingFiles.isEmpty() && validationErrors.isEmpty(),
            validationErrors = validationErrors,
        )
    }

    fun createStagingDir(): File {
        val stagingRoot = File(context.filesDir, "models/.staging").apply {
            if (!exists()) {
                mkdirs()
            }
        }
        return File(stagingRoot, "video-v1-${System.currentTimeMillis()}").apply {
            mkdirs()
        }
    }

    fun writeManifest(directory: File, payload: String) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        manifestFile(directory).writeText(payload)
    }

    fun commitStagingDir(stagingDir: File) {
        val finalDir = modelDir()
        val parent = finalDir.parentFile ?: throw IllegalStateException("模型目录没有父目录")
        val backupDir = File(parent, "video-v1.backup")
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }
        if (finalDir.exists() && !finalDir.renameTo(backupDir)) {
            throw IllegalStateException("无法备份旧运行时目录")
        }
        val moved = stagingDir.renameTo(finalDir)
        if (!moved) {
            stagingDir.copyRecursively(finalDir, overwrite = true)
            stagingDir.deleteRecursively()
        }
        backupDir.deleteRecursively()
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) {
                        break
                    }
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
