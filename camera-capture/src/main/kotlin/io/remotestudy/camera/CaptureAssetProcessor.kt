package io.remotestudy.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Processes the JPEG saved by the ImageCapture in BookCameraView's shared
 * UseCaseGroup. CameraX has already applied that group's ViewPort crop, so the
 * normalized regions below are relative to the upright cropped JPEG.
 */
internal object CaptureAssetProcessor {
    private const val BOOK_MAX_LONG_EDGE = 2_400
    private const val BOOK_JPEG_QUALITY = 92
    private const val THUMBNAIL_MAX_LONG_EDGE = 480
    private const val THUMBNAIL_JPEG_QUALITY = 55
    private const val PIXEL_BLOCK_SIZE = 24

    fun process(
        originalJpeg: File,
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
    ): CaptureAssets {
        var committedAssets: CaptureAssets? = null
        var processingFailure: Throwable? = null
        try {
            committedAssets = createAssets(originalJpeg, outputDir, assetId, capturedAtEpochMs)
            return committedAssets
        } catch (failure: Throwable) {
            processingFailure = failure
            throw failure
        } finally {
            if (originalJpeg.exists() && !originalJpeg.delete()) {
                committedAssets?.deleteFiles()
                val cleanupFailure = IOException("Unable to delete temporary camera original")
                if (processingFailure != null) {
                    processingFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private fun createAssets(
        originalJpeg: File,
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
    ): CaptureAssets {
        require(originalJpeg.isFile) { "temporary camera original is missing" }
        require(assetId.matches(Regex("[A-Za-z0-9._-]{1,96}"))) {
            "assetId may contain only letters, numbers, dot, underscore, and dash"
        }
        require(capturedAtEpochMs >= 0) { "capturedAtEpochMs must not be negative" }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("Unable to create capture output directory")
        }
        require(outputDir.isDirectory) { "outputDir must be a directory" }

        val thumbnailFile = File(outputDir, "${assetId}_thumbnail.jpg")
        val bookRoiFile = File(outputDir, "${assetId}_book.jpg")
        if (thumbnailFile.exists() || bookRoiFile.exists()) {
            throw IOException("Capture assets already exist for assetId=$assetId")
        }

        val thumbnailStaging = File.createTempFile("${assetId}_thumbnail_", ".part", outputDir)
        val bookStaging = File.createTempFile("${assetId}_book_", ".part", outputDir)
        var thumbnailCommitted = false
        var bookCommitted = false
        try {
            val orientation = readExifOrientation(originalJpeg)
            writeBookRoi(originalJpeg, orientation, bookStaging)
            writeThumbnail(originalJpeg, orientation, thumbnailStaging)

            if (!bookStaging.renameTo(bookRoiFile)) {
                throw IOException("Unable to commit book ROI")
            }
            bookCommitted = true
            if (!thumbnailStaging.renameTo(thumbnailFile)) {
                throw IOException("Unable to commit thumbnail")
            }
            thumbnailCommitted = true

            return CaptureAssets(
                assetId = assetId,
                capturedAtEpochMs = capturedAtEpochMs,
                thumbnailFile = thumbnailFile,
                bookRoiFile = bookRoiFile,
            )
        } catch (failure: Throwable) {
            if (thumbnailCommitted) thumbnailFile.delete()
            if (bookCommitted) bookRoiFile.delete()
            throw failure
        } finally {
            thumbnailStaging.delete()
            bookStaging.delete()
        }
    }

    private fun writeBookRoi(originalJpeg: File, orientation: Int, destination: File) {
        val bounds = decodeBounds(originalJpeg)
        val rawRegion = rawRegionForUprightNormalized(
            width = bounds.first,
            height = bounds.second,
            orientation = orientation,
            uprightRegion = CameraRegions.BOOK,
        )
        val sampleSize = sampleSizeFor(rawRegion.width(), rawRegion.height(), BOOK_MAX_LONG_EDGE)
        val decoder = BitmapRegionDecoder.newInstance(originalJpeg.absolutePath, false)
            ?: throw IOException("Unable to create JPEG region decoder")
        val rawCrop = try {
            decoder.decodeRegion(
                rawRegion,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: throw IOException("Unable to decode book ROI")
        } finally {
            decoder.recycle()
        }

        val uprightCrop = orientAndLimit(rawCrop, orientation, BOOK_MAX_LONG_EDGE)
        try {
            writeJpeg(uprightCrop, destination, BOOK_JPEG_QUALITY)
        } finally {
            uprightCrop.recycle()
        }
    }

    private fun writeThumbnail(originalJpeg: File, orientation: Int, destination: File) {
        val bounds = decodeBounds(originalJpeg)
        val sampleSize = sampleSizeFor(bounds.first, bounds.second, THUMBNAIL_MAX_LONG_EDGE)
        val sampledRaw = BitmapFactory.decodeFile(
            originalJpeg.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IOException("Unable to decode thumbnail source")
        val upright = orientAndLimit(sampledRaw, orientation, THUMBNAIL_MAX_LONG_EDGE)
        val composited = pixelateOutsideBook(upright)
        try {
            writeJpeg(composited, destination, THUMBNAIL_JPEG_QUALITY)
        } finally {
            composited.recycle()
            upright.recycle()
        }
    }

    private fun pixelateOutsideBook(source: Bitmap): Bitmap {
        val tinyWidth = max(1, source.width / PIXEL_BLOCK_SIZE)
        val tinyHeight = max(1, source.height / PIXEL_BLOCK_SIZE)
        val tiny = Bitmap.createScaledBitmap(source, tinyWidth, tinyHeight, true)
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val fullRect = Rect(0, 0, source.width, source.height)
        canvas.drawBitmap(tiny, null, fullRect, Paint().apply { isFilterBitmap = false })

        val bookSourceRect = Rect(
            floor(CameraRegions.BOOK.left * source.width).toInt(),
            floor(CameraRegions.BOOK.top * source.height).toInt(),
            ceil(CameraRegions.BOOK.right * source.width).toInt(),
            ceil(CameraRegions.BOOK.bottom * source.height).toInt(),
        )
        canvas.drawBitmap(
            source,
            bookSourceRect,
            RectF(bookSourceRect),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true },
        )
        if (tiny !== source) tiny.recycle()
        return output
    }

    private fun orientAndLimit(source: Bitmap, orientation: Int, maxLongEdge: Int): Bitmap {
        val matrix = orientationMatrix(orientation)
        val upright = if (matrix == null) {
            source
        } else {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
                if (it !== source) source.recycle()
            }
        }

        val longestEdge = max(upright.width, upright.height)
        if (longestEdge <= maxLongEdge) return upright
        val scale = maxLongEdge.toFloat() / longestEdge
        return Bitmap.createScaledBitmap(
            upright,
            max(1, (upright.width * scale).toInt()),
            max(1, (upright.height * scale).toInt()),
            true,
        ).also {
            if (it !== upright) upright.recycle()
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(
                -1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(
                -1f, 0f, 0f,
                0f, -1f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(
                1f, 0f, 0f,
                0f, -1f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(
                0f, 1f, 0f,
                1f, 0f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(
                0f, -1f, 0f,
                1f, 0f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(
                0f, -1f, 0f,
                -1f, 0f, 0f,
                0f, 0f, 1f,
            )
            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(
                0f, 1f, 0f,
                -1f, 0f, 0f,
                0f, 0f, 1f,
            )
            else -> return null
        }
        return Matrix().apply { setValues(values) }
    }

    private fun rawRegionForUprightNormalized(
        width: Int,
        height: Int,
        orientation: Int,
        uprightRegion: NormalizedRegion,
    ): Rect {
        val sourceCorners = listOf(
            sourcePoint(uprightRegion.left, uprightRegion.top, orientation),
            sourcePoint(uprightRegion.right, uprightRegion.top, orientation),
            sourcePoint(uprightRegion.left, uprightRegion.bottom, orientation),
            sourcePoint(uprightRegion.right, uprightRegion.bottom, orientation),
        )
        val left = floor(sourceCorners.minOf { it.first } * width).toInt().coerceIn(0, width - 1)
        val top = floor(sourceCorners.minOf { it.second } * height).toInt().coerceIn(0, height - 1)
        val right = ceil(sourceCorners.maxOf { it.first } * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(sourceCorners.maxOf { it.second } * height).toInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun sourcePoint(uprightX: Float, uprightY: Float, orientation: Int): Pair<Float, Float> =
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> (1f - uprightX) to uprightY
            ExifInterface.ORIENTATION_ROTATE_180 -> (1f - uprightX) to (1f - uprightY)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> uprightX to (1f - uprightY)
            ExifInterface.ORIENTATION_TRANSPOSE -> uprightY to uprightX
            ExifInterface.ORIENTATION_ROTATE_90 -> uprightY to (1f - uprightX)
            ExifInterface.ORIENTATION_TRANSVERSE -> (1f - uprightY) to (1f - uprightX)
            ExifInterface.ORIENTATION_ROTATE_270 -> (1f - uprightY) to uprightX
            else -> uprightX to uprightY
        }

    private fun readExifOrientation(file: File): Int = ExifInterface(file.absolutePath)
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IOException("Unable to read JPEG dimensions")
        }
        return options.outWidth to options.outHeight
    }

    private fun sampleSizeFor(width: Int, height: Int, maxLongEdge: Int): Int =
        max(1, ceil(max(width, height).toDouble() / maxLongEdge).toInt())

    private fun writeJpeg(bitmap: Bitmap, destination: File, quality: Int) {
        FileOutputStream(destination).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                throw IOException("JPEG encoder rejected capture asset")
            }
        }
    }

    private fun CaptureAssets.deleteFiles() {
        thumbnailFile.delete()
        bookRoiFile.delete()
    }
}
