package com.localmotion.backend

import com.localmotion.model.VideoGenerationRequest
import com.localmotion.model.VideoProgressEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class StubVideoBackend : VideoBackend {
    override suspend fun generateClip(
        requestModel: VideoGenerationRequest,
        onProgress: (VideoProgressEvent) -> Unit,
    ): BackendCompletion {
        val stages = listOf(
            "preprocess" to "正在整理输入图片",
            "stylize" to "正在生成风格化首帧",
            "depth" to "正在估计景深层次",
            "render" to "正在渲染关键帧",
            "interpolate" to "正在补足中间帧",
        )

        stages.forEachIndexed { index, (stage, message) ->
            currentCoroutineContext().ensureActive()
            delay(320L)
            onProgress(
                VideoProgressEvent(
                    stage = stage,
                    progress = (index + 1).toFloat() / stages.size.toFloat(),
                    previewFrameBase64 = requestModel.imageBase64.takeIf { stage == "render" },
                    message = message,
                ),
            )
        }

        return BackendCompletion(
            width = requestModel.outputSize,
            height = requestModel.outputSize,
            fps = requestModel.fps,
            durationMs = requestModel.durationSec * 1_000L,
            seed = requestModel.seed,
        )
    }
}

