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
import kotlin.math.roundToInt

class TelegramReporter(
    private val rootDirectory: File,
    private val config: TelegramConfig,
    private val commandHandler: TelegramCommandHandler,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val api = TelegramBotApi(config)
    private val queue = DiskUploadQueue(rootDirectory.resolve("upload-queue.jsonl"))
    private val archive = OriginalArchive(rootDirectory.resolve("originals"), zoneId)
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

    /** Source is copied to today's disk archive before this call returns. */
    fun recordCapture(cameraJpeg: File, capturedAtEpochMs: Long, elapsedRealtimeMs: Long) {
        synchronized(lock) {
            val archived = archive.store(cameraJpeg, capturedAtEpochMs, loadBookRegion())
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
                queue.enqueue(
                    UploadKind.PHOTO,
                    result.file,
                    caption,
                    now(),
                    replyMarkup = montageButtons(result),
                )
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

    fun sendImmediate(message: String, replyMarkup: String? = null) {
        queue.enqueue(UploadKind.MESSAGE, null, message, now(), replyMarkup = replyMarkup)
        wakeUploader()
    }

    fun sendControlMenu() = sendImmediate(
        "원하는 기능을 누르세요. 기존 직접 명령도 그대로 사용할 수 있습니다.",
        CONTROL_MENU,
    )

    fun sendAreaGrid() {
        archive.startFreshSession(now())
        val source = archive.all().lastOrNull()
        if (source == null) {
            sendImmediate("설정할 사진이 없습니다. 공부를 시작해 첫 사진이 촬영된 뒤 다시 눌러주세요.")
            return
        }
        val grid = AreaGridRenderer.createGrid(source.file, detailDir)
        queue.enqueue(
            UploadKind.PHOTO,
            grid,
            "책 범위의 왼쪽·오른쪽 열과 위·아래 행을 보내세요.\n예: /area IJ 56\n10행 포함 예: /area IJ 5-10",
            now(),
        )
        wakeUploader()
    }

    fun sendAreaPreview(command: TelegramCommand.PreviewBookRegion) {
        archive.startFreshSession(now())
        val source = archive.all().lastOrNull()
        if (source == null) {
            sendImmediate("미리 볼 사진이 없습니다. 새 사진이 촬영된 뒤 다시 시도해주세요.")
            return
        }
        val preview = AreaGridRenderer.createCropPreview(
            source.file,
            command.region,
            detailDir,
            loadBookRotation(),
        )
        queue.enqueue(
            UploadKind.PHOTO,
            preview,
            "선택 영역 ${command.label} · 이 범위로 설정할까요?",
            now(),
            replyMarkup = areaConfirmation(command.region),
        )
        wakeUploader()
    }

    fun sendRotationMenu() = sendImmediate(
        "책 상세사진 회전 · 현재 ${loadBookRotation()}°\n전체 썸네일과 격자 사진은 회전하지 않습니다.",
        ROTATION_MENU,
    )

    fun updateBookRotation(degrees: Int) {
        require(degrees in setOf(0, 90, 180, 270))
        rootDirectory.resolve("book-rotation.txt").writeText(degrees.toString())
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
            val detail = archive.createBookCrop(archived, region, detailDir, loadBookRotation())
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
                queue.enqueue(
                    UploadKind.PHOTO,
                    result.file,
                    buildCaption(result, elapsedMinutes, emptyList()),
                    now(),
                    replyMarkup = montageButtons(result),
                )
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
            UploadKind.PHOTO -> api.sendPhoto(requireFile(entry), entry.text, entry.replyMarkup)
            UploadKind.DOCUMENT -> api.sendDocument(requireFile(entry), entry.text)
            UploadKind.MESSAGE -> api.sendMessage(entry.text, entry.replyMarkup)
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
            runCatching { api.setMyCommands() }
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
                    if (update.callbackQueryId != null) {
                        val handled = runCatching { handleCallback(update) }.isSuccess
                        if (!handled) break
                        offset = update.updateId + 1
                        commitOffset(offset)
                        continue
                    }
                    if (text == null) {
                        offset = update.updateId + 1
                        commitOffset(offset)
                        continue
                    }
                    val handled = runCatching {
                        val command = parser.parse(text)
                        when (command) {
                            TelegramCommand.Menu -> sendControlMenu()
                            TelegramCommand.ShowAreaGrid -> sendAreaGrid()
                            is TelegramCommand.PreviewBookRegion -> sendAreaPreview(command)
                            TelegramCommand.ShowBookRotation -> sendRotationMenu()
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

    private fun handleCallback(update: TelegramUpdate) {
        val callbackId = requireNotNull(update.callbackQueryId)
        val data = update.callbackData.orEmpty()
        BOOK_BUTTON.matchEntire(data)?.let { match ->
            val capturedAt = match.groupValues[1].toLong()
            val source = archive.all().firstOrNull { it.capturedAtEpochMs == capturedAt }
            if (source == null) {
                sendImmediate("선택한 사진은 날짜가 지나 학생폰에서 삭제됐습니다")
                runCatching { api.answerCallbackQuery(callbackId, "원본이 삭제됐습니다") }
                return
            }
            val detail = archive.createBookCrop(source, loadBookRegion(), detailDir, loadBookRotation())
            queue.enqueue(UploadKind.DOCUMENT, detail, "책 영역 · ${formatTime(source.capturedAtEpochMs)}", now())
            wakeUploader()
            runCatching { api.answerCallbackQuery(callbackId, "${formatTime(source.capturedAtEpochMs)} 책 사진 전송 중") }
            return
        }
        AREA_SET_BUTTON.matchEntire(data)?.let { match ->
            val values = match.groupValues.drop(1).map(String::toInt)
            val region = runCatching {
                NormalizedBookRegion(values[0] / 100f, values[1] / 100f, values[2] / 100f, values[3] / 100f)
            }.getOrNull()
            if (region == null) {
                runCatching { api.answerCallbackQuery(callbackId, "잘못된 영역입니다") }
                return
            }
            commandHandler.handle(TelegramCommand.SetBookRegion(region))
            runCatching { api.answerCallbackQuery(callbackId, "책 영역을 적용했습니다") }
            return
        }
        val command = when (data) {
            "cmd:start" -> TelegramCommand.Start
            "cmd:pause" -> TelegramCommand.Pause
            "cmd:resume" -> TelegramCommand.Resume
            "cmd:next" -> TelegramCommand.NextPhase
            "cmd:status" -> TelegramCommand.Status
            "cmd:settings" -> TelegramCommand.Settings
            "cmd:focus" -> TelegramCommand.Refocus
            "cmd:index" -> TelegramCommand.Index
            "cmd:recent" -> TelegramCommand.Book(BookSelection.RecentMinutes(5))
            "cmd:area", "area:grid" -> {
                sendAreaGrid()
                runCatching { api.answerCallbackQuery(callbackId, "10×10 격자를 보냈습니다") }
                return
            }
            "cmd:rotate" -> {
                sendRotationMenu()
                runCatching { api.answerCallbackQuery(callbackId, "회전 메뉴를 열었습니다") }
                return
            }
            "rotate:0" -> TelegramCommand.SetBookRotation(0)
            "rotate:90" -> TelegramCommand.SetBookRotation(90)
            "rotate:180" -> TelegramCommand.SetBookRotation(180)
            "rotate:270" -> TelegramCommand.SetBookRotation(270)
            "cmd:stop" -> return sendCallbackMenu(callbackId, "공부를 종료할까요?", CONFIRM_STOP_MENU)
            "cmd:restart" -> return sendCallbackMenu(callbackId, "현재 진행을 버리고 처음부터 다시 시작할까요?", CONFIRM_RESTART_MENU)
            "confirm:stop" -> TelegramCommand.Stop
            "confirm:restart" -> TelegramCommand.Restart
            "menu:time" -> return sendCallbackMenu(
                callbackId,
                "시간 조합을 선택하세요. 직접 설정은 /set 명상 공부 휴식 형식입니다.",
                TIME_MENU,
            )
            "set:0:40:15" -> TelegramCommand.SetSchedule(0, 40, 15)
            "set:5:40:15" -> TelegramCommand.SetSchedule(5, 40, 15)
            "set:5:50:10" -> TelegramCommand.SetSchedule(5, 50, 10)
            "menu:main" -> return sendCallbackMenu(callbackId, "원하는 기능을 누르세요.", CONTROL_MENU)
            else -> {
                runCatching { api.answerCallbackQuery(callbackId, "지원하지 않는 버튼입니다") }
                return
            }
        }
        when (command) {
            TelegramCommand.Index -> sendIndex()
            is TelegramCommand.Book -> sendBookDetails(command.selection)
            else -> commandHandler.handle(command)
        }
        runCatching { api.answerCallbackQuery(callbackId, "처리했습니다") }
    }

    private fun sendCallbackMenu(callbackId: String, text: String, replyMarkup: String) {
        sendImmediate(text, replyMarkup)
        runCatching { api.answerCallbackQuery(callbackId, "메뉴를 열었습니다") }
    }

    private fun montageButtons(result: MontageComposer.MontageResult): String = buildString {
        append("{\"inline_keyboard\":[[")
        result.capturedAtEpochMs.forEachIndexed { index, capturedAt ->
            if (index > 0) append(',')
            append("{\"text\":\"").append(index + 1)
                .append("\",\"callback_data\":\"book:").append(capturedAt).append("\"}")
        }
        append("]]}")
    }

    private fun areaConfirmation(region: NormalizedBookRegion): String =
        """{"inline_keyboard":[[{"text":"✅ 확정","callback_data":"area:set:${(region.left * 100).roundToInt()}:${(region.top * 100).roundToInt()}:${(region.right * 100).roundToInt()}:${(region.bottom * 100).roundToInt()}"},{"text":"↩ 다시 선택","callback_data":"area:grid"}]]}"""

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

    private fun loadBookRotation(): Int = rootDirectory.resolve("book-rotation.txt")
        .takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull()
        ?.takeIf { it in setOf(0, 90, 180, 270) } ?: DEFAULT_BOOK_ROTATION

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
        val BOOK_BUTTON = Regex("^book:(\\d{1,19})$")
        val AREA_SET_BUTTON = Regex("^area:set:(\\d{1,3}):(\\d{1,3}):(\\d{1,3}):(\\d{1,3})$")
        const val CONTROL_MENU = """{"inline_keyboard":[[{"text":"▶ 시작","callback_data":"cmd:start"},{"text":"⏸ 일시정지","callback_data":"cmd:pause"},{"text":"▶ 계속","callback_data":"cmd:resume"}],[{"text":"⏭ 다음 단계","callback_data":"cmd:next"},{"text":"⏹ 종료","callback_data":"cmd:stop"},{"text":"🔄 처음부터","callback_data":"cmd:restart"}],[{"text":"📊 현재 상태","callback_data":"cmd:status"},{"text":"📷 최근 5분","callback_data":"cmd:recent"},{"text":"🎯 초점","callback_data":"cmd:focus"}],[{"text":"📐 책 영역 설정","callback_data":"cmd:area"},{"text":"🔃 책 회전","callback_data":"cmd:rotate"}],[{"text":"⏱ 시간 설정","callback_data":"menu:time"},{"text":"🖼 사진 목록","callback_data":"cmd:index"},{"text":"⚙ 현재 설정","callback_data":"cmd:settings"}]]}"""
        const val ROTATION_MENU = """{"inline_keyboard":[[{"text":"0°","callback_data":"rotate:0"},{"text":"90°","callback_data":"rotate:90"},{"text":"180°","callback_data":"rotate:180"},{"text":"270°","callback_data":"rotate:270"}],[{"text":"‹ 기본 메뉴","callback_data":"menu:main"}]]}"""
        const val DEFAULT_BOOK_ROTATION = 180
        const val TIME_MENU = """{"inline_keyboard":[[{"text":"0·40·15","callback_data":"set:0:40:15"},{"text":"5·40·15","callback_data":"set:5:40:15"},{"text":"5·50·10","callback_data":"set:5:50:10"}],[{"text":"‹ 기본 메뉴","callback_data":"menu:main"}]]}"""
        const val CONFIRM_STOP_MENU = """{"inline_keyboard":[[{"text":"종료","callback_data":"confirm:stop"},{"text":"취소","callback_data":"menu:main"}]]}"""
        const val CONFIRM_RESTART_MENU = """{"inline_keyboard":[[{"text":"처음부터 시작","callback_data":"confirm:restart"},{"text":"취소","callback_data":"menu:main"}]]}"""
    }
}
