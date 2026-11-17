package org.sam.openreader

import androidx.room.withTransaction
import java.io.*
import kotlinx.coroutines.Dispatchers
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
            TextFiles.publish(folder, book.title, rule)
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

fun textLocation(folder: File, offset: Long): String {
    var resourceStart = 0L
    val href =
        RandomAccessFile(File(folder, "resources.idx"), "r").use { f ->
            val slot = floorIndex(f, 20, offset)
            f.seek(slot*20)
            resourceStart=f.readLong()
            "r" + slot + ".xhtml"
        }
    val anchor =
        RandomAccessFile(File(folder, "lines.idx"), "r").use { f ->
            if (f.length() == 0L) 0L
            else {
                f.seek(16 * floorIndex(f, 16, offset))
                f.readLong()
            }
        }
    return JSONObject()
        .put("href", href)
        .put("type", "application/xhtml+xml")
        .put(
            "locations",
            JSONObject()
                .put("canonicalOffset", offset)
                .put("fragments", org.json.JSONArray().put("s" + maxOf(anchor,resourceStart))),
        )
        .toString()
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

private fun remapFile(file: File, offset: Long, reverse: Boolean): Long {
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
            check(shelf.revise(live.id, live.revision, d.result.revision, visual) == 1)
            shelf.speech(live.id, d.result.revision, map(live.spoken))
            val marks = shelf.allMarks(live.id)
            shelf.clearMarks(live.id)
            marks.forEach { shelf.mark(it.copy(location = map(it.location))) }
            d.result.copy(visual = visual)
        }
    }
