package io.remotestudy.camera

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Pure Kotlin normalized geometry used by the analysis and capture paths. */
internal data class NormalizedRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && right in 0f..1f && left < right)
        require(top in 0f..1f && bottom in 0f..1f && top < bottom)
    }

    /** Maps an upright/display region back to the unrotated image buffer. */
    fun inSourceCoordinates(rotationDegrees: Int): NormalizedRegion {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        require(normalizedRotation in setOf(0, 90, 180, 270)) {
            "rotationDegrees must be 0, 90, 180, or 270"
        }

        val corners = listOf(
            sourcePoint(left, top, normalizedRotation),
            sourcePoint(right, top, normalizedRotation),
            sourcePoint(left, bottom, normalizedRotation),
            sourcePoint(right, bottom, normalizedRotation),
        )
        return NormalizedRegion(
            left = corners.minOf { it.first }.coerceIn(0f, 1f),
            top = corners.minOf { it.second }.coerceIn(0f, 1f),
            right = corners.maxOf { it.first }.coerceIn(0f, 1f),
            bottom = corners.maxOf { it.second }.coerceIn(0f, 1f),
        )
    }

    /**
     * Maps this upright PreviewView region into an ImageProxy buffer region.
     * [sourceCrop] is the ViewPort cropRect expressed in unrotated buffer pixels.
     */
    fun inSourceCrop(sourceCrop: PixelRegion, rotationDegrees: Int): PixelRegion {
        val relativeSource = inSourceCoordinates(rotationDegrees)
        return PixelRegion(
            left = floor(sourceCrop.left + relativeSource.left * sourceCrop.width).toInt()
                .coerceIn(sourceCrop.left, sourceCrop.right - 1),
            top = floor(sourceCrop.top + relativeSource.top * sourceCrop.height).toInt()
                .coerceIn(sourceCrop.top, sourceCrop.bottom - 1),
            right = ceil(sourceCrop.left + relativeSource.right * sourceCrop.width).toInt()
                .coerceIn(sourceCrop.left + 1, sourceCrop.right),
            bottom = ceil(sourceCrop.top + relativeSource.bottom * sourceCrop.height).toInt()
                .coerceIn(sourceCrop.top + 1, sourceCrop.bottom),
        )
    }

    private fun sourcePoint(uprightX: Float, uprightY: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (rotationDegrees) {
            0 -> uprightX to uprightY
            90 -> uprightY to (1f - uprightX)
            180 -> (1f - uprightX) to (1f - uprightY)
            270 -> (1f - uprightY) to uprightX
            else -> error("rotation was validated")
        }
}

/** Pure pixel geometry. Right and bottom are exclusive, matching Android Rect. */
internal data class PixelRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0) { "pixel region origin must not be negative" }
        require(left < right && top < bottom) { "pixel region must have positive area" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun requireInside(width: Int, height: Int): PixelRegion = apply {
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(right <= width && bottom <= height) {
            "pixel region must stay inside image dimensions"
        }
    }
}

/**
 * Extracts a compact signature directly from a Y plane. This object has no
 * Android dependency, so its geometry and stride behavior are JVM-testable.
 */
internal object LuminanceSignatureExtractor {
    const val DEFAULT_GRID_SIZE = 24

    fun extract(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        region: NormalizedRegion,
        gridSize: Int = DEFAULT_GRID_SIZE,
    ): FloatArray = extract(
        luma = luma,
        width = width,
        height = height,
        rowStride = rowStride,
        pixelStride = pixelStride,
        region = region.inSourceCrop(PixelRegion(0, 0, width, height), rotationDegrees = 0),
        gridSize = gridSize,
    )

    fun extract(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        region: PixelRegion,
        gridSize: Int = DEFAULT_GRID_SIZE,
    ): FloatArray {
        require(width > 0 && height > 0)
        require(rowStride > 0 && pixelStride > 0)
        require(gridSize > 0)
        region.requireInside(width, height)

        val leftPx = region.left
        val topPx = region.top
        val rightPx = region.right
        val bottomPx = region.bottom
        val regionWidth = rightPx - leftPx
        val regionHeight = bottomPx - topPx
        val baseOffset = luma.position()
        val limit = luma.limit()

        return FloatArray(gridSize * gridSize).also { signature ->
            var signatureIndex = 0
            for (gridY in 0 until gridSize) {
                val y = (topPx + ((gridY + 0.5f) * regionHeight / gridSize).toInt())
                    .coerceIn(topPx, bottomPx - 1)
                for (gridX in 0 until gridSize) {
                    val x = (leftPx + ((gridX + 0.5f) * regionWidth / gridSize).toInt())
                        .coerceIn(leftPx, rightPx - 1)
                    val bufferIndex = baseOffset + y * rowStride + x * pixelStride
                    require(bufferIndex in baseOffset until limit) {
                        "Y plane strides point outside the supplied buffer"
                    }
                    signature[signatureIndex++] = (luma.get(bufferIndex).toInt() and 0xff) / 255f
                }
            }
        }
    }
}

/** Pure comparator shared by the camera analyzer and local JVM tests. */
internal object LuminanceSignatureComparator {
    fun meanAbsoluteDifference(first: FloatArray, second: FloatArray): Float {
        require(first.isNotEmpty()) { "signatures must not be empty" }
        require(first.size == second.size) { "signature sizes must match" }

        var difference = 0f
        for (index in first.indices) {
            difference += abs(first[index] - second[index])
        }
        return (difference / first.size).coerceIn(0f, 1f)
    }
}
