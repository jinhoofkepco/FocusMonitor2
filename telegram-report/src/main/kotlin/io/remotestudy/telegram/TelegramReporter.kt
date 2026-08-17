package io.remotestudy.telegram

import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TelegramReporter(
    private val rootDirectory: File,
    private val config: TelegramConfig,
    private val commandHandler: TelegramCommandHandler,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val api = TelegramBotApi(config)
    private val queue = DiskUploadQueue(rootDirectory.resolve("upload-queue.jsonl"))
    private val archive = OriginalArchive(rootDirectory.resolve("originals"), config.originalBudgetBytes, zoneId)
    private val montageDir = rootDirectory.resolve("montages")
    private val detailDir = rootDirectory.resolve("details")
    private val parser = TelegramCommandParser()
    private val uploadExecutor = Executors.newSingleThreadExecutor { Thread(it, "telegram-upload") }
    private val pollingExecutor = Executors.newSingleThreadExecutor { Thread(it, "telegram-long-poll") }
    private val uploadRunning = AtomicBoolean(false)
    private val pollingRunning = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val montageIndex = mutableListOf<MontageIndexEntry>()
    private var composer: MontageComposer? = null
    private var montageSequence = 0
    private var sessionStartedAtEpochMs = 0L
    private var sessionStartedAtElapsedMs = 0L
    private var awayEvents = mutableListOf<AwayEvent>()

    init {
        rootDirectory.mkdirs()
        montageDir.mkdirs()
        detailDir.mkdirs()
        restoreSessionState()
    }

    fun start() {
        check(config.enabled) { "TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are required" }
        startUploadLoop()
        startPollingLoop()
    }

    fun cleanupPreviousSessionFiles() {
        synchronized(lock) {
            composer?.close()
            composer = null
            archive.startFreshSession()
            deleteUnqueuedGeneratedFiles()
            montageSequence = 0
            montageIndex.clear()
            awayEvents.clear()
            sessionStartedAtEpochMs = 0L
            sessionStartedAtElapsedMs = 0L
            persistSessionState()
        }
    }

    fun startFreshSession(startedAtEpochMs: Long = now(), startedAtElapsedMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            composer?.close()
            composer = null
            montageSequence = 0
            montageIndex.clear()
            awayEvents.clear()
            sessionStartedAtEpochMs = startedAtEpochMs
            sessionStartedAtElapsedMs = startedAtElapsedMs
            archive.startFreshSession()
            deleteUnqueuedGeneratedFiles()
            persistSessionState()
        }
        sendImmediate("공부 세션을 시작합니다")
    }

    /** Source is copied to the 300MB archive before this call returns. */
    fun recordCapture(cameraJpeg: File, capturedAtEpochMs: Long, elapsedRealtimeMs: Long) {
        synchronized(lock) {
            val archived = archive.store(cameraJpeg, capturedAtEpochMs)
            val activeComposer = composer ?: MontageComposer(config.cellsPerMontage, montageDir, zoneId).also {
                composer = it
            }
            activeComposer.add(archived.file, capturedAtEpochMs)
            if (activeComposer.isComplete) {
                val result = activeComposer.finish(++montageSequence)
                activeComposer.close()
                composer = null
                val elapsedMinutes = ((elapsedRealtimeMs - sessionStartedAtElapsedMs).coerceAtLeast(0L) / 60_000L)
                val includedAway = awayEvents.filter {
                    it.atEpochMs in result.firstCapturedAtEpochMs..result.lastCapturedAtEpochMs
                }
                val caption = buildCaption(result, elapsedMinutes, includedAway)
                queue.enqueue(UploadKind.PHOTO, result.file, caption, now())
                montageIndex += MontageIndexEntry(result.sequence, result.firstCapturedAtEpochMs, result.lastCapturedAtEpochMs)
                persistSessionState()
                wakeUploader()
            }
        }
    }

    fun recordAway(atEpochMs: Long, durationMs: Long) {
        synchronized(lock) { awayEvents += AwayEvent(atEpochMs, durationMs) }
        sendImmediate("자리 이탈 · ${formatTime(atEpochMs)} · ${durationMs / 1_000}초")
    }

    fun sendImmediate(message: String) {
        queue.enqueue(UploadKind.MESSAGE, null, message, now())
        wakeUploader()
    }

    fun sendIndex() {
        val text = synchronized(lock) {
            if (montageIndex.isEmpty()) "이번 세션에 전송한 몽타주가 없습니다"
            else buildString {
                append("이번 세션 몽타주 ${montageIndex.size}건\n")
                montageIndex.forEach {
                    append('#').append(it.sequence).append(" · ")
                        .append(formatTime(it.firstEpochMs)).append('–').append(formatTime(it.lastEpochMs)).append('\n')
                }
            }.trimEnd()
        }
        sendImmediate(text)
    }

    fun sendBookDetails(selection: BookSelection) {
        val requestTime = now()
        val matches = archive.select(selection, requestTime)
        if (matches.isEmpty()) {
            val requested = describeSelection(selection)
            val nearest = archive.nearestDescription(selection, requestTime)
            sendImmediate(if (nearest == null) "$requested 원본 없음" else "$requested 원본 없음 · 보관 중 가장 가까운 시각 $nearest")
            return
        }
        val limited = matches.take(MAX_DETAIL_FILES)
        if (matches.size > MAX_DETAIL_FILES) {
            sendImmediate("구간이 넓습니다, 앞 8장만 보냅니다")
        }
        val region = loadBookRegion()
        limited.forEach { archived ->
            val detail = archive.createBookCrop(archived, region, detailDir)
            // Detail is always DOCUMENT by type; this path cannot call sendPhoto.
            queue.enqueue(UploadKind.DOCUMENT, detail, "책 영역 · ${formatTime(archived.capturedAtEpochMs)}", now())
        }
        wakeUploader()
    }

    fun updateBookRegion(region: NormalizedBookRegion) {
        rootDirectory.resolve("book-region.txt").writeText(
            listOf(region.left, region.top, region.right, region.bottom).joinToString(","),
        )
    }

    fun finishSession(summary: String) {
        synchronized(lock) {
            composer?.takeIf { it.size > 0 }?.let { partial ->
                val result = partial.finish(++montageSequence)
                val elapsedMinutes = (SystemClock.elapsedRealtime() - sessionStartedAtElapsedMs).coerceAtLeast(0L) / 60_000L
                queue.enqueue(UploadKind.PHOTO, result.file, buildCaption(result, elapsedMinutes, emptyList()), now())
                montageIndex += MontageIndexEntry(result.sequence, result.firstCapturedAtEpochMs, result.lastCapturedAtEpochMs)
            }
            composer?.close()
            composer = null
            queue.enqueue(UploadKind.MESSAGE_AND_PIN, null, summary, now())
            persistSessionState()
        }
        wakeUploader()
    }

    fun pendingUploadCount(): Int = queue.size()

    private fun startUploadLoop() {
        if (!uploadRunning.compareAndSet(false, true)) return
        uploadExecutor.execute {
            while (!closed.get()) {
                val entry = queue.due(now())
                if (entry == null) {
                    val next = queue.nextWakeEpochMs()
                    val sleep = if (next == null) 1_000L else (next - now()).coerceIn(250L, 10_000L)
                    runCatching { Thread.sleep(sleep) }
                    continue
                }
                val success = runCatching { upload(entry) }.isSuccess
                if (success) {
                    queue.acknowledge(entry.id)
                    entry.filePath?.let { path ->
                        if (entry.kind == UploadKind.PHOTO || entry.kind == UploadKind.DOCUMENT) File(path).delete()
                    }
                } else {
                    queue.retry(entry.id, now())
                }
            }
            uploadRunning.set(false)
        }
    }

    private fun upload(entry: UploadEntry) {
        when (entry.kind) {
            UploadKind.PHOTO -> api.sendPhoto(requireFile(entry), entry.text)
            UploadKind.DOCUMENT -> api.sendDocument(requireFile(entry), entry.text)
            UploadKind.MESSAGE -> api.sendMessage(entry.text)
            UploadKind.MESSAGE_AND_PIN -> {
                val message = api.sendMessage(entry.text)
                api.pinChatMessage(requireNotNull(message.messageId))
            }
        }
    }

    private fun startPollingLoop() {
        if (!pollingRunning.compareAndSet(false, true)) return
        pollingExecutor.execute {
            var offset = loadOffset()
            while (!closed.get() && runCatching { api.deleteWebhook() }.isFailure) {
                runCatching { Thread.sleep(POLL_FAILURE_DELAY_MS) }
            }
            while (!closed.get()) {
                val updatesResult = runCatching { api.getUpdates(offset, 50) }
                if (updatesResult.isFailure) {
                    runCatching { Thread.sleep(POLL_FAILURE_DELAY_MS) }
                    continue
                }
                val updates = updatesResult.getOrThrow()
                for (update in updates) {
                    // Whitelist is deliberately the first per-update decision.
                    if (update.chatId != config.allowedChatId) {
                        offset = update.updateId + 1
                        commitOffset(offset)
                        continue
                    }
                    val text = update.text
                    if (text == null) {
                        offset = update.updateId + 1
                        commitOffset(offset)
                        continue
                    }
                    val handled = runCatching {
                        val command = parser.parse(text)
                        when (command) {
                            TelegramCommand.Index -> sendIndex()
                            is TelegramCommand.Book -> sendBookDetails(command.selection)
                            else -> commandHandler.handle(command)
                        }
                    }.isSuccess
                    if (!handled) break
                    offset = update.updateId + 1
                    commitOffset(offset)
                }
            }
            pollingRunning.set(false)
        }
    }

    private fun wakeUploader() {
        if (!uploadRunning.get()) startUploadLoop()
    }

    private fun requireFile(entry: UploadEntry): File = requireNotNull(entry.filePath).let(::File).also {
        require(it.isFile) { "Queued file was removed: $it" }
    }

    private fun deleteUnqueuedGeneratedFiles() {
        val protected = queue.snapshot().mapNotNull(UploadEntry::filePath).map(::File).map(File::getAbsolutePath).toSet()
        montageDir.listFiles()?.filterNot { it.absolutePath in protected }?.forEach(File::delete)
        detailDir.listFiles()?.filterNot { it.absolutePath in protected }?.forEach(File::delete)
    }

    private fun buildCaption(
        result: MontageComposer.MontageResult,
        elapsedMinutes: Long,
        away: List<AwayEvent>,
    ) = buildString {
        append('#').append(result.sequence).append(" · ")
            .append(formatTime(result.firstCapturedAtEpochMs)).append('–')
            .append(formatTime(result.lastCapturedAtEpochMs)).append(" · 공부 ")
            .append(elapsedMinutes).append("분 경과")
        if (away.isNotEmpty()) {
            append("\n이탈 ").append(away.size).append("회(")
            append(away.joinToString { "${formatTime(it.atEpochMs)}, ${it.durationMs / 1_000}초" })
            append(')')
        }
    }

    private fun loadBookRegion(): NormalizedBookRegion = runCatching {
        val values = rootDirectory.resolve("book-region.txt").readText().split(',').map(String::toFloat)
        NormalizedBookRegion(values[0], values[1], values[2], values[3])
    }.getOrDefault(NormalizedBookRegion.DEFAULT)

    private fun loadOffset() = rootDirectory.resolve("update-offset.txt").takeIf(File::isFile)
        ?.readText()?.trim()?.toLongOrNull() ?: 0L

    private fun commitOffset(offset: Long) {
        val target = rootDirectory.resolve("update-offset.txt")
        val temp = rootDirectory.resolve("update-offset.tmp")
        FileOutputStream(temp).use { output ->
            output.write(offset.toString().toByteArray())
            output.flush()
            output.fd.sync()
        }
        atomicReplace(temp, target)
    }

    private fun persistSessionState() {
        rootDirectory.resolve("session-index.txt").writeText(
            buildString {
                append(sessionStartedAtEpochMs).append(',').append(sessionStartedAtElapsedMs).append(',').append(montageSequence).append('\n')
                montageIndex.forEach { append(it.sequence).append(',').append(it.firstEpochMs).append(',').append(it.lastEpochMs).append('\n') }
            },
        )
    }

    private fun restoreSessionState() {
        val state = rootDirectory.resolve("session-index.txt")
        if (!state.isFile) return
        val lines = state.readLines().filter(String::isNotBlank)
        val header = lines.firstOrNull()?.split(',') ?: return
        if (header.size < 3) return
        sessionStartedAtEpochMs = header[0].toLongOrNull() ?: return
        sessionStartedAtElapsedMs = header[1].toLongOrNull() ?: 0L
        montageSequence = header[2].toIntOrNull() ?: 0
        montageIndex.clear()
        lines.drop(1).forEach { line ->
            val values = line.split(',')
            if (values.size >= 3) {
                val sequence = values[0].toIntOrNull()
                val first = values[1].toLongOrNull()
                val last = values[2].toLongOrNull()
                if (sequence != null && first != null && last != null) {
                    montageIndex += MontageIndexEntry(sequence, first, last)
                }
            }
        }
        montageSequence = maxOf(montageSequence, montageIndex.maxOfOrNull(MontageIndexEntry::sequence) ?: 0)
        val queuedSequence = queue.snapshot().asSequence()
            .filter { it.kind == UploadKind.PHOTO }
            .mapNotNull { PHOTO_SEQUENCE.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        montageSequence = maxOf(montageSequence, queuedSequence)
    }

    private fun atomicReplace(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun describeSelection(selection: BookSelection): String = when (selection) {
        is BookSelection.Minute -> "%02d:%02d".format(selection.hour, selection.minute)
        is BookSelection.Exact -> "%02d:%02d:%02d".format(selection.hour, selection.minute, selection.second)
        is BookSelection.Range -> "%02d:%02d-%02d:%02d".format(selection.startHour, selection.startMinute, selection.endHour, selection.endMinute)
        is BookSelection.RecentMinutes -> "최근 ${selection.minutes}분"
    }

    private fun formatTime(epochMs: Long): String = TIME.format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { composer?.close(); composer = null }
        uploadExecutor.shutdownNow()
        pollingExecutor.shutdownNow()
    }

    private data class MontageIndexEntry(val sequence: Int, val firstEpochMs: Long, val lastEpochMs: Long)
    private data class AwayEvent(val atEpochMs: Long, val durationMs: Long)

    private companion object {
        const val MAX_DETAIL_FILES = 8
        const val POLL_FAILURE_DELAY_MS = 3_000L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val PHOTO_SEQUENCE = Regex("^#(\\d+)")
    }
}
