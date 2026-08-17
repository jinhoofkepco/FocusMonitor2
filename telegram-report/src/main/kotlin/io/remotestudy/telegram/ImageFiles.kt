package io.remotestudy.telegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File

internal object ImageFiles {
    fun decodeUpright(file: File, maxLongEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid JPEG: $file" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = powerOfTwoSample(bounds.outWidth, bounds.outHeight, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options))
        val orientation = runCatching {
            @Suppress("DEPRECATION")
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transformed = transform(decoded, orientation)
        if (transformed !== decoded) decoded.recycle()
        // Do not allocate a second scaled Bitmap. Callers draw this sampled bitmap directly.
        return transformed
    }

    @Suppress("DEPRECATION")
    fun decodeRegion(file: File, region: NormalizedBookRegion): Bitmap {
        val decoder = requireNotNull(BitmapRegionDecoder.newInstance(file.absolutePath, false))
        try {
            val rect = Rect(
                (region.left * decoder.width).toInt().coerceIn(0, decoder.width - 1),
                (region.top * decoder.height).toInt().coerceIn(0, decoder.height - 1),
                (region.right * decoder.width).toInt().coerceIn(1, decoder.width),
                (region.bottom * decoder.height).toInt().coerceIn(1, decoder.height),
            )
            return requireNotNull(decoder.decodeRegion(rect, BitmapFactory.Options()))
        } finally {
            decoder.recycle()
        }
    }

    fun writeJpeg(bitmap: Bitmap, target: File, quality: Int) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.outputStream().buffered().use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it))
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun powerOfTwoSample(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        while (maxOf(width / (sample * 2), height / (sample * 2)) >= maxLongEdge) sample *= 2
        return sample
    }

    private fun transform(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
