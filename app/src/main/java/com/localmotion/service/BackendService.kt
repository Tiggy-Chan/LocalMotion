package com.localmotion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localmotion.R
import com.localmotion.backend.BackendClient
import com.localmotion.data.RuntimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class BackendService : Service() {
    sealed class BackendState {
        data object Idle : BackendState()
        data object Placeholder : BackendState()
        data object Starting : BackendState()
        data object Running : BackendState()
        data class Error(val message: String) : BackendState()
    }

    companion object {
        private const val ChannelId = "localmotion_backend"
        private const val NotificationId = 201
        private const val ExecutableName = "liblocalmotion_backend.so"

        const val ACTION_START = "com.localmotion.action.START_BACKEND"
        const val ACTION_STOP = "com.localmotion.action.STOP_BACKEND"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<BackendState>(BackendState.Idle)
        val backendState: kotlinx.coroutines.flow.StateFlow<BackendState> = mutableState

        fun markPlaceholder() {
            mutableState.value = BackendState.Placeholder
        }

        fun markIdle() {
            mutableState.value = BackendState.Idle
        }

        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BackendService::class.java).setAction(ACTION_START),
            )
        }
    }

    private var process: Process? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationId, buildNotification())
        when (intent?.action) {
            ACTION_STOP -> {
                stopBackend()
                stopSelf()
            }

            else -> startBackend()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBackend()
        super.onDestroy()
    }

    private fun startBackend() {
        if (process?.isAlive == true) {
            mutableState.value = BackendState.Running
            return
        }
        mutableState.value = BackendState.Starting

        val executable = File(applicationInfo.nativeLibraryDir, ExecutableName)
        if (!executable.exists()) {
            mutableState.value = BackendState.Error("Missing native backend: ${executable.absolutePath}")
            return
        }

        runCatching {
            val runtimeDir = RuntimeRepository(applicationContext).modelDir()
            process = ProcessBuilder(
                listOf(
                    executable.absolutePath,
                    "--port",
                    "8081",
                    "--runtime-dir",
                    runtimeDir.absolutePath,
                ),
            ).apply {
                directory(File(applicationInfo.nativeLibraryDir))
                redirectErrorStream(true)
            }.start()

            startMonitorThread()
            scope.launch {
                val health = BackendClient().waitUntilReachable(10_000L)
                mutableState.value = when {
                    health == null -> BackendState.Error("本地后端未响应")
                    health.canGenerate -> BackendState.Running
                    else -> BackendState.Placeholder
                }
                if (health != null && !health.canGenerate) {
                    android.util.Log.w(
                        "LocalMotionBackend",
                        "Backend reachable but not generation-ready: ${health.detail.orEmpty()}",
                    )
                }
            }
        }.onFailure { throwable ->
            mutableState.value = BackendState.Error(throwable.message ?: "Unable to start backend")
        }
    }

    private fun stopBackend() {
        process?.let { backend ->
            backend.destroy()
            if (!backend.waitFor(3, TimeUnit.SECONDS)) {
                backend.destroyForcibly()
            }
        }
        process = null
        mutableState.value = BackendState.Idle
    }

    private fun startMonitorThread() {
        val backendProcess = process ?: return
        Thread {
            runCatching {
                backendProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        android.util.Log.i("LocalMotionBackend", line)
                    }
                }
            }.onFailure { throwable ->
                android.util.Log.e("LocalMotionBackend", "Backend monitor error", throwable)
            }
            if (mutableState.value !is BackendState.Error) {
                mutableState.value = BackendState.Idle
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.backend_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.backend_channel_description)
            },
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.backend_notification_title))
            .setContentText(getString(R.string.backend_notification_text))
            .setOngoing(true)
            .build()
}
