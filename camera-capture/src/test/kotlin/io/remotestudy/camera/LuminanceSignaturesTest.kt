package io.remotestudy.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class LuminanceSignaturesTest {
    @Test
    fun `identical signatures have zero difference`() {
        val signature = floatArrayOf(0f, 0.25f, 0.5f, 1f)

        assertEquals(
            0f,
            LuminanceSignatureComparator.meanAbsoluteDifference(signature, signature.copyOf()),
            0.0001f,
        )
    }

    @Test
    fun `black and white signatures have maximum normalized difference`() {
        assertEquals(
            1f,
            LuminanceSignatureComparator.meanAbsoluteDifference(
                FloatArray(24 * 24) { 0f },
                FloatArray(24 * 24) { 1f },
            ),
            0.0001f,
        )
    }

    @Test
    fun `extractor reads only the selected normalized region`() {
        val width = 8
        val height = 4
        val bytes = ByteArray(width * height) { index ->
            val x = index % width
            if (x < width / 2) 0 else 255.toByte()
        }

        val left = LuminanceSignatureExtractor.extract(
            luma = ByteBuffer.wrap(bytes),
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            region = NormalizedRegion(0f, 0f, 0.5f, 1f),
            gridSize = 2,
        )
        val right = LuminanceSignatureExtractor.extract(
            luma = ByteBuffer.wrap(bytes),
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            region = NormalizedRegion(0.5f, 0f, 1f, 1f),
            gridSize = 2,
        )

        assertEquals(0f, left.average().toFloat(), 0.0001f)
        assertEquals(1f, right.average().toFloat(), 0.0001f)
    }

    @Test
    fun `extractor honors buffer offset row stride and pixel stride`() {
        val width = 2
        val height = 2
        val rowStride = 6
        val pixelStride = 2
        val prefix = 3
        val bytes = ByteArray(prefix + rowStride * height)
        bytes[prefix + 0] = 0
        bytes[prefix + 2] = 64
        bytes[prefix + rowStride] = 128.toByte()
        bytes[prefix + rowStride + 2] = 255.toByte()
        val buffer = ByteBuffer.wrap(bytes).apply { position(prefix) }

        val signature = LuminanceSignatureExtractor.extract(
            luma = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            region = NormalizedRegion(0f, 0f, 1f, 1f),
            gridSize = 2,
        )

        assertEquals(0f, signature[0], 0.0001f)
        assertEquals(64f / 255f, signature[1], 0.0001f)
        assertEquals(128f / 255f, signature[2], 0.0001f)
        assertEquals(1f, signature[3], 0.0001f)
    }

    @Test
    fun `upright region maps back through ninety degree rotation`() {
        val source = NormalizedRegion(0.1f, 0.2f, 0.4f, 0.8f)
            .inSourceCoordinates(90)

        assertEquals(0.2f, source.left, 0.0001f)
        assertEquals(0.6f, source.top, 0.0001f)
        assertEquals(0.8f, source.right, 0.0001f)
        assertEquals(0.9f, source.bottom, 0.0001f)
    }

    @Test
    fun `upright regions map inside an offset viewport crop for every rotation`() {
        val upright = NormalizedRegion(0.25f, 0.25f, 0.5f, 0.75f)
        val crop = PixelRegion(left = 100, top = 200, right = 500, bottom = 800)

        assertEquals(PixelRegion(200, 350, 300, 650), upright.inSourceCrop(crop, 0))
        assertEquals(PixelRegion(200, 500, 400, 650), upright.inSourceCrop(crop, 90))
        assertEquals(PixelRegion(300, 350, 400, 650), upright.inSourceCrop(crop, 180))
        assertEquals(PixelRegion(200, 350, 400, 500), upright.inSourceCrop(crop, 270))
    }

    @Test
    fun `pixel extractor never samples outside viewport crop`() {
        val width = 10
        val height = 10
        val bytes = ByteArray(width * height) { 255.toByte() }
        for (y in 2 until 8) {
            for (x in 2 until 8) bytes[y * width + x] = 0
        }

        val signature = LuminanceSignatureExtractor.extract(
            luma = ByteBuffer.wrap(bytes),
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            region = PixelRegion(2, 2, 8, 8),
            gridSize = 3,
        )

        assertEquals(0f, signature.average().toFloat(), 0.0001f)
    }
}
