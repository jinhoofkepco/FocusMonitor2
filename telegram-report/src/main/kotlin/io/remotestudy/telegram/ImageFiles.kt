package io.remotestudy.telegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

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
        return decodeUprightRegion(file, region, Int.MAX_VALUE)
    }

    @Suppress("DEPRECATION")
    fun decodeUprightRegion(file: File, region: NormalizedBookRegion, maxLongEdge: Int): Bitmap {
        require(maxLongEdge > 0)
        val decoder = requireNotNull(BitmapRegionDecoder.newInstance(file.absolutePath, false))
        val orientation = readOrientation(file)
        try {
            val rect = rawRegionForUprightNormalized(
                decoder.width,
                decoder.height,
                orientation,
                region,
            )
            val raw = requireNotNull(
                decoder.decodeRegion(
                    rect,
                    BitmapFactory.Options().apply {
                        inSampleSize = powerOfTwoSample(rect.width(), rect.height(), maxLongEdge)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                ),
            )
            val upright = transform(raw, orientation)
            if (upright !== raw) raw.recycle()
            return limit(upright, maxLongEdge)
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

    /** Returns the source for 0 degrees; otherwise returns a rotated bitmap and recycles source. */
    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        require(degrees in setOf(0, 90, 180, 270))
        if (degrees == 0) return source
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { setRotate(degrees.toFloat()) },
            true,
        ).also { source.recycle() }
    }

    private fun powerOfTwoSample(width: Int, height: Int, maxLongEdge: Int): Int {
        var sample = 1
        while (maxOf(width / (sample * 2), height / (sample * 2)) >= maxLongEdge) sample *= 2
        return sample
    }

    private fun limit(source: Bitmap, maxLongEdge: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxLongEdge) return source
        val scale = maxLongEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * scale).toInt()),
            max(1, (source.height * scale).toInt()),
            true,
        ).also { if (it !== source) source.recycle() }
    }

    private fun readOrientation(file: File): Int = runCatching {
        @Suppress("DEPRECATION")
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun rawRegionForUprightNormalized(
        width: Int,
        height: Int,
        orientation: Int,
        region: NormalizedBookRegion,
    ): Rect {
        val corners = listOf(
            sourcePoint(region.left, region.top, orientation),
            sourcePoint(region.right, region.top, orientation),
            sourcePoint(region.left, region.bottom, orientation),
            sourcePoint(region.right, region.bottom, orientation),
        )
        val left = floor(corners.minOf { it.first } * width).toInt().coerceIn(0, width - 1)
        val top = floor(corners.minOf { it.second } * height).toInt().coerceIn(0, height - 1)
        val right = ceil(corners.maxOf { it.first } * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(corners.maxOf { it.second } * height).toInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun sourcePoint(x: Float, y: Float, orientation: Int): Pair<Float, Float> = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> (1f - x) to y
        ExifInterface.ORIENTATION_ROTATE_180 -> (1f - x) to (1f - y)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> x to (1f - y)
        ExifInterface.ORIENTATION_TRANSPOSE -> y to x
        ExifInterface.ORIENTATION_ROTATE_90 -> y to (1f - x)
        ExifInterface.ORIENTATION_TRANSVERSE -> (1f - y) to (1f - x)
        ExifInterface.ORIENTATION_ROTATE_270 -> (1f - y) to x
        else -> x to y
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
