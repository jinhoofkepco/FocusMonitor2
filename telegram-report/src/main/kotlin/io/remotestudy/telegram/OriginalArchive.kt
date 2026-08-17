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
    private val budgetBytes: Long,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    init { directory.mkdirs() }

    /** Deliberately clears previous-session material; Telegram remains the long-term archive. */
    @Synchronized
    fun startFreshSession() {
        directory.listFiles()?.forEach(File::delete)
    }

    @Synchronized
    fun store(cameraJpeg: File, capturedAtEpochMs: Long): ArchivedOriginal {
        val target = directory.resolve("$capturedAtEpochMs.jpg")
        val bitmap = ImageFiles.decodeUpright(cameraJpeg, ORIGINAL_LONG_EDGE)
        try { ImageFiles.writeJpeg(bitmap, target, ORIGINAL_JPEG_QUALITY) } finally { bitmap.recycle() }
        prune()
        return ArchivedOriginal(capturedAtEpochMs, target)
    }

    @Synchronized
    fun all(): List<ArchivedOriginal> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
        .mapNotNull { file -> file.nameWithoutExtension.toLongOrNull()?.let { ArchivedOriginal(it, file) } }
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
    fun createBookCrop(source: ArchivedOriginal, region: NormalizedBookRegion, outputDir: File): File {
        outputDir.mkdirs()
        val target = outputDir.resolve("book-${source.capturedAtEpochMs}-${UUID.randomUUID()}.jpg")
        val bitmap = ImageFiles.decodeRegion(source.file, region)
        try { ImageFiles.writeJpeg(bitmap, target, DETAIL_JPEG_QUALITY) } finally { bitmap.recycle() }
        return target
    }

    private fun prune() {
        val files = all().toMutableList()
        var bytes = files.sumOf { it.file.length() }
        for (entry in files) {
            if (bytes <= budgetBytes) break
            val length = entry.file.length()
            if (entry.file.delete()) bytes -= length
        }
    }

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
        const val DETAIL_JPEG_QUALITY = 95
    }
}
