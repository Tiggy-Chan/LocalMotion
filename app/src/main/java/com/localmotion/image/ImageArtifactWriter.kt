package com.localmotion.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.localmotion.backend.BackendCompletion
import com.localmotion.model.ImageArtifact
import com.localmotion.model.ImageGenerationRequest
import com.localmotion.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

object ImageArtifactWriter {
    suspend fun writeArtifact(
        outputDirectory: File,
        sourceBitmap: Bitmap,
        request: ImageGenerationRequest,
        completion: BackendCompletion,
        previewImageBase64: String?,
        generationStartedAt: Long,
    ): ImageArtifact = withContext(Dispatchers.Default) {
        val width = completion.width.coerceAtLeast(1)
        val height = completion.height.coerceAtLeast(1)
        val outputFile = File(outputDirectory, "result.jpg")

        val completionBitmap = ImageUtils.base64ToBitmap(completion.imageBase64)
        val previewBitmap = ImageUtils.base64ToBitmap(previewImageBase64)
        val fallbackBitmap = stylize(
            source = sourceBitmap,
            prompt = request.prompt,
            guidanceScale = request.guidanceScale,
            strength = request.strength,
            isImg2Img = request.mode == "img2img",
        )
        val selectedBitmap = completionBitmap ?: previewBitmap ?: fallbackBitmap
        val finalBitmap = scaleBitmap(selectedBitmap, width, height)

        ImageUtils.writePoster(finalBitmap, outputFile)

        ImageArtifact(
            id = outputDirectory.name,
            createdAtEpochMs = System.currentTimeMillis(),
            prompt = request.prompt,
            negativePrompt = request.negativePrompt,
            guidanceScale = request.guidanceScale,
            inferenceSteps = request.inferenceSteps,
            strength = request.strength,
            imagePath = outputFile.absolutePath,
            width = width,
            height = height,
            generationTimeMs = System.currentTimeMillis() - generationStartedAt,
            seed = completion.seed,
            sourceMode = request.mode,
        )
    }

    private fun stylize(
        source: Bitmap,
        prompt: String,
        guidanceScale: Float,
        strength: Float,
        isImg2Img: Boolean,
    ): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val blendStrength = if (isImg2Img) strength else (guidanceScale / 12f).coerceIn(0.35f, 1.1f)
        val saturation = 1f + blendStrength * 0.35f
        val lift = ((prompt.hashCode().absoluteValue % 18) - 9) * blendStrength
        val matrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, lift,
                0f, 1f, 0f, 0f, lift / 2f,
                0f, 0f, 1f, 0f, lift / 3f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
        matrix.postConcat(saturationMatrix)
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)

        if (blendStrength > 0f) {
            val overlayPaint = Paint().apply {
                color = promptOverlayColor(prompt, blendStrength)
            }
            canvas.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), overlayPaint)
        }
        return output
    }

    private fun promptOverlayColor(prompt: String, blendStrength: Float): Int {
        val hue = prompt.hashCode().absoluteValue % 360
        val hsv = floatArrayOf(hue.toFloat(), 0.2f + blendStrength * 0.35f, 1f)
        return Color.HSVToColor((20 + blendStrength * 36f).roundToInt().coerceIn(0, 96), hsv)
    }

    private fun scaleBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height) {
            return source
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, Rect(0, 0, width, height), paint)
        return output
    }
}
