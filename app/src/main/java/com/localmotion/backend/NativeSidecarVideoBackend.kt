package com.localmotion.backend

import com.localmotion.model.VideoGenerationRequest
import com.localmotion.model.VideoProgressEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NativeSidecarVideoBackend(
    private val client: BackendClient,
) : VideoBackend {
    override suspend fun generateClip(
        requestModel: VideoGenerationRequest,
        onProgress: (VideoProgressEvent) -> Unit,
    ): BackendCompletion = withContext(Dispatchers.IO) {
        client.generateClip(requestModel, onProgress)
    }
}

