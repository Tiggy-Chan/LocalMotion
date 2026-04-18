package com.localmotion.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.absoluteValue

object ImageUtils {
    fun loadPreparedBitmap(
        context: Context,
        uri: Uri,
        size: Int = 512,
    ): Bitmap {
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            context.contentResolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } ?: error("Unable to decode bitmap from $uri")

        val cropped = centerCropSquare(original)
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    fun centerCropSquare(bitmap: Bitmap): Bitmap {
        val edge = minOf(bitmap.width, bitmap.height)
        val xOffset = (bitmap.width - edge) / 2
        val yOffset = (bitmap.height - edge) / 2
        return Bitmap.createBitmap(bitmap, xOffset, yOffset, edge, edge)
    }

    fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 92): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun base64ToBitmap(value: String?): Bitmap? {
        if (value.isNullOrBlank()) {
            return null
        }
        val bytes = Base64.decode(value, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun writePoster(bitmap: Bitmap, outputFile: java.io.File) {
        outputFile.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
    }

    fun clone(bitmap: Bitmap): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    fun drawScaled(bitmap: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, size, size), paint)
        return output
    }

    fun createPromptSeedBitmap(prompt: String, size: Int = 512): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val safePrompt = prompt.ifBlank { "SD1.5 prompt" }
        val promptHash = safePrompt.hashCode().absoluteValue

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                size.toFloat(),
                size.toFloat(),
                Color.HSVToColor(floatArrayOf((promptHash % 360).toFloat(), 0.52f, 0.28f)),
                Color.HSVToColor(floatArrayOf(((promptHash / 7) % 360).toFloat(), 0.44f, 0.82f)),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), backgroundPaint)

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 255, 255, 255)
        }
        repeat(5) { index ->
            val radius = size * (0.12f + index * 0.06f)
            val x = size * (0.18f + ((promptHash shr (index + 2)) and 0xF) / 22f)
            val y = size * (0.18f + ((promptHash shr (index + 6)) and 0xF) / 22f)
            canvas.drawCircle(x, y, radius, accentPaint)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.06f
        }
        val lines = safePrompt.chunked(18).take(4)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, size * 0.08f, size * (0.68f + index * 0.08f), textPaint)
        }
        return output
    }
}
