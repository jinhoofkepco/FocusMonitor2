package io.remotestudy.telegram

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Durable append-only JSONL journal. PUT/RETRY records replace the current value and ACK removes it.
 * Compaction is an atomic rename, so a process death cannot resurrect an acknowledged upload.
 */
class DiskUploadQueue(private val journal: File) {
    private val entries = linkedMapOf<String, UploadEntry>()
    private var appendedRecords = 0

    init {
        journal.parentFile?.mkdirs()
        replay()
    }

    @Synchronized
    fun enqueue(
        kind: UploadKind,
        file: File?,
        text: String,
        nowEpochMs: Long,
        replyMarkup: String? = null,
    ): UploadEntry {
        val entry = UploadEntry(
            id = UUID.randomUUID().toString(),
            kind = kind,
            filePath = file?.absolutePath,
            text = text,
            attempts = 0,
            nextAttemptEpochMs = nowEpochMs,
            replyMarkup = replyMarkup,
        )
        entries[entry.id] = entry
        append("PUT", entry)
        return entry
    }

    @Synchronized
    fun due(nowEpochMs: Long): UploadEntry? = entries.values.firstOrNull {
        it.nextAttemptEpochMs <= nowEpochMs
    }

    @Synchronized
    fun nextWakeEpochMs(): Long? = entries.values.minOfOrNull(UploadEntry::nextAttemptEpochMs)

    @Synchronized
    fun acknowledge(id: String) {
        if (entries.remove(id) == null) return
        appendRaw("{\"op\":\"ACK\",\"id\":\"${escape(id)}\"}")
        compactIfNeeded()
    }

    @Synchronized
    fun retry(id: String, nowEpochMs: Long): UploadEntry? {
        val current = entries[id] ?: return null
        val attempts = current.attempts + 1
        val delay = (2_000L shl attempts.coerceAtMost(7)).coerceAtMost(MAX_BACKOFF_MS)
        val updated = current.copy(attempts = attempts, nextAttemptEpochMs = nowEpochMs + delay)
        entries[id] = updated
        append("PUT", updated)
        compactIfNeeded()
        return updated
    }

    @Synchronized fun size(): Int = entries.size
    @Synchronized fun snapshot(): List<UploadEntry> = entries.values.toList()

    private fun replay() {
        if (!journal.isFile) return
        journal.forEachLine(StandardCharsets.UTF_8) { line ->
            val fields = decodeObject(line) ?: return@forEachLine
            val id = fields["id"] ?: return@forEachLine
            when (fields["op"]) {
                "ACK" -> entries.remove(id)
                "PUT" -> runCatching {
                    UploadEntry(
                        id = id,
                        kind = UploadKind.valueOf(fields.getValue("kind")),
                        filePath = fields["file"].orEmpty().ifBlank { null },
                        text = fields["text"].orEmpty(),
                        attempts = fields["attempts"]?.toInt() ?: 0,
                        nextAttemptEpochMs = fields["next"]?.toLong() ?: 0L,
                        replyMarkup = fields["markup"].orEmpty().ifBlank { null },
                    )
                }.getOrNull()?.let { entries[id] = it }
            }
            appendedRecords++
        }
    }

    private fun append(op: String, entry: UploadEntry) = appendRaw(
        "{\"op\":\"$op\",\"id\":\"${escape(entry.id)}\",\"kind\":\"${entry.kind}\"," +
            "\"file\":\"${escape(entry.filePath.orEmpty())}\",\"text\":\"${escape(entry.text)}\"," +
            "\"attempts\":\"${entry.attempts}\",\"next\":\"${entry.nextAttemptEpochMs}\"," +
            "\"markup\":\"${escape(entry.replyMarkup.orEmpty())}\"}",
    )

    private fun appendRaw(line: String) {
        FileOutputStream(journal, true).use { output ->
            output.writer(StandardCharsets.UTF_8).use { writer ->
                writer.append(line).append('\n')
                writer.flush()
                output.fd.sync()
            }
        }
        appendedRecords++
    }

    private fun compactIfNeeded() {
        if (appendedRecords < COMPACT_AFTER_RECORDS || appendedRecords < entries.size * 3) return
        val temp = File(journal.parentFile, journal.name + ".tmp")
        FileOutputStream(temp).use { output ->
            output.writer(StandardCharsets.UTF_8).use { writer ->
                entries.values.forEach { entry ->
                    writer.append(
                        "{\"op\":\"PUT\",\"id\":\"${escape(entry.id)}\",\"kind\":\"${entry.kind}\"," +
                            "\"file\":\"${escape(entry.filePath.orEmpty())}\",\"text\":\"${escape(entry.text)}\"," +
                            "\"attempts\":\"${entry.attempts}\",\"next\":\"${entry.nextAttemptEpochMs}\"," +
                            "\"markup\":\"${escape(entry.replyMarkup.orEmpty())}\"}\n",
                    )
                }
                writer.flush()
                output.fd.sync()
            }
        }
        runCatching {
            Files.move(temp.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temp.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        appendedRecords = entries.size
    }

    private fun decodeObject(line: String): Map<String, String>? {
        if (!line.startsWith('{') || !line.endsWith('}')) return null
        val result = mutableMapOf<String, String>()
        val regex = Regex("\\\"([^\\\"]+)\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"")
        regex.findAll(line).forEach { match ->
            result[match.groupValues[1]] = unescape(match.groupValues[2])
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var escaped = false
        value.forEach { char ->
            if (escaped) {
                append(when (char) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> char })
                escaped = false
            } else if (char == '\\') escaped = true else append(char)
        }
        if (escaped) append('\\')
    }

    private companion object {
        const val MAX_BACKOFF_MS = 5L * 60_000L
        const val COMPACT_AFTER_RECORDS = 128
    }
}
