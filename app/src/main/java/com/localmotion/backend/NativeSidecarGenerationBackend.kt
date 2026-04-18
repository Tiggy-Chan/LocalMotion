package com.localmotion.backend

import com.localmotion.model.GenerationProgressEvent
import com.localmotion.model.ImageGenerationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NativeSidecarGenerationBackend(
    private val client: BackendClient,
) : GenerationBackend {
    override suspend fun generateImage(
        requestModel: ImageGenerationRequest,
        onProgress: (GenerationProgressEvent) -> Unit,
    ): BackendCompletion = withContext(Dispatchers.IO) {
        client.generateImage(requestModel, onProgress)
    }
}
