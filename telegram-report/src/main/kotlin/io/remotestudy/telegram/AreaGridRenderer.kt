package io.remotestudy.telegram

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.util.UUID
import kotlin.math.max

internal object AreaGridRenderer {
    fun createGrid(source: File, outputDirectory: File): File {
        outputDirectory.mkdirs()
        val decoded = ImageFiles.decodeUpright(source, GRID_LONG_EDGE)
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        try {
            drawGrid(bitmap)
            return outputDirectory.resolve("area-grid-${UUID.randomUUID()}.jpg").also {
                ImageFiles.writeJpeg(bitmap, it, PREVIEW_QUALITY)
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun createCropPreview(
        source: File,
        region: NormalizedBookRegion,
        outputDirectory: File,
        rotationDegrees: Int = 0,
    ): File {
        outputDirectory.mkdirs()
        val bitmap = ImageFiles.rotate(
            ImageFiles.decodeUprightRegion(source, region, PREVIEW_LONG_EDGE),
            rotationDegrees,
        )
        try {
            return outputDirectory.resolve("area-preview-${UUID.randomUUID()}.jpg").also {
                ImageFiles.writeJpeg(bitmap, it, PREVIEW_QUALITY)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Shows the exact full-frame coordinate space used by the cropper.  Keeping
     * the grid and selection on the same upright bitmap makes calibration
     * visually verifiable before it is committed.
     */
    fun createSelectionPreview(
        source: File,
        region: NormalizedBookRegion,
        outputDirectory: File,
    ): File {
        outputDirectory.mkdirs()
        val decoded = ImageFiles.decodeUpright(source, GRID_LONG_EDGE)
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        try {
            drawGrid(bitmap)
            drawSelection(bitmap, region)
            return outputDirectory.resolve("area-selection-${UUID.randomUUID()}.jpg").also {
                ImageFiles.writeJpeg(bitmap, it, PREVIEW_QUALITY)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawGrid(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val cellWidth = bitmap.width / GRID_SIZE.toFloat()
        val cellHeight = bitmap.height / GRID_SIZE.toFloat()
        val base = max(2f, max(bitmap.width, bitmap.height) / 500f)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = base * 3f
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 255, 120)
            style = Paint.Style.STROKE
            strokeWidth = base
        }
        for (index in 0..GRID_SIZE) {
            val x = (cellWidth * index).coerceAtMost(bitmap.width.toFloat() - 1f)
            val y = (cellHeight * index).coerceAtMost(bitmap.height.toFloat() - 1f)
            canvas.drawLine(x, 0f, x, bitmap.height.toFloat(), shadow)
            canvas.drawLine(x, 0f, x, bitmap.height.toFloat(), line)
            canvas.drawLine(0f, y, bitmap.width.toFloat(), y, shadow)
            canvas.drawLine(0f, y, bitmap.width.toFloat(), y, line)
        }
        val textSize = (minOf(cellWidth, cellHeight) * 0.30f).coerceAtLeast(18f)
        val textShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            style = Paint.Style.FILL
            strokeWidth = base
        }
        val text = Paint(textShadow).apply { color = Color.WHITE }
        for (column in 0 until GRID_SIZE) {
            val label = ('A' + column).toString()
            val x = cellWidth * column + base * 3f
            val y = textSize + base * 2f
            canvas.drawText(label, x + base, y + base, textShadow)
            canvas.drawText(label, x, y, text)
        }
        for (row in 0 until GRID_SIZE) {
            val label = (row + 1).toString()
            val x = base * 3f
            val y = cellHeight * row + textSize + base * 2f
            canvas.drawText(label, x + base, y + base, textShadow)
            canvas.drawText(label, x, y, text)
        }
    }

    private fun drawSelection(bitmap: Bitmap, region: NormalizedBookRegion) {
        val canvas = Canvas(bitmap)
        val base = max(3f, max(bitmap.width, bitmap.height) / 260f)
        val left = region.left * bitmap.width
        val top = region.top * bitmap.height
        val right = region.right * bitmap.width
        val bottom = region.bottom * bitmap.height
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = base * 3f
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = base * 1.5f
        }
        canvas.drawRect(left, top, right, bottom, shadow)
        canvas.drawRect(left, top, right, bottom, outline)
    }

    private const val GRID_SIZE = 10
    private const val GRID_LONG_EDGE = 1_600
    private const val PREVIEW_LONG_EDGE = 1_600
    private const val PREVIEW_QUALITY = 90
}
