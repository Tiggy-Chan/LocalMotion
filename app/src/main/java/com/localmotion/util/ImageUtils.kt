package com.localmotion.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream

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
}

