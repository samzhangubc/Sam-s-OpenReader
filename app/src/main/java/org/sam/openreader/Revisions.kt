package org.sam.openreader

import androidx.room.withTransaction
import java.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Draft(val base: Book, val result: Book, val count: Long, val examples: List<String>)

suspend fun Library.draft(
    book: Book,
    rule: HeadingRule,
    find: String? = null,
    replacement: String = "",
    rules: List<EditRule>? = null,
): Draft =
    withContext(Dispatchers.IO) {
        require(book.format == "txt" && book.permanent)
        val next = book.copy(revision = uid())
        val folder = revision(next).apply { mkdirs() }
        val source = File(revision(book), "text.utf8")
        val target = File(folder, "text.utf8")
        val samples = mutableListOf<String>()
        var count = 0L
        try {
            File(folder, "parent").writeText(book.revision)
            File(revision(book), "encoding").takeIf { it.isFile }?.copyTo(File(folder, "encoding"))
            val batch = rules ?: find?.let { listOf(EditRule(it, replacement)) }.orEmpty()
            require(batch.size <= 32)
            if (batch.isEmpty()) source.copyTo(target)
            var input = source
            batch.forEachIndexed { i, edit ->
                val output = if (i == batch.lastIndex) target else File(folder, "pass-" + i)
                val editMap = File(folder, "edits." + i.toString().padStart(3, '0'))
                count += rewrite(input, output, editMap, edit, samples)
                if (input != source) input.delete()
                input = output
            }
            val mappedRule = remapManualDecisions(revision(book), target, folder, rule)
            TextFiles.publish(folder, book.title, mappedRule)
            Draft(book, next, count, samples)
        } catch (e: Throwable) {
            folder.deleteRecursively()
            throw e
        }
    }

fun textOffset(folder: File, location: String): Long {
    if (location.startsWith("t:")) {
        val parts = location.split(':')
        val line = parts.getOrNull(1)?.toLongOrNull() ?: return 0
        val byte = parts.getOrNull(2)?.toLongOrNull() ?: 0
        RandomAccessFile(File(folder, "lines.idx"), "r").use { f ->
            if (line < 0 || line * 16 >= f.length()) return 0
            f.seek(line * 16)
            val offset = f.readLong()
            val start = f.readLong()
            return RandomAccessFile(File(folder, "text.utf8"), "r").use { data ->
                data.seek(start)
                var units = 0L
                var left = byte.coerceIn(0, data.length() - start)
                val buf = ByteArray(8192)
                while (left > 0) {
                    val n = data.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (n <= 0) break
                    for (i in 0 until n) {
                        val c = buf[i].toInt() and 255
                        if (c < 128 || c >= 192) units += if (c >= 240) 2 else 1
                    }
                    left -= n
                }
                offset + units
            }
        }
    }
    val loc = runCatching { JSONObject(location) }.getOrNull() ?: return 0
    val range = loc.optJSONObject("locations")
    range
        ?.optLong("canonicalOffset", -1)
        ?.takeIf { it >= 0 }
        ?.let {
            return it
        }
    val fragment = range?.optJSONArray("fragments")?.optString(0)?.removePrefix("s")?.toLongOrNull()
    if (fragment != null) return fragment
    val index =
        loc.optString("href")
            .substringAfterLast('/')
            .removePrefix("r")
            .substringBefore(".")
            .toIntOrNull() ?: return 0
    return RandomAccessFile(File(folder, "resources.idx"), "r").use { f ->
        if (index < 0 || index.toLong() * 20 >= f.length()) return@use 0L
        f.seek(index.toLong() * 20)
        val start = f.readLong()
        val end =
            if ((index + 1L) * 20 < f.length()) {
                f.seek((index + 1L) * 20)
                f.readLong()
            } else
                File(folder, "text.length").let {
                    if (it.exists()) it.readText().toLong() else start
                }
        start + ((end - start) * (range?.optDouble("progression", 0.0) ?: 0.0)).toLong()
    }
}

internal data class IndexedTextLocation(
    val resource: Int,
    val anchor: Long,
    val canonicalOffset: Long,
    val progression: Double? = null,
    val before: String? = null,
    val highlight: String? = null,
    val after: String? = null,
)

internal fun indexedTextLocation(folder: File, offset: Long): IndexedTextLocation {
    var resource = 0
    var resourceStart = 0L
    var anchor = offset
    var before: String? = null
    var highlight: String? = null
    var after: String? = null
    var progression: Double? = null
    val segmentFile = File(folder, "segments.idx")
    if (hasCurrentSegmentIndex(folder) && segmentFile.length() >= 24) {
        RandomAccessFile(segmentFile, "r").use { f ->
            val i = floorIndex(f, 24, offset)
            f.seek(i * 24)
            anchor = f.readLong()
            val byteStart = f.readLong()
            resource = f.readInt()
            val byteLength = f.readInt().coerceIn(0, 32768)
            if (byteLength > 0) {
                val bytes = ByteArray(byteLength)
                RandomAccessFile(File(folder, "text.utf8"), "r").use { source ->
                    source.seek(byteStart)
                    source.readFully(bytes)
                }
                val part = String(bytes, Charsets.UTF_8)
                val at = (offset - anchor).toInt().coerceIn(0, part.length)
                val start = at.coerceAtMost(part.length)
                val end = minOf(part.length, start + 48)
                val selected = part.substring(start, end)
                if (selected.isNotBlank()) {
                    before = part.substring(maxOf(0, start - 48), start)
                    highlight = selected
                    after = part.substring(end, minOf(part.length, end + 24))
                }
            }
        }
    } else {
        RandomAccessFile(File(folder, "resources.idx"), "r").use { f ->
            val slot = floorIndex(f, 20, offset)
            f.seek(slot * 20)
            resourceStart = f.readLong()
            resource = slot.toInt()
            val nextStart =
                if ((slot + 1) * 20 < f.length()) {
                    f.seek((slot + 1) * 20)
                    f.readLong()
                } else File(folder, "text.length").takeIf { it.exists() }?.readText()?.toLongOrNull()
                    ?: resourceStart
            progression =
                ((offset - resourceStart).toDouble() / (nextStart - resourceStart).coerceAtLeast(1))
                    .coerceIn(0.0, 1.0)
        }
        RandomAccessFile(File(folder, "lines.idx"), "r").use { f ->
            if (f.length() > 0) {
                f.seek(16 * floorIndex(f, 16, offset))
                anchor = f.readLong()
            }
        }
    }
    return IndexedTextLocation(resource, anchor, offset, progression, before, highlight, after)
}

fun textLocation(folder: File, offset: Long): String {
    val position = indexedTextLocation(folder, offset)
    val locations = JSONObject()
        .put("canonicalOffset", position.canonicalOffset)
        .put("fragments", org.json.JSONArray().put("s" + position.anchor))
    position.progression?.let { locations.put("progression", it) }
    val locator = JSONObject()
        .put("href", "r${position.resource}.xhtml")
        .put("type", "application/xhtml+xml")
        .put("locations", locations)
    position.highlight?.let {
        locator.put(
            "text",
            JSONObject()
                .put("before", position.before.orEmpty())
                .put("highlight", it)
                .put("after", position.after.orEmpty()),
        )
    }
    return locator.toString()
}

fun floorIndex(f: RandomAccessFile, stride: Long, offset: Long): Long {
    var lo = 0L
    var hi = (f.length() / stride - 1).coerceAtLeast(0)
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        f.seek(mid * stride)
        if (f.readLong() <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

private fun remap(folder: File, offset: Long, reverse: Boolean = false): Long {
    val maps =
        folder.listFiles().orEmpty().filter { it.name.startsWith("edits.") }.sortedBy { it.name }
    return (if (reverse) maps.reversed() else maps).fold(offset) { position, file ->
        remapFile(file, position, reverse)
    }
}

private fun remapFile(file: File, offset: Long, reverse: Boolean = false): Long {
    var delta = 0L
    DataInputStream(file.inputStream().buffered()).use { f ->
        repeat((file.length() / 16).toInt()) {
            val source = f.readLong()
            val old = f.readInt()
            val new = f.readInt()
            val start = if (reverse) source - delta else source
            val from = if (reverse) new else old
            val to = if (reverse) old else new
            if (offset < start) return offset + delta
            if (offset < start + from) return start + delta
            delta += to - from
        }
    }
    return offset + delta
}

internal suspend fun remapManualDecisions(
    sourceFolder: File,
    target: File,
    resultFolder: File,
    rule: HeadingRule,
): HeadingRule {
    val decisions = rule.excluded + rule.included
    if (decisions.isEmpty()) return rule
    val sourceOffsets = RandomAccessFile(File(sourceFolder, "lines.idx"), "r").use { index ->
        decisions.associateWith { line ->
            val at = (line - 1L) * 16
            if (line < 1 || at + 8 > index.length()) null
            else {
                index.seek(at)
                index.readLong()
            }
        }
    }
    val maps = resultFolder.listFiles().orEmpty()
        .filter { it.name.startsWith("edits.") }
        .sortedBy { it.name }
    val requested =
        sourceOffsets.mapNotNull { (line, offset) ->
            offset?.let { old ->
                line to maps.fold(old) { at, file -> remapFile(file, at) }
            }
        }.sortedBy { it.second }
    val lineMap = mutableMapOf<Int, Int>()
    var request = 0
    var offset = 0L
    var lastLine = 1
    target.bufferedReader().use { input ->
        for (part in TextFiles.parts(input)) {
            currentCoroutineContext().ensureActive()
            lastLine = part.line
            val end = offset + part.text.length
            while (request < requested.size && requested[request].second <= end) {
                lineMap[requested[request].first] = part.line
                request++
            }
            offset = end + if (part.newline) 1 else 0
        }
    }
    while (request < requested.size) {
        lineMap[requested[request].first] = lastLine
        request++
    }
    fun remapped(lines: Set<Int>) = lines.mapTo(mutableSetOf()) { lineMap[it] ?: it }
    return rule.copy(excluded = remapped(rule.excluded), included = remapped(rule.included))
}

suspend fun Library.applyDraft(d: Draft) =
    withContext(Dispatchers.IO) {
        val folder = revision(d.result)
        val old = revision(d.base)
        db.withTransaction {
            val live = requireNotNull(shelf.book(d.base.id))
            check(live.revision == d.base.revision)
            val undo = File(old, "parent").takeIf { it.isFile }?.readText() == d.result.revision
            fun map(loc: String) =
                if (loc.isBlank()) ""
                else
                    textLocation(
                        folder,
                        remap(if (undo) old else folder, textOffset(old, loc), undo),
                    )
            val visual = map(live.visual)
            val progress =
                if (visual.isBlank()) live.progress
                else {
                    val length = File(folder, "text.length").readText().toLongOrNull() ?: 0L
                    if (length == 0L) 0.0
                    else (textOffset(folder, visual).toDouble() / length).coerceIn(0.0, 1.0)
                }
            check(shelf.revise(live.id, live.revision, d.result.revision, visual, progress) == 1)
            shelf.speech(live.id, d.result.revision, map(live.spoken), null)
            val marks = shelf.allMarks(live.id)
            shelf.clearMarks(live.id)
            marks.forEach { shelf.mark(it.copy(location = map(it.location))) }
            d.result.copy(visual = visual)
        }
    }
