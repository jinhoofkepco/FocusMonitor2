package io.remotestudy.telegram

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class OriginalArchive(
    private val directory: File,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    init {
        directory.mkdirs()
        cleanupOrphans()
    }

    /** Keeps only material captured on the current local calendar date. */
    @Synchronized
    fun startFreshSession(nowEpochMs: Long = System.currentTimeMillis()) = pruneExpired(nowEpochMs)

    @Synchronized
    fun store(
        cameraJpeg: File,
        capturedAtEpochMs: Long,
        bookRegion: NormalizedBookRegion,
    ): ArchivedOriginal {
        val target = directory.resolve("$capturedAtEpochMs.jpg")
        val bookTarget = directory.resolve("${capturedAtEpochMs}_book.jpg")
        val bookRegionTarget = directory.resolve("${capturedAtEpochMs}_book-region.txt")
        try {
            val book = ImageFiles.decodeUprightRegion(cameraJpeg, bookRegion, BOOK_LONG_EDGE)
            try { ImageFiles.writeJpeg(book, bookTarget, BOOK_JPEG_QUALITY) } finally { book.recycle() }
            writeBookRegion(bookRegionTarget, bookRegion)
            val bitmap = ImageFiles.decodeUpright(cameraJpeg, ORIGINAL_LONG_EDGE)
            try { ImageFiles.writeJpeg(bitmap, target, ORIGINAL_JPEG_QUALITY) } finally { bitmap.recycle() }
        } catch (failure: Throwable) {
            target.delete()
            bookTarget.delete()
            bookRegionTarget.delete()
            throw failure
        }
        pruneExpired(capturedAtEpochMs)
        return ArchivedOriginal(capturedAtEpochMs, target, bookTarget, bookRegion)
    }

    @Synchronized
    fun all(): List<ArchivedOriginal> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
        .mapNotNull { file ->
            file.nameWithoutExtension.toLongOrNull()?.let { epoch ->
                ArchivedOriginal(
                    epoch,
                    file,
                    directory.resolve("${epoch}_book.jpg").takeIf(File::isFile),
                    readBookRegion(directory.resolve("${epoch}_book-region.txt")),
                )
            }
        }
        .sortedBy(ArchivedOriginal::capturedAtEpochMs)

    @Synchronized
    fun select(selection: BookSelection, nowEpochMs: Long): List<ArchivedOriginal> {
        val all = all()
        if (all.isEmpty()) return emptyList()
        return when (selection) {
            is BookSelection.RecentMinutes -> all.filter {
                it.capturedAtEpochMs >= nowEpochMs - selection.minutes * 60_000L
            }
            is BookSelection.Exact -> nearestTo(
                all,
                todayAt(selection.hour, selection.minute, selection.second, nowEpochMs),
                toleranceMs = 6_000L,
            )?.let(::listOf).orEmpty()
            is BookSelection.Minute -> {
                val start = todayAt(selection.hour, selection.minute, 0, nowEpochMs)
                all.filter { it.capturedAtEpochMs in start until start + 60_000L }
            }
            is BookSelection.Range -> {
                val start = todayAt(selection.startHour, selection.startMinute, 0, nowEpochMs)
                var end = todayAt(selection.endHour, selection.endMinute, 59, nowEpochMs) + 999L
                if (end < start) end += 24L * 60L * 60_000L
                all.filter { it.capturedAtEpochMs in start..end }
            }
        }
    }

    @Synchronized
    fun nearestDescription(selection: BookSelection, nowEpochMs: Long): String? {
        val files = all()
        if (files.isEmpty()) return null
        val requested = when (selection) {
            is BookSelection.Minute -> todayAt(selection.hour, selection.minute, 0, nowEpochMs)
            is BookSelection.Exact -> todayAt(selection.hour, selection.minute, selection.second, nowEpochMs)
            is BookSelection.Range -> todayAt(selection.startHour, selection.startMinute, 0, nowEpochMs)
            is BookSelection.RecentMinutes -> nowEpochMs - selection.minutes * 60_000L
        }
        return nearestTo(files, requested, Long.MAX_VALUE)?.let { formatMinute(it.capturedAtEpochMs) }
    }

    @Synchronized
    fun createBookCrop(
        source: ArchivedOriginal,
        region: NormalizedBookRegion,
        outputDir: File,
        rotationDegrees: Int = 0,
    ): File {
        require(rotationDegrees in setOf(0, 90, 180, 270))
        outputDir.mkdirs()
        val target = outputDir.resolve("book-${source.capturedAtEpochMs}-${UUID.randomUUID()}.jpg")
        val matchingBookFile = source.bookFile
            ?.takeIf(File::isFile)
            ?.takeIf { source.bookRegion?.approximatelyEquals(region) == true }
        matchingBookFile?.takeIf { rotationDegrees == 0 }?.let {
            it.copyTo(target, overwrite = false)
            return target
        }
        val decoded = matchingBookFile?.let {
            ImageFiles.decodeUpright(it, BOOK_LONG_EDGE)
        } ?: ImageFiles.decodeUprightRegion(source.file, region, BOOK_LONG_EDGE)
        val bitmap = ImageFiles.rotate(decoded, rotationDegrees)
        try { ImageFiles.writeJpeg(bitmap, target, DETAIL_JPEG_QUALITY) } finally { bitmap.recycle() }
        return target
    }

    private fun pruneExpired(nowEpochMs: Long) {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        all().filter {
            Instant.ofEpochMilli(it.capturedAtEpochMs).atZone(zoneId).toLocalDate().isBefore(today)
        }.forEach { entry ->
            entry.file.delete()
            entry.bookFile?.delete()
            directory.resolve("${entry.capturedAtEpochMs}_book-region.txt").delete()
        }
    }

    /** Removes companions left behind if the process stopped mid-commit. */
    private fun cleanupOrphans() {
        directory.listFiles().orEmpty().forEach { file ->
            val epoch = when {
                file.name.endsWith("_book.jpg") -> file.name.removeSuffix("_book.jpg").toLongOrNull()
                file.name.endsWith("_book-region.txt") -> file.name.removeSuffix("_book-region.txt").toLongOrNull()
                file.name.endsWith(".part") || file.name.endsWith(".tmp") -> {
                    file.delete()
                    null
                }
                else -> null
            }
            if (epoch != null && !directory.resolve("$epoch.jpg").isFile) file.delete()
        }
    }

    private fun writeBookRegion(target: File, region: NormalizedBookRegion) {
        val temp = directory.resolve(target.name + ".part")
        temp.writeText(listOf(region.left, region.top, region.right, region.bottom).joinToString(","))
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun readBookRegion(file: File): NormalizedBookRegion? = runCatching {
        val values = file.takeIf(File::isFile)?.readText()?.split(',')?.map(String::toFloat)
            ?: return@runCatching null
        if (values.size != 4) return@runCatching null
        NormalizedBookRegion(values[0], values[1], values[2], values[3])
    }.getOrNull()

    private fun NormalizedBookRegion.approximatelyEquals(other: NormalizedBookRegion): Boolean =
        abs(left - other.left) < REGION_EPSILON &&
            abs(top - other.top) < REGION_EPSILON &&
            abs(right - other.right) < REGION_EPSILON &&
            abs(bottom - other.bottom) < REGION_EPSILON

    private fun todayAt(hour: Int, minute: Int, second: Int, referenceEpochMs: Long): Long {
        val date = Instant.ofEpochMilli(referenceEpochMs).atZone(zoneId).toLocalDate()
        return date.atTime(hour, minute, second).atZone(zoneId).toInstant().toEpochMilli()
    }

    private fun nearestTo(files: List<ArchivedOriginal>, epochMs: Long, toleranceMs: Long) = files
        .minByOrNull { abs(it.capturedAtEpochMs - epochMs) }
        ?.takeIf { abs(it.capturedAtEpochMs - epochMs) <= toleranceMs }

    private fun formatMinute(epochMs: Long): String = String.format(
        Locale.KOREA,
        "%02d:%02d",
        Instant.ofEpochMilli(epochMs).atZone(zoneId).hour,
        Instant.ofEpochMilli(epochMs).atZone(zoneId).minute,
    )

    private companion object {
        const val ORIGINAL_LONG_EDGE = 2_000
        const val ORIGINAL_JPEG_QUALITY = 90
        const val BOOK_LONG_EDGE = 4_000
        const val BOOK_JPEG_QUALITY = 95
        const val DETAIL_JPEG_QUALITY = 95
        const val REGION_EPSILON = 0.0001f
    }
}
