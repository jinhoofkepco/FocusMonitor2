package io.remotestudy.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Processes the complete 4:3 JPEG saved by BookCameraView. No screen-shaped
 * ViewPort crop is applied, so normalized regions are relative to the full upright frame.
 */
internal object CaptureAssetProcessor {
    private const val STANDARD_BOOK_MAX_LONG_EDGE = 4_000
    private const val ULTRA_BOOK_MAX_LONG_EDGE = 4_600
    private const val BOOK_JPEG_QUALITY = 95
    private const val THUMBNAIL_MAX_LONG_EDGE = 1_600
    private const val THUMBNAIL_JPEG_QUALITY = 85
    private const val CALIBRATION_MAX_LONG_EDGE = 4_000
    private const val CALIBRATION_JPEG_QUALITY = 95

    fun process(
        originalJpeg: File,
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
        bookRegion: BookRegion = BookRegion.DEFAULT,
        detailCaptureMode: DetailCaptureMode = DetailCaptureMode.STANDARD_12_MP,
        includeCalibration: Boolean = true,
    ): CaptureAssets {
        return processDual(
            fullFrameJpeg = originalJpeg,
            detailFrameJpeg = originalJpeg,
            outputDir = outputDir,
            assetId = assetId,
            capturedAtEpochMs = capturedAtEpochMs,
            bookRegion = bookRegion,
            detailCaptureMode = detailCaptureMode,
            includeCalibration = includeCalibration,
        )
    }

    fun processDual(
        fullFrameJpeg: File,
        detailFrameJpeg: File,
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
        bookRegion: BookRegion = BookRegion.DEFAULT,
        detailCaptureMode: DetailCaptureMode = DetailCaptureMode.STANDARD_12_MP,
        includeCalibration: Boolean = true,
    ): CaptureAssets {
        var committedAssets: CaptureAssets? = null
        var processingFailure: Throwable? = null
        try {
            committedAssets = createAssets(
                fullFrameJpeg, detailFrameJpeg, outputDir, assetId, capturedAtEpochMs,
                bookRegion, detailCaptureMode,
                includeCalibration,
            )
            return committedAssets
        } catch (failure: Throwable) {
            processingFailure = failure
            throw failure
        } finally {
            setOf(fullFrameJpeg, detailFrameJpeg).forEach { original ->
                if (original.exists() && !original.delete()) {
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
    }

    private fun createAssets(
        fullFrameJpeg: File,
        detailFrameJpeg: File,
        outputDir: File,
        assetId: String,
        capturedAtEpochMs: Long,
        bookRegion: BookRegion,
        detailCaptureMode: DetailCaptureMode,
        includeCalibration: Boolean,
    ): CaptureAssets {
        require(fullFrameJpeg.isFile) { "temporary 1x camera original is missing" }
        require(detailFrameJpeg.isFile) { "temporary 2x camera original is missing" }
        require(assetId.matches(Regex("[A-Za-z0-9._-]{1,96}"))) {
            "assetId may contain only letters, numbers, dot, underscore, and dash"
        }
        require(capturedAtEpochMs >= 0) { "capturedAtEpochMs must not be negative" }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("Unable to create capture output directory")
        }
        require(outputDir.isDirectory) { "outputDir must be a directory" }

        val thumbnailFile = File(outputDir, "${assetId}_thumbnail.jpg")
        val calibrationFile = if (includeCalibration) File(outputDir, "${assetId}_calibration.jpg") else null
        val bookRoiFile = File(outputDir, "${assetId}_book.jpg")
        if (thumbnailFile.exists() || calibrationFile?.exists() == true || bookRoiFile.exists()) {
            throw IOException("Capture assets already exist for assetId=$assetId")
        }

        val thumbnailStaging = File.createTempFile("${assetId}_thumbnail_", ".part", outputDir)
        val calibrationStaging = if (includeCalibration) {
            File.createTempFile("${assetId}_calibration_", ".part", outputDir)
        } else {
            null
        }
        val bookStaging = File.createTempFile("${assetId}_book_", ".part", outputDir)
        var thumbnailCommitted = false
        var calibrationCommitted = false
        var bookCommitted = false
        try {
            val fullOrientation = readExifOrientation(fullFrameJpeg)
            val detailOrientation = readExifOrientation(detailFrameJpeg)
            val bookMaxLongEdge = if (detailCaptureMode == DetailCaptureMode.ULTRA_50_MP) {
                ULTRA_BOOK_MAX_LONG_EDGE
            } else {
                STANDARD_BOOK_MAX_LONG_EDGE
            }
            writeBookRoi(
                detailFrameJpeg, detailOrientation, bookRegion.normalized(), bookStaging, bookMaxLongEdge,
            )
            writeThumbnail(
                fullFrameJpeg,
                fullOrientation,
                thumbnailStaging,
            )
            if (calibrationStaging != null) {
                writeCalibration(detailFrameJpeg, detailOrientation, calibrationStaging)
            }

            if (!bookStaging.renameTo(bookRoiFile)) {
                throw IOException("Unable to commit book ROI")
            }
            bookCommitted = true
            if (calibrationStaging != null && calibrationFile != null && !calibrationStaging.renameTo(calibrationFile)) {
                throw IOException("Unable to commit book calibration")
            }
            calibrationCommitted = calibrationFile != null
            if (!thumbnailStaging.renameTo(thumbnailFile)) {
                throw IOException("Unable to commit thumbnail")
            }
            thumbnailCommitted = true

            return CaptureAssets(
                assetId = assetId,
                capturedAtEpochMs = capturedAtEpochMs,
                thumbnailFile = thumbnailFile,
                bookCalibrationFile = calibrationFile,
                bookRoiFile = bookRoiFile,
            )
        } catch (failure: Throwable) {
            if (thumbnailCommitted) thumbnailFile.delete()
            if (calibrationCommitted) calibrationFile?.delete()
            if (bookCommitted) bookRoiFile.delete()
            throw failure
        } finally {
            thumbnailStaging.delete()
            calibrationStaging?.delete()
            bookStaging.delete()
        }
    }

    private fun writeCalibration(originalJpeg: File, orientation: Int, destination: File) {
        val bounds = decodeBounds(originalJpeg)
        val sampleSize = sampleSizeFor(bounds.first, bounds.second, CALIBRATION_MAX_LONG_EDGE)
        val sampled = BitmapFactory.decodeFile(
            originalJpeg.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IOException("Unable to decode 2x calibration source")
        val upright = orientAndLimit(sampled, orientation, CALIBRATION_MAX_LONG_EDGE)
        try {
            writeJpeg(upright, destination, CALIBRATION_JPEG_QUALITY)
        } finally {
            upright.recycle()
        }
    }

    private fun writeBookRoi(
        originalJpeg: File,
        orientation: Int,
        bookRegion: NormalizedRegion,
        destination: File,
        maxLongEdge: Int,
    ) {
        val bounds = decodeBounds(originalJpeg)
        val rawRegion = rawRegionForUprightNormalized(
            width = bounds.first,
            height = bounds.second,
            orientation = orientation,
            uprightRegion = bookRegion,
        )
        val sampleSize = sampleSizeFor(rawRegion.width(), rawRegion.height(), maxLongEdge)
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

        val uprightCrop = orientAndLimit(rawCrop, orientation, maxLongEdge)
        try {
            writeJpeg(uprightCrop, destination, BOOK_JPEG_QUALITY)
        } finally {
            uprightCrop.recycle()
        }
    }

    private fun writeThumbnail(
        originalJpeg: File,
        orientation: Int,
        destination: File,
    ) {
        val bounds = decodeBounds(originalJpeg)
        val sampleSize = sampleSizeFor(bounds.first, bounds.second, THUMBNAIL_MAX_LONG_EDGE)
        val sampledRaw = BitmapFactory.decodeFile(
            originalJpeg.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw IOException("Unable to decode thumbnail source")
        val upright = orientAndLimit(sampledRaw, orientation, THUMBNAIL_MAX_LONG_EDGE)
        try {
            // This private prototype uses the context image to check small face/eye motion.
            // Keep the complete 1x frame clear instead of obscuring everything outside the book.
            writeJpeg(upright, destination, THUMBNAIL_JPEG_QUALITY)
        } finally {
            upright.recycle()
        }
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
        bookCalibrationFile?.delete()
        bookRoiFile.delete()
    }
}
