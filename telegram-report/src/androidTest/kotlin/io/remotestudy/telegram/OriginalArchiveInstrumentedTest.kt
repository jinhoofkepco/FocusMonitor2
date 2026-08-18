package io.remotestudy.telegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OriginalArchiveInstrumentedTest {
    @Test fun storesBookFromCameraOriginalAndReusesItForButtonDetail() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "archive-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val cameraJpeg = root.resolve("camera.jpg")
            val source = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.WHITE)
            cameraJpeg.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 98, it) }
            source.recycle()

            val archive = OriginalArchive(root.resolve("archive"))
            val stored = archive.store(
                cameraJpeg,
                123_456L,
                NormalizedBookRegion(0.25f, 0.20f, 0.75f, 0.80f),
            )
            val book = requireNotNull(stored.bookFile)
            assertTrue(stored.file.isFile)
            assertTrue(book.isFile)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(book.absolutePath, bounds)
            assertEquals(600, bounds.outWidth)
            assertEquals(540, bounds.outHeight)

            val detail = archive.createBookCrop(
                stored,
                NormalizedBookRegion.DEFAULT,
                root.resolve("details"),
            )
            assertEquals(book.length(), detail.length())
            assertTrue(book.readBytes().contentEquals(detail.readBytes()))

            val grid = AreaGridRenderer.createGrid(stored.file, root.resolve("grid"))
            val gridBitmap = requireNotNull(BitmapFactory.decodeFile(grid.absolutePath))
            assertTrue(gridBitmap.width <= 1600 && gridBitmap.height <= 1600)
            gridBitmap.recycle()

            val preview = AreaGridRenderer.createCropPreview(
                stored.file,
                NormalizedBookRegion(0.1f, 0.1f, 0.8f, 0.8f),
                root.resolve("grid"),
            )
            assertTrue(preview.isFile && preview.length() > 0L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun removesAllPreviousCalendarDayOriginalsButKeepsToday() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "archive-day-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val zone = ZoneId.of("Asia/Seoul")
            val today = ZonedDateTime.of(2026, 8, 18, 0, 1, 0, 0, zone).toInstant().toEpochMilli()
            val yesterday = ZonedDateTime.of(2026, 8, 17, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
            val archiveDir = root.resolve("archive").apply { mkdirs() }
            archiveDir.resolve("$yesterday.jpg").writeBytes(byteArrayOf(1))
            archiveDir.resolve("${yesterday}_book.jpg").writeBytes(byteArrayOf(2))
            archiveDir.resolve("$today.jpg").writeBytes(byteArrayOf(3))
            archiveDir.resolve("${today}_book.jpg").writeBytes(byteArrayOf(4))

            val archive = OriginalArchive(archiveDir, zone)
            archive.startFreshSession(today)

            assertEquals(listOf(today), archive.all().map(ArchivedOriginal::capturedAtEpochMs))
            assertTrue(!archiveDir.resolve("$yesterday.jpg").exists())
            assertTrue(!archiveDir.resolve("${yesterday}_book.jpg").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
