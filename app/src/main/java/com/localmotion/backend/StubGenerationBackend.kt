package com.localmotion.backend

import com.localmotion.model.GenerationProgressEvent
import com.localmotion.model.ImageGenerationRequest
import com.localmotion.util.ImageUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class StubGenerationBackend : GenerationBackend {
    override suspend fun generateImage(
        requestModel: ImageGenerationRequest,
        onProgress: (GenerationProgressEvent) -> Unit,
    ): BackendCompletion {
        val denoiseLabel = "正在执行 ${requestModel.inferenceSteps} 步 UNet 去噪"
        val stages = listOf(
            "prepare" to "正在整理输入条件",
            "encode_text" to "正在编码提示词与负向提示词",
            "init_latent" to "正在初始化潜变量",
            "denoise" to denoiseLabel,
            "decode_vae" to "正在解码输出图像",
        )
        val previewImageBase64 = requestModel.referenceImageBase64 ?: ImageUtils.bitmapToBase64Jpeg(
            ImageUtils.createPromptSeedBitmap(requestModel.prompt, requestModel.outputSize),
        )

        stages.forEachIndexed { index, (stage, message) ->
            currentCoroutineContext().ensureActive()
            delay(320L)
            onProgress(
                GenerationProgressEvent(
                    stage = stage,
                    progress = (index + 1).toFloat() / stages.size.toFloat(),
                    previewImageBase64 = previewImageBase64.takeIf { stage == "decode_vae" },
                    message = message,
                ),
            )
        }

        return BackendCompletion(
            width = requestModel.outputSize,
            height = requestModel.outputSize,
            seed = requestModel.seed,
            imageBase64 = previewImageBase64,
        )
    }
}
