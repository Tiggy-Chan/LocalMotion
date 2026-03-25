package com.localmotion.backend

import com.localmotion.model.VideoGenerationRequest
import com.localmotion.model.VideoProgressEvent

interface VideoBackend {
    suspend fun generateClip(
        requestModel: VideoGenerationRequest,
        onProgress: (VideoProgressEvent) -> Unit,
    ): BackendCompletion
}

