package com.localmotion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localmotion.R
import com.localmotion.backend.BackendClient
import com.localmotion.backend.NativeSidecarVideoBackend
import com.localmotion.backend.StubVideoBackend
import com.localmotion.backend.VideoBackend
import com.localmotion.data.ArtifactRepository
import com.localmotion.data.RuntimeRepository
import com.localmotion.model.VideoArtifact
import com.localmotion.model.VideoGenerationRequest
import com.localmotion.util.ImageUtils
import com.localmotion.video.LocalVideoComposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipGenerationService : Service() {
    sealed class GenerationState {
        data object Idle : GenerationState()
        data class Preparing(val message: String) : GenerationState()
        data class Running(
            val stage: String,
            val progress: Float,
            val previewFrameBase64: String? = null,
        ) : GenerationState()

        data class Completed(val artifact: VideoArtifact) : GenerationState()
        data class Error(val message: String) : GenerationState()
        data object Cancelled : GenerationState()
    }

    companion object {
        private const val ChannelId = "localmotion_generation"
        private const val NotificationId = 301

        const val ACTION_START = "com.localmotion.action.START_GENERATION"
        const val ACTION_CANCEL = "com.localmotion.action.CANCEL_GENERATION"

        private const val EXTRA_IMAGE_URI = "extra_image_uri"
        private const val EXTRA_PROMPT = "extra_prompt"
        private const val EXTRA_STYLE_STRENGTH = "extra_style_strength"
        private const val EXTRA_DURATION_SEC = "extra_duration_sec"
        private const val EXTRA_FPS = "extra_fps"
        private const val EXTRA_OUTPUT_SIZE = "extra_output_size"
        private const val EXTRA_SEED = "extra_seed"

        private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState: kotlinx.coroutines.flow.StateFlow<GenerationState> = mutableState

        fun createStartIntent(
            context: Context,
            imageUri: Uri,
            prompt: String,
            styleStrength: Float,
            durationSec: Int = 4,
            fps: Int = 12,
            outputSize: Int = 512,
            seed: Long = System.currentTimeMillis(),
        ): Intent = Intent(context, ClipGenerationService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_IMAGE_URI, imageUri.toString())
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_STYLE_STRENGTH, styleStrength)
            putExtra(EXTRA_DURATION_SEC, durationSec)
            putExtra(EXTRA_FPS, fps)
            putExtra(EXTRA_OUTPUT_SIZE, outputSize)
            putExtra(EXTRA_SEED, seed)
        }

        fun start(
            context: Context,
            imageUri: Uri,
            prompt: String,
            styleStrength: Float,
        ) {
            ContextCompat.startForegroundService(
                context,
                createStartIntent(context, imageUri, prompt, styleStrength),
            )
        }

        fun cancel(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClipGenerationService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: kotlinx.coroutines.Job? = null
    private val backendClient = BackendClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationId, buildNotification())
        when (intent?.action) {
            ACTION_CANCEL -> cancelGeneration()
            ACTION_START, null -> startGeneration(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startGeneration(intent: Intent?) {
        val imageUri = intent?.getStringExtra(EXTRA_IMAGE_URI)?.let(Uri::parse) ?: return
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        val styleStrength = intent.getFloatExtra(EXTRA_STYLE_STRENGTH, 0.25f)
        val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 4)
        val fps = intent.getIntExtra(EXTRA_FPS, 12)
        val outputSize = intent.getIntExtra(EXTRA_OUTPUT_SIZE, 512)
        val seed = intent.getLongExtra(EXTRA_SEED, System.currentTimeMillis())

        runningJob?.cancel()
        runningJob = scope.launch {
            try {
                val runtimeStatus = RuntimeRepository(applicationContext).status()
                val backend = selectBackend(runtimeStatus.isReady)

                mutableState.value = GenerationState.Preparing("正在准备输入图片")
                val preparedBitmap = ImageUtils.loadPreparedBitmap(applicationContext, imageUri, outputSize)
                val request = VideoGenerationRequest(
                    imageBase64 = ImageUtils.bitmapToBase64Jpeg(preparedBitmap),
                    prompt = prompt,
                    styleStrength = styleStrength,
                    durationSec = durationSec,
                    fps = fps,
                    seed = seed,
                    outputSize = outputSize,
                )

                val completion = runCatching {
                    backend.generateClip(request) { event ->
                        mutableState.value = GenerationState.Running(
                            stage = event.stage,
                            progress = event.progress,
                            previewFrameBase64 = event.previewFrameBase64,
                        )
                    }
                }.getOrElse { throwable ->
                    if (backend is NativeSidecarVideoBackend) {
                        BackendService.markPlaceholder()
                        StubVideoBackend().generateClip(request) { event ->
                            mutableState.value = GenerationState.Running(
                                stage = event.stage,
                                progress = event.progress,
                                previewFrameBase64 = event.previewFrameBase64,
                            )
                        }
                    } else {
                        throw throwable
                    }
                }

                mutableState.value = GenerationState.Running("encode", 0f)
                val artifactRepository = ArtifactRepository(applicationContext)
                val artifactDirectory = artifactRepository.createArtifactDir()
                val artifact = LocalVideoComposer.composeClip(
                    outputDirectory = artifactDirectory,
                    sourceBitmap = preparedBitmap,
                    request = request,
                    completion = completion,
                ) { progress ->
                    mutableState.value = GenerationState.Running("encode", progress)
                }
                artifactRepository.saveArtifact(artifact)
                mutableState.value = GenerationState.Completed(artifact)
            } catch (cancelled: CancellationException) {
                mutableState.value = GenerationState.Cancelled
            } catch (throwable: Throwable) {
                mutableState.value = GenerationState.Error(throwable.message ?: "生成失败")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelGeneration() {
        runningJob?.cancel()
        runCatching { backendClient.cancelCurrent() }
        mutableState.value = GenerationState.Cancelled
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun selectBackend(runtimeReady: Boolean): VideoBackend {
        if (!runtimeReady) {
            BackendService.markPlaceholder()
            mutableState.value = GenerationState.Preparing("正在使用演示后端")
            return StubVideoBackend()
        }

        mutableState.value = GenerationState.Preparing("正在启动本地后端")
        BackendService.ensureRunning(applicationContext)
        val health = backendClient.waitUntilReachable(5_000L)
        return if (health?.canGenerate == true) {
            NativeSidecarVideoBackend(backendClient)
        } else {
            BackendService.markPlaceholder()
            mutableState.value = GenerationState.Preparing(
                health?.detail ?: "QNN 后端未就绪，已切换到演示模式",
            )
            StubVideoBackend()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.generation_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.generation_channel_description)
            },
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.generation_notification_title))
            .setContentText(getString(R.string.generation_notification_text))
            .setOngoing(true)
            .build()
}
