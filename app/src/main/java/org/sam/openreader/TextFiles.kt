package org.sam.openreader

import com.google.re2j.Pattern
import java.io.*
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class HeadingRule(
    val prefix: String = "第",
    val markers: String = "章|回|節|节|卷|部",
    val contains: String = "",
    val loose: Boolean = false,
    val bare: Boolean = false,
    val advanced: String = "",
    val excluded: Set<Int> = emptySet(),
    val included: Set<Int> = emptySet(),
    val blocks: List<Block> = emptyList(),
    val multiline: Boolean = false,
) {
    private val chinese = "[零〇一二兩两三四五六七八九十百千万萬亿億壹贰貳叁參肆伍陆陸柒捌玖拾佰仟]+"
    private val roman = "(?i:M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3}))"
    private val words = "(?i:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)"
    private val suffix = if (loose) ".{0,48}" else "(?:[\\s:：—-]+.{1,48})?"
    private val custom =
        Regex(
            Regex.escape(prefix) +
                "(?:[0-9]+|" +
                chinese +
                ")(?:" +
                markers
                    .split('|')
                    .filter { it.isNotEmpty() }
                    .joinToString("|") { Regex.escape(it) } +
                ")" +
                suffix
        )
    private val english =
        Regex(
            "(?i:chapter|part|section|book|volume)\\s+(?=[A-Za-z0-9])(?:[0-9]+|" +
                roman +
                "|" +
                words +
                ")" +
                suffix
        )
    private val numberOnly =
        Regex("[(（]?(?:[0-9]+(?:\\.[0-9]+)*|" + chinese + "|" + roman + ")[)、）]?")
    private val extra =
        advanced
            .takeIf { it.isNotBlank() }
            ?.let {
                require(it.length <= 256)
                Pattern.compile(it)
            }

    fun reason(raw: String, line: Int): String? {
        if (line in excluded || raw.isBlank() || raw.length > 240) return null
        if (line in included) return "+"
        val text =
            Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
                .trim('【', '】', '*', '—', ' ')
                .trim()
        if (text.isEmpty()) return null
        if (blocks.isNotEmpty()) return if (blockMatch(text, blocks)) "✓ " + blocks.size else null
        if (text.last() in "。！？.!?" || !text.contains(contains, true)) return null
        return when {
            custom.matches(text) &&
                Regex(chinese)
                    .find(text.removePrefix(prefix))
                    ?.takeIf { it.range.first == 0 }
                    ?.let { validChineseNumber(it.value) } != false -> prefix + " # " + markers
            english.matches(text) -> "Chapter / Part / Book"
            text.lowercase() in
                setOf(
                    "prologue",
                    "epilogue",
                    "preface",
                    "序章",
                    "序言",
                    "前言",
                    "楔子",
                    "番外",
                    "后记",
                    "後記",
                ) -> "§"
            bare && numberOnly.matches(text) -> "#"
            extra?.matcher(text)?.matches() == true -> advanced
            else -> null
        }
    }

    fun save(file: File) {
        val p = Properties()
        p.setProperty("multiline", multiline.toString())
        p.setProperty("blocks", blocks.size.toString())
        blocks.forEachIndexed { i, b ->
            p.setProperty("op" + i, b.op.name)
            p.setProperty("arg" + i, b.value)
        }
        listOf(
                "prefix" to prefix,
                "markers" to markers,
                "contains" to contains,
                "loose" to loose.toString(),
                "bare" to bare.toString(),
                "advanced" to advanced,
                "excluded" to excluded.joinToString(","),
                "included" to included.joinToString(","),
            )
            .forEach { (k, v) -> p.setProperty(k, v) }
        file.outputStream().use { p.store(it, null) }
    }

    companion object {
        fun load(file: File): HeadingRule {
            if (!file.exists()) return HeadingRule()
            val p = Properties().apply { file.inputStream().use { load(it) } }
            fun lines(k: String) =
                p.getProperty(k, "").split(",").mapNotNull { it.toIntOrNull() }.toSet()
            return HeadingRule(
                p.getProperty("prefix"),
                p.getProperty("markers"),
                p.getProperty("contains", ""),
                p.getProperty("loose").toBoolean(),
                p.getProperty("bare").toBoolean(),
                p.getProperty("advanced", ""),
                lines("excluded"),
                lines("included"),
                List(p.getProperty("blocks", "0").toInt().also { require(it in 0..32) }) {
                    Block(Op.valueOf(p.getProperty("op" + it)), p.getProperty("arg" + it, ""))
                },
                p.getProperty("multiline").toBoolean(),
            )
        }
    }
}

data class Heading(val line: Int, val title: String, val reason: String)

data class TextPart(val text: String, val line: Int, val first: Boolean, val newline: Boolean)

object TextFiles {
    val encodings = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "Big5", "windows-1252")

    fun guess(file: File): String {
        val bytes =
            file.inputStream().use {
                val b = ByteArray(3)
                it.read(b)
                b
            }
        return when {
            bytes[0] == (-1).toByte() && bytes[1] == (-2).toByte() -> "UTF-16LE"
            bytes[0] == (-2).toByte() && bytes[1] == (-1).toByte() -> "UTF-16BE"
            else -> "UTF-8"
        }
    }

    fun reader(file: File, charset: Charset) =
        InputStreamReader(
                file.inputStream(),
                charset
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT),
            )
            .buffered()

    fun sample(file: File, charset: Charset): String =
        reader(file, charset).use {
            val buf = CharArray(2000)
            val n = it.read(buf)
            if (n < 0) ""
            else String(buf, 0, n).removePrefix("\uFEFF").trimEnd { c -> c.isHighSurrogate() }
        }

    // Bound memory even for a book containing one unbroken physical line.
    fun parts(reader: Reader): Sequence<TextPart> = sequence {
        val input = PushbackReader(reader.buffered(), 2)
        val chunk = StringBuilder()
        var line = 1
        var first = true
        var cp = input.read()
        if (cp == 0xfeff) cp = input.read()
        while (cp != -1) {
            val ch = cp.toChar()
            if (ch == '\r' || ch == '\n') {
                if (ch == '\r') {
                    val following = input.read()
                    if (following != '\n'.code && following >= 0) input.unread(following)
                }
                yield(TextPart(chunk.toString(), line++, first, true))
                chunk.setLength(0)
                first = true
            } else {
                require(ch != '\u0000') { "NUL" }
                chunk.append(ch)
                if (chunk.length >= 8192 && !ch.isHighSurrogate()) {
                    yield(TextPart(chunk.toString(), line, first, false))
                    chunk.setLength(0)
                    first = false
                }
            }
            cp = input.read()
        }
        if (chunk.isNotEmpty()) yield(TextPart(chunk.toString(), line, first, false))
    }

    suspend fun decode(original: File, target: File, charset: Charset) {
        reader(original, charset).use { input ->
            target.bufferedWriter().use { out ->
                for (part in parts(input)) {
                    currentCoroutineContext().ensureActive()
                    out.write(part.text)
                    if (part.newline) out.write("\n")
                }
            }
        }
    }

    suspend fun headings(file: File, rule: HeadingRule): List<Heading> = buildList {
        var previous: Heading? = null
        file.bufferedReader().use { input ->
            for (part in parts(input)) {
                currentCoroutineContext().ensureActive()
                val reason = if (part.first) rule.reason(part.text, part.line) else null
                previous?.let { h ->
                    if (
                        rule.multiline &&
                            reason == null &&
                            part.first &&
                            part.text.length in 1..60 &&
                            part.text.last() !in "。！？.!?"
                    ) {
                        this[lastIndex] = h.copy(title = h.title + " · " + part.text)
                    }
                }
                previous = null
                if (reason != null) {
                    require(size < 50_000) { "50,000" }
                    previous = Heading(part.line, part.text, reason)
                    add(previous!!)
                }
            }
        }
    }

    private fun xml(s: String) =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "\uFFFD")

    suspend fun publish(folder: File, title: String, rule: HeadingRule) {
        val text = File(folder, "text.utf8")
        val map = headings(text, rule).associateBy { it.line }
        val nav = mutableListOf<Pair<Int, String>>()
        var count = 0
        var bytes = 0L
        var offset = 0L
        var start = 0L
        var section = 0
        var length = 0
        val xhtml =
            "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>" +
                xml(title) +
                "</title></head><body style='white-space:pre-wrap;overflow-wrap:anywhere'>"
        ZipOutputStream(File(folder, "reader.epub").outputStream().buffered()).use { zip ->
            fun write(s: String) = zip.write(s.toByteArray())
            fun entry(name: String, data: String) {
                zip.putNextEntry(ZipEntry(name))
                write(data)
                zip.closeEntry()
            }
            // EPUB OCF requires an uncompressed mimetype as the first local header.
            val mime = "application/epub+zip".toByteArray()
            val mt =
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = size
                    crc = java.util.zip.CRC32().apply { update(mime) }.value
                }
            zip.putNextEntry(mt)
            zip.write(mime)
            zip.closeEntry()
            entry(
                "META-INF/container.xml",
                "<container version='1.0' xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='content.opf' media-type='application/oebps-package+xml'/></rootfiles></container>",
            )
            DataOutputStream(File(folder, "resources.idx").outputStream().buffered()).use { index ->
                DataOutputStream(File(folder, "lines.idx").outputStream().buffered()).use { lines ->
                    fun begin(label: String?) {
                        if (count > 0) {
                            write("</body></html>")
                            zip.closeEntry()
                        }
                        if (label != null) {
                            nav += count to label
                            section = nav.size - 1
                        }
                        index.writeLong(offset)
                        index.writeLong(bytes)
                        index.writeInt(section)
                        zip.putNextEntry(ZipEntry("r" + count++ + ".xhtml"))
                        write(xhtml)
                        start = bytes
                        length = 0
                    }
                    text.bufferedReader().use { input ->
                        for (part in parts(input)) {
                            currentCoroutineContext().ensureActive()
                            val heading = map[part.line]?.takeIf { part.first }
                            if (count == 0 || heading != null || bytes - start >= 196608)
                                begin(heading?.title ?: if (count == 0) title else null)
                            if (part.first) {
                                lines.writeLong(offset)
                                lines.writeLong(bytes)
                            }
                            if (heading != null) {
                                write(
                                    "<span style='display:block;font-size:1.35em;font-weight:600;margin-bottom:0.5em'>"
                                )
                            }
                            write("<span id='s" + offset + "'>" + xml(part.text) + "</span>")
                            if (heading != null) write("</span><br/>")
                            if (part.newline) write("<br/>")
                            length += part.text.length
                            bytes += part.text.toByteArray().size + (if (part.newline) 1 else 0)
                            offset += part.text.length + (if (part.newline) 1 else 0)
                        }
                    }
                    if (count == 0) begin(title)
                    write("</body></html>")
                    zip.closeEntry()
                }
            }
            entry(
                "nav.xhtml",
                "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Contents</title></head><body><nav epub:type='toc'><ol>" +
                    nav.joinToString("") {
                        "<li><a href='r" + it.first + ".xhtml'>" + xml(it.second) + "</a></li>"
                    } +
                    "</ol></nav></body></html>",
            )
            entry(
                "content.opf",
                "<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='id'>urn:uuid:" +
                    folder.name +
                    "</dc:identifier><dc:title>" +
                    xml(title) +
                    "</dc:title><dc:language>und</dc:language><meta property='dcterms:modified'>2026-01-01T00:00:00Z</meta></metadata><manifest><item id='nav' href='nav.xhtml' properties='nav' media-type='application/xhtml+xml'/>" +
                    (0 until count).joinToString("") {
                        "<item id='r" +
                            it +
                            "' href='r" +
                            it +
                            ".xhtml' media-type='application/xhtml+xml'/>"
                    } +
                    "</manifest><spine>" +
                    (0 until count).joinToString("") { "<itemref idref='r" + it + "'/>" } +
                    "</spine></package>",
            )
        }
        rule.save(File(folder, "profile"))
        File(folder, "text.length").writeText(offset.toString())
    }
}
