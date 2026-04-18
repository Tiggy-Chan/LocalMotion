package com.localmotion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localmotion.BuildConfig
import com.localmotion.R
import com.localmotion.data.RuntimeBundleManifest
import com.localmotion.data.RuntimeRepository
import com.localmotion.data.currentRuntimeWorkloadProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class RuntimeInstallService : Service() {
    sealed class InstallState {
        data object Idle : InstallState()
        data class Preparing(val message: String) : InstallState()
        data class Downloading(
            val fileName: String,
            val fileProgress: Float,
            val overallProgress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : InstallState()

        data class Verifying(val fileName: String, val overallProgress: Float) : InstallState()
        data class Installing(val version: String) : InstallState()
        data class Completed(val manifest: RuntimeBundleManifest) : InstallState()
        data class Error(val message: String) : InstallState()
        data object Cancelled : InstallState()
    }

    companion object {
        private const val ChannelId = "localmotion_runtime_install"
        private const val NotificationId = 401
        private const val ACTION_INSTALL = "com.localmotion.action.INSTALL_RUNTIME"
        private const val ACTION_CANCEL = "com.localmotion.action.CANCEL_RUNTIME_INSTALL"
        private const val EXTRA_MANIFEST_URL = "extra_manifest_url"

        private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<InstallState>(InstallState.Idle)
        val installState: kotlinx.coroutines.flow.StateFlow<InstallState> = mutableState

        fun install(context: Context, manifestUrl: String? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuntimeInstallService::class.java).apply {
                    action = ACTION_INSTALL
                    putExtra(EXTRA_MANIFEST_URL, manifestUrl)
                },
            )
        }

        fun cancel(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuntimeInstallService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var installJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationId, buildNotification("准备安装运行时"))
        when (intent?.action) {
            ACTION_CANCEL -> cancelInstall()
            ACTION_INSTALL, null -> startInstall(intent?.getStringExtra(EXTRA_MANIFEST_URL))
        }
        return START_NOT_STICKY
    }

    private fun startInstall(manifestUrlOverride: String?) {
        installJob?.cancel()
        installJob = scope.launch {
            val repository = RuntimeRepository(applicationContext)
            val manifestUrl = manifestUrlOverride?.takeIf { it.isNotBlank() }
                ?: BuildConfig.DEFAULT_RUNTIME_MANIFEST_URL.takeIf { it.isNotBlank() }

            if (manifestUrl.isNullOrBlank()) {
                mutableState.value = InstallState.Error("未配置运行时清单下载地址，请先设置 localmotion.runtimeManifestUrl")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            var stagingDir: File? = null
            try {
                mutableState.value = InstallState.Preparing("正在下载运行时清单")
                updateNotification("正在下载运行时清单")
                val manifestPayload = downloadText(manifestUrl)
                val manifest = RuntimeBundleManifest.fromJson(manifestPayload, manifestUrl)
                val manifestErrors = manifest.validate(
                    Build.SOC_MODEL,
                    currentRuntimeWorkloadProfile().id,
                )
                if (manifestErrors.isNotEmpty()) {
                    throw IOException(manifestErrors.joinToString(separator = "\n"))
                }

                stagingDir = repository.createStagingDir()
                val totalBytes = manifest.totalBytes().coerceAtLeast(1L)
                var completedBytes = 0L

                manifest.files.forEach { file ->
                    currentCoroutineContext().ensureActive()
                    val relativePath = file.normalizedRelativePath()
                    val outputFile = File(stagingDir, relativePath).apply {
                        parentFile?.mkdirs()
                    }
                    val tempFile = File(outputFile.parentFile, "${outputFile.name}.part")
                    mutableState.value = InstallState.Preparing("正在下载 ${outputFile.name}")
                    updateNotification("正在下载 ${outputFile.name}")
                    downloadFile(
                        url = file.resolvedUrl(manifest.sourceUrl),
                        destFile = tempFile,
                        onProgress = { downloadedBytes, contentLength ->
                            val fileProgress = if (contentLength > 0L) {
                                downloadedBytes.toFloat() / contentLength.toFloat()
                            } else {
                                0f
                            }
                            val overall = ((completedBytes + downloadedBytes).toDouble() / totalBytes.toDouble())
                                .toFloat()
                                .coerceIn(0f, 1f)
                            mutableState.value = InstallState.Downloading(
                                fileName = outputFile.name,
                                fileProgress = fileProgress,
                                overallProgress = overall,
                                downloadedBytes = completedBytes + downloadedBytes,
                                totalBytes = totalBytes,
                            )
                            updateNotification("正在下载 ${outputFile.name}", overall)
                        },
                    )

                    mutableState.value = InstallState.Verifying(
                        fileName = outputFile.name,
                        overallProgress = completedBytes.toFloat() / totalBytes.toFloat(),
                    )
                    updateNotification("正在校验 ${outputFile.name}")
                    if (file.sizeBytes > 0L && tempFile.length() != file.sizeBytes) {
                        throw IOException("${outputFile.name} 大小校验失败，期望 ${file.sizeBytes} 字节，实际 ${tempFile.length()} 字节")
                    }
                    val actualSha256 = RuntimeRepository.sha256(tempFile)
                    if (!actualSha256.equals(file.sha256, ignoreCase = true)) {
                        throw IOException("${outputFile.name} SHA-256 校验失败")
                    }
                    if (outputFile.exists()) {
                        outputFile.delete()
                    }
                    if (!tempFile.renameTo(outputFile)) {
                        throw IOException("无法写入 ${outputFile.absolutePath}")
                    }
                    completedBytes += file.sizeBytes
                }

                repository.writeManifest(stagingDir, manifestPayload)
                mutableState.value = InstallState.Installing(manifest.version)
                updateNotification("正在安装 ${manifest.displayName}")
                repository.commitStagingDir(stagingDir)
                stagingDir = null
                val verifiedStatus = repository.verifyInstalledBundle()
                if (!verifiedStatus.isReady) {
                    throw IOException(
                        (verifiedStatus.validationErrors + verifiedStatus.missingFiles)
                            .joinToString(separator = "\n")
                            .ifBlank { "运行时安装后校验失败" },
                    )
                }
                BackendService.markIdle()
                mutableState.value = InstallState.Completed(manifest)
            } catch (cancelled: CancellationException) {
                stagingDir?.deleteRecursively()
                mutableState.value = InstallState.Cancelled
            } catch (throwable: Throwable) {
                stagingDir?.deleteRecursively()
                mutableState.value = InstallState.Error(throwable.message ?: "运行时安装失败")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun downloadText(url: String): String {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载运行时清单失败: HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("运行时清单为空")
        }
    }

    private suspend fun downloadFile(
        url: String,
        destFile: File,
        onProgress: (downloadedBytes: Long, contentLength: Long) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载文件失败: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("下载文件为空: $url")
            val contentLength = body.contentLength()
            body.byteStream().buffered().use { input ->
                FileOutputStream(destFile).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) {
                            break
                        }
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, contentLength)
                    }
                }
            }
            onProgress(contentLength.takeIf { it > 0L } ?: destFile.length(), contentLength)
        }
    }

    private fun cancelInstall() {
        installJob?.cancel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.runtime_install_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.runtime_install_channel_description)
            },
        )
    }

    private fun updateNotification(message: String, progress: Float? = null) {
        val notification = buildNotification(message, progress)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NotificationId, notification)
    }

    private fun buildNotification(message: String, progress: Float? = null): Notification {
        val builder = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.runtime_install_notification_title))
            .setContentText(message)
            .setOngoing(true)
        if (progress != null) {
            builder.setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
