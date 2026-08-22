package io.remotestudy.student

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.remotestudy.detection.FrameEvidence
import io.remotestudy.telegram.NormalizedBookRegion
import kotlin.math.abs

/** One-fps coarse luminance signatures; no frame Bitmap allocation. */
internal class MotionAnalyzer(
    private val elapsedRealtime: () -> Long,
    private val regionProvider: () -> NormalizedBookRegion,
    private val listener: (FrameEvidence) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastAnalyzedMs = Long.MIN_VALUE
    private var baselinePresence: FloatArray? = null
    private var previousPresence: FloatArray? = null
    private var previousBook: FloatArray? = null

    override fun analyze(image: ImageProxy) {
        try {
            val now = elapsedRealtime()
            if (lastAnalyzedMs != Long.MIN_VALUE && now - lastAnalyzedMs < 1_000L) return
            lastAnalyzedMs = now
            val plane = image.planes.firstOrNull() ?: return
            val presence = signature(
                plane.buffer,
                image.width,
                image.height,
                plane.rowStride,
                plane.pixelStride,
                mapToSource(NormalizedBookRegion(0.05f, 0.05f, 0.95f, 0.48f), image.imageInfo.rotationDegrees),
            )
            val book = signature(
                plane.buffer,
                image.width,
                image.height,
                plane.rowStride,
                plane.pixelStride,
                mapToSource(regionProvider(), image.imageInfo.rotationDegrees),
            )
            val baseline = baselinePresence ?: presence.copyOf().also { baselinePresence = it }
            listener(
                FrameEvidence(
                    observedAtElapsedMs = now,
                    presenceDifference = difference(presence, baseline),
                    presenceMotion = previousPresence?.let { difference(presence, it) },
                    bookMovement = previousBook?.let { difference(book, it) },
                ),
            )
            previousPresence = presence
            previousBook = book
        } finally {
            image.close()
        }
    }

    fun resetBaseline() {
        baselinePresence = null
        previousPresence = null
        previousBook = null
    }

    private fun signature(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        region: NormalizedBookRegion,
    ): FloatArray {
        val left = (region.left * width).toInt().coerceIn(0, width - 1)
        val top = (region.top * height).toInt().coerceIn(0, height - 1)
        val right = (region.right * width).toInt().coerceIn(left + 1, width)
        val bottom = (region.bottom * height).toInt().coerceIn(top + 1, height)
        val result = FloatArray(GRID_X * GRID_Y)
        for (gy in 0 until GRID_Y) {
            val y = top + ((gy + 0.5f) * (bottom - top) / GRID_Y).toInt().coerceAtMost(bottom - top - 1)
            for (gx in 0 until GRID_X) {
                val x = left + ((gx + 0.5f) * (right - left) / GRID_X).toInt().coerceAtMost(right - left - 1)
                val index = y * rowStride + x * pixelStride
                result[gy * GRID_X + gx] = (buffer.get(index).toInt() and 0xff) / 255f
            }
        }
        return result
    }

    private fun difference(first: FloatArray, second: FloatArray): Float =
        first.indices.sumOf { abs(first[it] - second[it]).toDouble() }.toFloat() / first.size

    private fun mapToSource(region: NormalizedBookRegion, rotationDegrees: Int): NormalizedBookRegion {
        fun point(x: Float, y: Float): Pair<Float, Float> = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> y to 1f - x
            180 -> 1f - x to 1f - y
            270 -> 1f - y to x
            else -> x to y
        }
        val points = listOf(
            point(region.left, region.top), point(region.right, region.top),
            point(region.left, region.bottom), point(region.right, region.bottom),
        )
        return NormalizedBookRegion(
            points.minOf { it.first }, points.minOf { it.second },
            points.maxOf { it.first }, points.maxOf { it.second },
        )
    }

    private companion object {
        const val GRID_X = 16
        const val GRID_Y = 12
    }
}
