package com.localmotion.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.localmotion.backend.BackendCompletion
import com.localmotion.model.VideoArtifact
import com.localmotion.model.VideoGenerationRequest
import com.localmotion.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

object LocalVideoComposer {
    private const val MimeType = "video/avc"
    private const val TimeoutUs = 10_000L

    suspend fun composeClip(
        outputDirectory: File,
        sourceBitmap: Bitmap,
        request: VideoGenerationRequest,
        completion: BackendCompletion,
        onProgress: (Float) -> Unit,
    ): VideoArtifact = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val width = completion.width
        val height = completion.height
        val outputFile = File(outputDirectory, "clip.mp4")
        val posterFile = File(outputDirectory, "poster.jpg")

        val styled = stylize(sourceBitmap, request.prompt, request.styleStrength)
        val totalFrames = max(1, request.durationSec * request.fps)

        val format = MediaFormat.createVideoFormat(MimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, request.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MimeType)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val state = MuxerState(muxer = muxer)

        try {
            repeat(totalFrames) { index ->
                val progress = if (totalFrames == 1) 1f else index.toFloat() / (totalFrames - 1).toFloat()
                val frameBitmap = renderFrame(styled, width, height, progress)
                if (index == 0) {
                    ImageUtils.writePoster(frameBitmap, posterFile)
                }

                val inputBufferIndex = waitForInputBuffer(codec)
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    ?: error("Encoder input buffer unavailable")
                val bytes = bitmapToI420(frameBitmap)
                inputBuffer.clear()
                inputBuffer.put(bytes)
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    bytes.size,
                    presentationTimeUs(index, request.fps),
                    0,
                )
                drainEncoder(codec, state, endOfStream = false)
                frameBitmap.recycle()
                onProgress((index + 1).toFloat() / totalFrames.toFloat())
            }

            val eosBufferIndex = waitForInputBuffer(codec)
            codec.queueInputBuffer(
                eosBufferIndex,
                0,
                0,
                presentationTimeUs(totalFrames, request.fps),
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            drainEncoder(codec, state, endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching {
                if (state.started) {
                    muxer.stop()
                }
            }
            runCatching { muxer.release() }
        }

        VideoArtifact(
            id = outputDirectory.name,
            createdAtEpochMs = System.currentTimeMillis(),
            prompt = request.prompt.orEmpty(),
            styleStrength = request.styleStrength,
            mp4Path = outputFile.absolutePath,
            posterPath = posterFile.absolutePath,
            width = width,
            height = height,
            fps = completion.fps,
            durationMs = completion.durationMs,
            generationTimeMs = System.currentTimeMillis() - startedAt,
            seed = completion.seed,
        )
    }

    private fun stylize(source: Bitmap, prompt: String?, styleStrength: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val saturation = 1f + styleStrength * 0.35f
        val lift = ((prompt.orEmpty().hashCode().absoluteValue % 18) - 9) * styleStrength
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

        if (!prompt.isNullOrBlank() && styleStrength > 0f) {
            val overlayPaint = Paint().apply {
                color = promptOverlayColor(prompt, styleStrength)
            }
            canvas.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), overlayPaint)
        }
        return output
    }

    private fun promptOverlayColor(prompt: String, styleStrength: Float): Int {
        val hue = prompt.hashCode().absoluteValue % 360
        val hsv = floatArrayOf(hue.toFloat(), 0.2f + styleStrength * 0.35f, 1f)
        return Color.HSVToColor((20 + styleStrength * 36f).roundToInt().coerceIn(0, 96), hsv)
    }

    private fun renderFrame(
        source: Bitmap,
        width: Int,
        height: Int,
        progress: Float,
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val motion = cameraMotionVector(progress)
        val baseScale = max(width / source.width.toFloat(), height / source.height.toFloat())
        val scale = baseScale * motion.zoom
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val maxOffsetX = max(0f, (scaledWidth - width) / 2f)
        val maxOffsetY = max(0f, (scaledHeight - height) / 2f)
        val left = (width - scaledWidth) / 2f + motion.x * maxOffsetX
        val top = (height - scaledHeight) / 2f + motion.y * maxOffsetY

        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + scaledWidth, top + scaledHeight),
            paint,
        )
        return output
    }

    private fun cameraMotionVector(progress: Float): MotionVector =
        MotionVector(
            x = sin(progress * Math.PI).toFloat() * 0.06f,
            y = sin(progress * Math.PI * 0.5f).toFloat() * 0.03f,
            zoom = lerp(1.02f, 1.12f, progress),
        )

    private fun bitmapToI420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val ySize = width * height
        val uvSize = ySize / 4
        val data = ByteArray(ySize + uvSize * 2)
        var yIndex = 0
        var uIndex = ySize
        var vIndex = ySize + uvSize

        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = argb[y * width + x]
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                val yValue = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                val uValue = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val vValue = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)

                data[yIndex++] = yValue.toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    data[uIndex++] = uValue.toByte()
                    data[vIndex++] = vValue.toByte()
                }
            }
        }
        return data
    }

    private fun waitForInputBuffer(codec: MediaCodec): Int {
        while (true) {
            val inputBufferIndex = codec.dequeueInputBuffer(TimeoutUs)
            if (inputBufferIndex >= 0) {
                return inputBufferIndex
            }
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxerState: MuxerState,
        endOfStream: Boolean,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TimeoutUs)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER && !endOfStream -> return
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerState.started) {
                        muxerState.trackIndex = muxerState.muxer.addTrack(codec.outputFormat)
                        muxerState.muxer.start()
                        muxerState.started = true
                    }
                }

                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: return
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerState.started) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxerState.muxer.writeSampleData(muxerState.trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun presentationTimeUs(frameIndex: Int, fps: Int): Long =
        frameIndex * 1_000_000L / fps.toLong()

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount

    private data class MotionVector(
        val x: Float,
        val y: Float,
        val zoom: Float,
    )

    private data class MuxerState(
        val muxer: MediaMuxer,
        var trackIndex: Int = -1,
        var started: Boolean = false,
    )
}
