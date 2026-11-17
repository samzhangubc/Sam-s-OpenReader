package org.sam.openreader

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*

data class Draft(val base: Book, val result: Book, val count: Long, val examples: List<String>)

suspend fun Library.draft(book: Book, rule: HeadingRule, find: String? = null, replacement: String = ""): Draft =
    withContext(Dispatchers.IO) {
        require(book.format == "txt" && book.permanent)
        val next = book.copy(revision = uid())
        val folder = revision(next).apply { mkdirs() }
        val source = File(revision(book), "text.utf8")
        val target = File(folder, "text.utf8")
        val samples = mutableListOf<String>()
        var count = 0L
        try {
            File(folder,"parent").writeText(book.revision)
            if (find == null) source.copyTo(target) else {
                require(find.isNotEmpty() && find.length <= 4096 && replacement.length <= 4096)
                var offset = 0L
                DataOutputStream(File(folder,"edits.idx").outputStream().buffered()).use { edits ->
                    source.bufferedReader().use { input -> target.bufferedWriter().use { out ->
                        val buffer = CharArray(32768)
                        var tail = ""
                        var outputSize = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            val text = tail + (if (n < 0) "" else String(buffer,0,n))
                            val limit = if (n < 0) text.length else (text.length-find.length+1).coerceAtLeast(0)
                            var pos = 0
                            while (pos < limit) {
                                val hit = text.indexOf(find, pos)
                                if (hit < 0 || hit >= limit) break
                                out.write(text, pos, hit-pos); out.write(replacement)
                                if (samples.size < 6) samples += text.substring((hit-18).coerceAtLeast(0),(hit+find.length+18).coerceAtMost(text.length)) + " → " + replacement
                                edits.writeLong(offset+hit); edits.writeInt(find.length); edits.writeInt(replacement.length)
                                count++; require(count <= 5_000_000)
                                outputSize += hit-pos + replacement.length
                                require(outputSize <= 512L*1024*1024)
                                pos = hit+find.length
                            }
                            val consumed = maxOf(pos,limit)
                            out.write(text, pos, consumed-pos); outputSize += consumed-pos
                            offset += consumed; tail = text.substring(consumed)
                            if (n < 0) break
                        }
                    } }
                }
            }
            TextFiles.publish(folder, book.title, rule)
            Draft(book,next,count,samples)
        } catch (e: Throwable) { folder.deleteRecursively(); throw e }
    }

fun textOffset(folder: File, location: String): Long {
    val loc = runCatching { JSONObject(location) }.getOrNull() ?: return 0
    val range = loc.optJSONObject("locations")
    val fragment = range?.optJSONArray("fragments")?.optString(0)?.removePrefix("s")?.toLongOrNull()
    if (fragment != null) return fragment
    val index = loc.optString("href").substringAfterLast('/').removePrefix("r").substringBefore(".").toIntOrNull() ?: return 0
    return RandomAccessFile(File(folder,"resources.idx"),"r").use { f ->
        if (index < 0 || index.toLong()*20 >= f.length()) return@use 0L
        f.seek(index.toLong()*20)
        val start = f.readLong()
        val end = if ((index+1L)*20 < f.length()) { f.seek((index+1L)*20); f.readLong() }
                  else File(folder,"text.length").let { if(it.exists()) it.readText().toLong() else start }
        start + ((end-start)*(range?.optDouble("progression",0.0) ?: 0.0)).toLong()
    }
}
fun textLocation(folder: File, offset: Long): String {
    val href = RandomAccessFile(File(folder,"resources.idx"),"r").use { f ->
        val slot = floorIndex(f,20,offset)
        "r" + slot + ".xhtml"
    }
    val anchor = RandomAccessFile(File(folder,"lines.idx"),"r").use { f ->
        if (f.length()==0L) 0L else { f.seek(16*floorIndex(f,16,offset)); f.readLong() }
    }
    return JSONObject().put("href",href).put("type","application/xhtml+xml")
        .put("locations",JSONObject().put("fragments",org.json.JSONArray().put("s"+anchor))).toString()
}
fun floorIndex(f: RandomAccessFile, stride: Long, offset: Long): Long {
    var lo=0L
    var hi=(f.length()/stride-1).coerceAtLeast(0)
    while (lo<hi) {
        val mid=(lo+hi+1)/2; f.seek(mid*stride)
        if(f.readLong()<=offset) lo=mid else hi=mid-1
    }
    return lo
}
private fun remap(folder: File, offset: Long): Long {
    val file=File(folder,"edits.idx")
    if (!file.exists()) return offset
    var delta=0L
    DataInputStream(file.inputStream().buffered()).use { f ->
        repeat((file.length()/16).toInt()) {
            val start=f.readLong(); val from=f.readInt(); val to=f.readInt()
            if(offset < start) return offset+delta
            if(offset < start+from) return start+delta
            delta+=to-from
        }
    }
    return offset+delta
}
suspend fun Library.applyDraft(d: Draft) = withContext(Dispatchers.IO) {
    val folder=revision(d.result)
    val old=revision(d.base)
    db.withTransaction {
        val live=requireNotNull(shelf.book(d.base.id))
        check(live.revision==d.base.revision)
        fun map(loc: String) = if(loc.isBlank()) "" else textLocation(folder,remap(folder,textOffset(old,loc)))
        val visual=map(live.visual)
        check(shelf.revise(live.id,live.revision,d.result.revision,visual)==1)
        shelf.speech(live.id,d.result.revision,map(live.spoken))
        val marks=shelf.allMarks(live.id)
        shelf.clearMarks(live.id)
        marks.forEach { shelf.mark(it.copy(location=map(it.location))) }
        d.result.copy(visual=visual)
    }
}
