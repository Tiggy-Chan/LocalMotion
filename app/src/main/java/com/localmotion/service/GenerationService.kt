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
import com.localmotion.backend.GenerationBackend
import com.localmotion.backend.NativeSidecarGenerationBackend
import com.localmotion.backend.StubGenerationBackend
import com.localmotion.data.ArtifactRepository
import com.localmotion.data.RuntimeRepository
import com.localmotion.image.ImageArtifactWriter
import com.localmotion.model.ImageArtifact
import com.localmotion.model.ImageGenerationRequest
import com.localmotion.util.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GenerationService : Service() {
    sealed class GenerationState {
        data object Idle : GenerationState()
        data class Preparing(val message: String) : GenerationState()
        data class Running(
            val stage: String,
            val progress: Float,
            val previewImageBase64: String? = null,
        ) : GenerationState()

        data class Completed(val artifact: ImageArtifact) : GenerationState()
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
        private const val EXTRA_NEGATIVE_PROMPT = "extra_negative_prompt"
        private const val EXTRA_GUIDANCE_SCALE = "extra_guidance_scale"
        private const val EXTRA_INFERENCE_STEPS = "extra_inference_steps"
        private const val EXTRA_IMG2IMG_STRENGTH = "extra_img2img_strength"
        private const val EXTRA_OUTPUT_SIZE = "extra_output_size"
        private const val EXTRA_SEED = "extra_seed"

        private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState: kotlinx.coroutines.flow.StateFlow<GenerationState> = mutableState

        fun createStartIntent(
            context: Context,
            imageUri: Uri?,
            prompt: String,
            negativePrompt: String,
            guidanceScale: Float,
            inferenceSteps: Int,
            img2imgStrength: Float,
            outputSize: Int = 512,
            seed: Long = System.currentTimeMillis(),
        ): Intent = Intent(context, GenerationService::class.java).apply {
            action = ACTION_START
            imageUri?.let { putExtra(EXTRA_IMAGE_URI, it.toString()) }
            putExtra(EXTRA_PROMPT, prompt)
            putExtra(EXTRA_NEGATIVE_PROMPT, negativePrompt)
            putExtra(EXTRA_GUIDANCE_SCALE, guidanceScale)
            putExtra(EXTRA_INFERENCE_STEPS, inferenceSteps)
            putExtra(EXTRA_IMG2IMG_STRENGTH, img2imgStrength)
            putExtra(EXTRA_OUTPUT_SIZE, outputSize)
            putExtra(EXTRA_SEED, seed)
        }

        fun start(
            context: Context,
            imageUri: Uri?,
            prompt: String,
            negativePrompt: String,
            guidanceScale: Float,
            inferenceSteps: Int,
            img2imgStrength: Float,
        ) {
            ContextCompat.startForegroundService(
                context,
                createStartIntent(
                    context = context,
                    imageUri = imageUri,
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    guidanceScale = guidanceScale,
                    inferenceSteps = inferenceSteps,
                    img2imgStrength = img2imgStrength,
                ),
            )
        }

        fun cancel(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GenerationService::class.java).setAction(ACTION_CANCEL),
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
        val imageUri = intent?.getStringExtra(EXTRA_IMAGE_URI)?.let(Uri::parse)
        val prompt = intent?.getStringExtra(EXTRA_PROMPT).orEmpty()
        val negativePrompt = intent?.getStringExtra(EXTRA_NEGATIVE_PROMPT).orEmpty()
        val guidanceScale = intent?.getFloatExtra(EXTRA_GUIDANCE_SCALE, 7.5f) ?: 7.5f
        val inferenceSteps = intent?.getIntExtra(EXTRA_INFERENCE_STEPS, 20) ?: 20
        val img2imgStrength = intent?.getFloatExtra(EXTRA_IMG2IMG_STRENGTH, 0.75f) ?: 0.75f
        val outputSize = intent?.getIntExtra(EXTRA_OUTPUT_SIZE, 512) ?: 512
        val seed = intent?.getLongExtra(EXTRA_SEED, System.currentTimeMillis()) ?: System.currentTimeMillis()

        runningJob?.cancel()
        runningJob = scope.launch {
            try {
                val generationStartedAt = System.currentTimeMillis()
                val runtimeStatus = RuntimeRepository(applicationContext).status()
                val backend = selectBackend(runtimeStatus.isReady)

                mutableState.value = GenerationState.Preparing(
                    if (imageUri != null) "正在准备输入图片" else "正在生成初始画布",
                )
                val preparedBitmap = imageUri?.let {
                    ImageUtils.loadPreparedBitmap(applicationContext, it, outputSize)
                } ?: ImageUtils.createPromptSeedBitmap(prompt, outputSize)
                val request = ImageGenerationRequest(
                    referenceImageBase64 = if (imageUri != null) {
                        ImageUtils.bitmapToBase64Jpeg(preparedBitmap)
                    } else {
                        null
                    },
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    guidanceScale = guidanceScale,
                    inferenceSteps = inferenceSteps,
                    strength = img2imgStrength,
                    seed = seed,
                    outputSize = outputSize,
                )
                var previewImageBase64: String? = null

                val completion = runCatching {
                    backend.generateImage(request) { event ->
                        previewImageBase64 = event.previewImageBase64 ?: previewImageBase64
                        mutableState.value = GenerationState.Running(
                            stage = event.stage,
                            progress = event.progress,
                            previewImageBase64 = previewImageBase64,
                        )
                    }
                }.getOrElse { throwable ->
                    if (backend is NativeSidecarGenerationBackend) {
                        BackendService.markPlaceholder()
                        StubGenerationBackend().generateImage(request) { event ->
                            previewImageBase64 = event.previewImageBase64 ?: previewImageBase64
                            mutableState.value = GenerationState.Running(
                                stage = event.stage,
                                progress = event.progress,
                                previewImageBase64 = previewImageBase64,
                            )
                        }
                    } else {
                        throw throwable
                    }
                }

                mutableState.value = GenerationState.Running("save", 0f, previewImageBase64)
                val artifactRepository = ArtifactRepository(applicationContext)
                val artifactDirectory = artifactRepository.createArtifactDir()
                val artifact = ImageArtifactWriter.writeArtifact(
                    outputDirectory = artifactDirectory,
                    sourceBitmap = preparedBitmap,
                    request = request,
                    completion = completion,
                    previewImageBase64 = previewImageBase64,
                    generationStartedAt = generationStartedAt,
                )
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

    private suspend fun selectBackend(runtimeReady: Boolean): GenerationBackend {
        if (!runtimeReady) {
            BackendService.markPlaceholder()
            mutableState.value = GenerationState.Preparing("正在使用演示后端")
            return StubGenerationBackend()
        }

        mutableState.value = GenerationState.Preparing("正在启动本地后端")
        BackendService.ensureRunning(applicationContext)
        val health = backendClient.waitUntilReachable(5_000L)
        return if (health?.canGenerate == true) {
            NativeSidecarGenerationBackend(backendClient)
        } else {
            BackendService.markPlaceholder()
            mutableState.value = GenerationState.Preparing(
                health?.detail ?: "QNN 图像后端未就绪，已切换到演示模式",
            )
            StubGenerationBackend()
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
