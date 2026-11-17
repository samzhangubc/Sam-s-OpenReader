package org.sam.openreader

import com.google.re2j.Pattern
import java.io.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class EditRule(
    val find: String,
    val replacement: String,
    val regex: Boolean = false,
    val sensitive: Boolean = true,
    val word: Boolean = false,
)

suspend fun rewrite(
    source: File,
    target: File,
    map: File,
    rule: EditRule,
    samples: MutableList<String>,
): Long {
    require(rule.find.isNotEmpty() && rule.find.length <= 4096 && rule.replacement.length <= 4096)
    var count = 0L
    var written = 0L
    DataOutputStream(map.outputStream().buffered()).use { edits ->
        source.bufferedReader().use { input ->
            target.bufferedWriter().use { out ->
                fun emit(s: String) {
                    written += s.length
                    require(written <= 512L * 1024 * 1024)
                    out.write(s)
                }
                fun hit(start: Long, length: Int, repl: String, sample: String) {
                    require(length > 0 && ++count <= 5_000_000)
                    edits.writeLong(start)
                    edits.writeInt(length)
                    edits.writeInt(repl.length)
                    if (samples.size < 6) samples += sample.take(150) + " → " + repl.take(80)
                    emit(repl)
                }
                var offset = 0L
                if (rule.regex || rule.word) {
                    // Whole-line RE2 matching bounds runtime and preserves capture semantics.
                    val expr = if (rule.regex) rule.find else Pattern.quote(rule.find)
                    val pattern =
                        Pattern.compile(
                            if (rule.word) "\\b(?:" + expr + ")\\b" else expr,
                            if (rule.sensitive) 0 else Pattern.CASE_INSENSITIVE,
                        )
                    require(!pattern.matcher("").find())
                    val captures = Regex("""\$(\d+)""")
                    val writer = StringBuilder()
                    fun line(text: String) {
                        val matcher = pattern.matcher(text)
                        var pos = 0
                        while (matcher.find()) {
                            require(matcher.end() > matcher.start())
                            emit(text.substring(pos, matcher.start()))
                            val repl =
                                if (rule.regex)
                                    captures.replace(rule.replacement) { m ->
                                        val group = m.groupValues[1].toInt()
                                        require(group <= matcher.groupCount())
                                        matcher.group(group).orEmpty()
                                    }
                                else rule.replacement
                            hit(
                                offset + matcher.start(),
                                matcher.end() - matcher.start(),
                                repl,
                                text.substring(
                                    (matcher.start() - 18).coerceAtLeast(0),
                                    (matcher.end() + 18).coerceAtMost(text.length),
                                ),
                            )
                            pos = matcher.end()
                        }
                        emit(text.substring(pos))
                    }
                    for (part in TextFiles.parts(input)) {
                        currentCoroutineContext().ensureActive()
                        writer.append(part.text)
                        require(writer.length <= 131072) { "128 Ki UTF-16" }
                        if (part.newline) {
                            line(writer.toString())
                            emit("\n")
                            offset += writer.length + 1
                            writer.setLength(0)
                        }
                    }
                    if (writer.isNotEmpty()) line(writer.toString())
                } else {
                    val buffer = CharArray(32768)
                    var tail = ""
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        val text = tail + (if (n < 0) "" else String(buffer, 0, n))
                        val limit =
                            if (n < 0) text.length
                            else (text.length - rule.find.length + 1).coerceAtLeast(0)
                        var pos = 0
                        while (pos < limit) {
                            val at = text.indexOf(rule.find, pos, ignoreCase = !rule.sensitive)
                            if (at < 0 || at >= limit) break
                            emit(text.substring(pos, at))
                            hit(
                                offset + at,
                                rule.find.length,
                                rule.replacement,
                                text.substring(
                                    (at - 18).coerceAtLeast(0),
                                    (at + rule.find.length + 18).coerceAtMost(text.length),
                                ),
                            )
                            pos = at + rule.find.length
                        }
                        val consumed = maxOf(pos, limit)
                        emit(text.substring(pos, consumed))
                        offset += consumed
                        tail = text.substring(consumed)
                        if (n < 0) break
                    }
                }
            }
        }
    }
    return count
}
