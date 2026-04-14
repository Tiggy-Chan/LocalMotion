package com.localmotion.backend

import com.localmotion.model.GenerationProgressEvent
import com.localmotion.model.ImageGenerationRequest

interface GenerationBackend {
    suspend fun generateImage(
        requestModel: ImageGenerationRequest,
        onProgress: (GenerationProgressEvent) -> Unit,
    ): BackendCompletion
}
