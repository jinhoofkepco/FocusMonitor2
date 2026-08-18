package io.remotestudy.telegram

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.sqrt

/** Holds exactly one canvas and one decoded source cell at a time. */
class MontageComposer(
    private val expectedCells: Int,
    private val outputDir: File,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : Closeable {
    private val columns = ceil(sqrt(expectedCells.toDouble())).toInt()
    private val rows = ceil(expectedCells.toDouble() / columns).toInt()
    private val canvasBitmap = Bitmap.createBitmap(columns * CELL_WIDTH, rows * CELL_HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap).apply { drawColor(Color.BLACK) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private var count = 0
    private val times = mutableListOf<Long>()
    private var closed = false

    val isComplete: Boolean get() = count >= expectedCells
    val size: Int get() = count

    fun add(jpeg: File, capturedAtEpochMs: Long) {
        check(!closed && !isComplete)
        val cell = ImageFiles.decodeUpright(jpeg, CELL_LONG_EDGE)
        try {
            val column = count % columns
            val row = count / columns
            val cellRect = Rect(column * CELL_WIDTH, row * CELL_HEIGHT, (column + 1) * CELL_WIDTH, (row + 1) * CELL_HEIGHT)
            val scale = minOf(cellRect.width().toFloat() / cell.width, cellRect.height().toFloat() / cell.height)
            val width = (cell.width * scale).toInt()
            val height = (cell.height * scale).toInt()
            val left = cellRect.left + (cellRect.width() - width) / 2
            val top = cellRect.top + (cellRect.height() - height) / 2
            canvas.drawBitmap(cell, null, Rect(left, top, left + width, top + height), null)
            val label = TIME.format(Instant.ofEpochMilli(capturedAtEpochMs).atZone(zoneId))
            val labelWidth = labelPaint.measureText(label)
            canvas.drawRect(cellRect.left.toFloat(), cellRect.top.toFloat(), cellRect.left + labelWidth + 20f, cellRect.top + 44f, LABEL_BACKGROUND)
            canvas.drawText(label, cellRect.left + 10f, cellRect.top + 34f, labelPaint)
        } finally {
            cell.recycle()
        }
        times += capturedAtEpochMs
        count++
    }

    fun finish(sequence: Int): MontageResult {
        check(!closed && count > 0)
        outputDir.mkdirs()
        val file = outputDir.resolve("montage-${times.first()}-${sequence.toString().padStart(5, '0')}.jpg")
        ImageFiles.writeJpeg(canvasBitmap, file, 80)
        return MontageResult(sequence, times.first(), times.last(), file, count, times.toList())
    }

    override fun close() {
        if (closed) return
        closed = true
        canvasBitmap.recycle()
    }

    data class MontageResult(
        val sequence: Int,
        val firstCapturedAtEpochMs: Long,
        val lastCapturedAtEpochMs: Long,
        val file: File,
        val cells: Int,
        val capturedAtEpochMs: List<Long>,
    )

    private companion object {
        const val CELL_LONG_EDGE = 400
        const val CELL_WIDTH = 400
        const val CELL_HEIGHT = 400
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val LABEL_BACKGROUND = Paint().apply { color = Color.argb(190, 0, 0, 0) }
    }
}
