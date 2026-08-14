package io.remotestudy.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureAssetProcessorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val testDirectory = File(context.cacheDir, "capture-processor-test-${System.nanoTime()}")

    @After
    fun cleanUp() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun originalIsDeletedAndSeparatedAssetsHaveBoundedDimensions() {
        assertTrue(testDirectory.mkdirs())
        val original = File(testDirectory, "original.jpg")
        createFixture(original)

        val assets = CaptureAssetProcessor.process(
            originalJpeg = original,
            outputDir = File(testDirectory, "assets"),
            assetId = "fixture-1",
            capturedAtEpochMs = 123,
        )

        assertFalse("full-frame temporary source must be deleted", original.exists())
        assertTrue(assets.thumbnailFile.isFile)
        assertTrue(assets.bookRoiFile.isFile)

        val thumbnail = checkNotNull(BitmapFactory.decodeFile(assets.thumbnailFile.absolutePath))
        val book = checkNotNull(BitmapFactory.decodeFile(assets.bookRoiFile.absolutePath))
        try {
            assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 480)
            assertTrue(maxOf(book.width, book.height) <= 2_400)
            assertTrue(
                "book ROI center should preserve the blue fixture",
                colorDistance(Color.BLUE, book.getPixel(book.width / 2, book.height / 2)) < 24,
            )

            val outsideA = thumbnail.getPixel(8, thumbnail.height / 2)
            val outsideB = thumbnail.getPixel(12, thumbnail.height / 2)
            assertTrue(
                "nearby context pixels should be strongly pixelated",
                colorDistance(outsideA, outsideB) < 18,
            )
        } finally {
            thumbnail.recycle()
            book.recycle()
        }
    }

    private fun createFixture(destination: File) {
        val width = 1_200
        val height = 1_600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val block = 20
        for (y in 0 until height step block) {
            for (x in 0 until width step block) {
                paint.color = if ((x / block + y / block) % 2 == 0) Color.BLACK else Color.WHITE
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + block).toFloat(), (y + block).toFloat(), paint)
            }
        }
        paint.color = Color.BLUE
        canvas.drawRect(
            RectF(
                CameraRegions.BOOK.left * width,
                CameraRegions.BOOK.top * height,
                CameraRegions.BOOK.right * width,
                CameraRegions.BOOK.bottom * height,
            ),
            paint,
        )
        destination.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        }
        bitmap.recycle()
    }

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs(Color.red(first) - Color.red(second)) +
            kotlin.math.abs(Color.green(first) - Color.green(second)) +
            kotlin.math.abs(Color.blue(first) - Color.blue(second))
}
