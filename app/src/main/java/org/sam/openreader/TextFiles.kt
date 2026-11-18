package org.sam.openreader

import com.google.re2j.Pattern
import java.io.*
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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
    private val roman = "(?i:(?=[mdclxvi])M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3}))"
    private val words = "(?i:(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[- ](?:one|two|three|four|five|six|seven|eight|nine))?)"
    private val englishNumber = "(?:[0-9]+(?:\\.[0-9]+){0,5}|" + roman + "|" + words + ")"
    private val prefixes = prefix.split('|').map(String::trim).filter(String::isNotEmpty).sortedByDescending(String::length)
    private val headingNumber = "(?:[0-9]+(?:\\.[0-9]+){0,5}|" + chinese + "(?:[.．]" + chinese + "){0,5})"
    private val suffix = if (loose) ".{0,48}" else "(?:[\\s:：—-]+.{1,48})?"
    private val custom =
        Regex(
            "(?:" + prefixes.joinToString("|") {
                Regex.escape(it) +
                    if (it.lastOrNull()?.let { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' } == true)
                        "\\s+"
                    else ""
            } + ")" +
                headingNumber + "(?:" +
                markers
                    .split('|')
                    .filter { it.isNotEmpty() }
                    .joinToString("|") { Regex.escape(it) } +
                ")" +
                suffix
        )
    private val english =
        Regex(
            "(?i:^(?:chapter|part|section|book|volume)\\s+(?<number>" + englishNumber + ")" + suffix + "$)"
        )
    private val englishTail =
        Regex("(?i)^(?:chapter|part|section|book|volume)\\s+" + englishNumber + "(?<tail>.*)$")
    private val romanNumber = Regex(roman)
    private val sentenceLead =
        Regex(
            "(?i)^(?:was|is|are|were|has|have|had|will|would|can|could|should|must|does|do|did|" +
                "contains|explains|shows|describes|starts|ends|begins|remains|appears|seems|" +
                "he|she|they|we|i|you|it|this chapter|the reader)\\b"
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
        if (isContentsTitle(text)) return "contents"
        if (isFrontMatterTitle(text)) return "§"
        if (blocks.isNotEmpty()) return if (blockMatch(text, blocks)) "✓ " + blocks.size else null
        if (text.last() in "。！？.!?" || !text.contains(contains, true)) return null
        val englishMatch = english.matchEntire(text)
        return when {
            prefixes.isNotEmpty() && custom.matches(text) && prefixes.any { candidate ->
                if (!text.startsWith(candidate)) false
                else {
                    val rest = text.drop(candidate.length).dropWhile(Char::isWhitespace)
                    val token = Regex("^(" + chinese + "(?:[.．]" + chinese + "){0,5})").find(rest)?.value
                    token == null || token.split(Regex("[.．]")).all(::validChineseNumber)
                }
            } -> (prefixes.firstOrNull { text.startsWith(it) } ?: prefix) + " # " + markers
            englishMatch != null -> {
                val number = englishMatch.groups["number"]?.value.orEmpty()
                val valid = number.toIntOrNull() != null ||
                    (number.contains('.') && number.split('.').all { it.toIntOrNull() != null }) ||
                    romanNumber.matches(number) || validEnglishNumber(number)
                if (!valid || sentenceLead.containsMatchIn(englishTail.matchEntire(text)?.groups?.get("tail")?.value
                        ?.trimStart(' ', ':', '：', '—', '-')
                        .orEmpty())) null
                else "Chapter / Part / Book"
            }
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

private fun isContentsTitle(text: String): Boolean =
    Regex(
        "(?i)^(?:table\\s+of\\s+contents|contents|toc|目\\s*次|目\\s*录|目\\s*錄|总\\s*目录|總\\s*目錄)(?:\\s*[:：—-]\\s*.+)?$"
    ).matches(text.trim('【', '】', '*', '—', ' '))

private fun isFrontMatterTitle(text: String): Boolean =
    Regex(
        "(?i)^(?:preface|foreword|introduction|prologue|epilogue|author'?s\\s+note|translator'?s\\s+note|序章|序言|序|前言|自序|作者序|译序|譯序|楔子|番外|后记|後記)(?:\\s*[:：—-]\\s*.+)?$"
    ).matches(text.trim('【', '】', '*', '—', ' '))

private val contentsEntry =
    Regex(
        "^(.{1,120}?)(?:\\.{2,}|…{2,}|\\s{2,})\\s*(?:[0-9]{1,4}|[ivxlcdm]{1,8})$",
        RegexOption.IGNORE_CASE,
    )

data class Heading(
    val line: Int,
    val title: String,
    val reason: String,
    val bodyEvidence: Boolean = false,
    val level: Int = 1,
)

private fun headingLevel(title: String): Int {
    val number =
        Regex("(?:^|\\s|第)([0-9]+(?:\\.[0-9]+)+|[零〇一二两兩三四五六七八九壹贰貳叁參肆伍陆陸柒捌玖拾佰仟百千万萬亿億]+(?:[.．][零〇一二两兩三四五六七八九壹贰貳叁參肆伍陆陸柒捌玖拾佰仟百千万萬亿億]+)+)")
            .find(Normalizer.normalize(title, Normalizer.Form.NFKC))
            ?.groupValues
            ?.getOrNull(1)
            ?: return 1
    return (number.count { it == '.' || it == '．' } + 1).coerceIn(1, 6)
}

private data class NavigationEntry(val resource: Int, val title: String, val level: Int)

private fun isChapterCandidate(heading: Heading): Boolean =
    heading.reason == "+" ||
        (heading.reason != "§" && heading.reason != "contents" &&
            (heading.reason.contains("#") || heading.reason.contains("Chapter") || heading.reason.contains("第")))

private fun normalizedHeadingTitle(heading: Heading): String =
    Normalizer.normalize(
            contentsEntry.matchEntire(heading.title.trim())?.groupValues?.get(1) ?: heading.title,
            Normalizer.Form.NFKC,
        )
        .lowercase()
        .replace(Regex("[\\s:：—-]+"), " ")
        .trim()

internal fun filterFrontMatter(headings: List<Heading>): List<Heading> {
    val chapterCandidates = headings.filter(::isChapterCandidate)
    val contentsHeading = headings.firstOrNull { it.reason == "contents" }?.line
    val candidatesAfterContents =
        chapterCandidates.filter { contentsHeading == null || it.line > contentsHeading }
    val possibleBodyChapters = candidatesAfterContents.filterNot {
        contentsEntry.matches(it.title.trim())
    }
    val duplicatedListings =
        possibleBodyChapters
            .groupBy(::normalizedHeadingTitle)
            .filterValues { it.size > 1 }
            .values
            .map { matches -> matches.lastOrNull { it.bodyEvidence } ?: matches.last() }
    val bodyStart =
        duplicatedListings.minOfOrNull { it.line }
            ?: possibleBodyChapters.firstOrNull { it.bodyEvidence }?.line
            ?: possibleBodyChapters.firstOrNull()?.line
    if (bodyStart == null) return headings

    val frontMatter = headings.filter { it.reason == "§" && it.line < bodyStart }
    val actualPrefaces =
        frontMatter
            .groupBy(::normalizedHeadingTitle)
            .values
            .map { matches -> matches.lastOrNull { it.bodyEvidence } ?: matches.last() }
            .mapTo(mutableSetOf()) { it.line }
    return headings.filter { heading ->
        when {
            heading.reason == "contents" -> heading.line < bodyStart
            heading.line < bodyStart &&
                contentsHeading != null &&
                heading.line >= contentsHeading &&
                chapterCandidates.any { it.line == heading.line } -> false
            heading.line >= bodyStart -> heading.reason != "§" && heading.reason != "contents"
            heading.reason == "§" -> heading.line in actualPrefaces
            else -> true
        }
    }
}

data class TextPart(val text: String, val line: Int, val first: Boolean, val newline: Boolean)

internal data class TextChunk(val start: Int, val text: String)

/** Keep stable EPUB anchors near saved offsets without splitting an extended character cluster. */
internal fun textChunks(text: String, maxUnits: Int = 256): Sequence<TextChunk> = sequence {
    if (text.isEmpty()) return@sequence
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    var start = boundaries.first()
    while (start < text.length) {
        val limit = minOf(text.length, start + maxUnits.coerceAtLeast(1))
        var end =
            if (limit == text.length) text.length
            else boundaries.preceding(limit + 1).takeIf { it > start } ?: boundaries.following(start)
        if (end <= start) end = boundaries.following(start)
        yield(TextChunk(start, text.substring(start, end)))
        start = end
    }
}

internal fun hasCurrentSegmentIndex(folder: File): Boolean {
    val index = File(folder, "segments.idx")
    val version =
        File(folder, "segments.version").takeIf { it.isFile }?.readText()?.trim()?.split(':')
    return version?.size == 2 && version[0] == "2" &&
        version[1].toLongOrNull() == index.length() && index.length() % 24L == 0L
}

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

    suspend fun headings(file: File, rule: HeadingRule): List<Heading> {
        val found =
            buildList {
                var previous: Heading? = null
                var activeIndex = -1
                var bodyEvidence = false
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
                            if (activeIndex >= 0)
                                this[activeIndex] = this[activeIndex].copy(bodyEvidence = bodyEvidence)
                            require(size < 50_000) { "50,000" }
                            previous = Heading(part.line, part.text, reason, level = headingLevel(part.text))
                            add(previous!!)
                            activeIndex = lastIndex
                            bodyEvidence = false
                        } else if (
                            activeIndex >= 0 &&
                                part.first &&
                                part.text.isNotBlank() &&
                                !contentsEntry.matches(part.text.trim()) &&
                                (part.text.length >= 24 || part.text.last() in "。！？.!?")
                        ) {
                            bodyEvidence = true
                        }
                    }
                }
                if (activeIndex >= 0)
                    this[activeIndex] = this[activeIndex].copy(bodyEvidence = bodyEvidence)
        }

        return filterFrontMatter(suggestUnlabelledContents(file, found))
    }

    private suspend fun suggestUnlabelledContents(
        file: File,
        headings: List<Heading>,
    ): List<Heading> {
        if (headings.any { it.reason == "contents" }) return headings
        val bodyStart =
            headings.filter(::isChapterCandidate).firstOrNull { it.bodyEvidence }?.line ?: return headings
        val earlyCandidates = headings.filter { isChapterCandidate(it) && it.line < bodyStart }
        val repeatedTitles =
            earlyCandidates
                .groupBy(::normalizedHeadingTitle)
                .mapNotNull { (title, listed) ->
                    val actual = headings.firstOrNull {
                        it.line >= bodyStart && isChapterCandidate(it) &&
                            it.bodyEvidence && normalizedHeadingTitle(it) == title
                    }
                    actual?.let { listed.first() }
                }
        if (repeatedTitles.size >= 3 &&
            repeatedTitles.last().line - repeatedTitles.first().line <= 100
        ) {
            return (headings + Heading(repeatedTitles.first().line, "Contents", "contents"))
                .sortedBy { it.line }
        }
        val lines = mutableListOf<Pair<Int, String>>()
        file.bufferedReader().use { input ->
            for (part in parts(input)) {
                currentCoroutineContext().ensureActive()
                if (!part.first || part.line >= bodyStart || part.line > 20_000) continue
                val text = part.text
                if (contentsEntry.matches(text.trim()) && text.length <= 160) {
                    lines += part.line to text.trim()
                } else if (lines.isNotEmpty() && part.line - lines.last().first > 3) {
                    if (lines.size >= 3) break
                    lines.clear()
                }
            }
        }
        if (lines.size < 3) return headings
        val synthetic = Heading(lines.first().first, "Contents", "contents")
        return (headings + synthetic).sortedBy { it.line }
    }

    private fun xml(s: String) =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "\uFFFD")

    suspend fun publish(folder: File, title: String, rule: HeadingRule) {
        val text = File(folder, "text.utf8")
        val map = headings(text, rule).associateBy { it.line }
        val nav = mutableListOf<NavigationEntry>()
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
                    DataOutputStream(File(folder, "segments.idx").outputStream().buffered()).use { segments ->
                    fun begin(label: String?, level: Int = 1) {
                        if (count > 0) {
                            write("</body></html>")
                            zip.closeEntry()
                        }
                        if (label != null) {
                            nav += NavigationEntry(count, label, level)
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
                                begin(
                                    heading?.title ?: if (count == 0) title else null,
                                    heading?.level ?: 1,
                                )
                            if (part.first) {
                                lines.writeLong(offset)
                                lines.writeLong(bytes)
                            }
                            var chunkBytes = 0
                            for (chunk in textChunks(part.text)) {
                                val encoded = chunk.text.toByteArray(Charsets.UTF_8)
                                segments.writeLong(offset + chunk.start)
                                segments.writeLong(bytes + chunkBytes)
                                segments.writeInt(count - 1)
                                segments.writeInt(encoded.size)
                                if (heading != null && chunk.start == 0)
                                    write(
                                        "<span style='display:block;font-size:1.35em;font-weight:600;margin-bottom:0.5em'>"
                                    )
                                write("<span id='s" + (offset + chunk.start) + "'>" + xml(chunk.text) + "</span>")
                                chunkBytes += encoded.size
                            }
                            if (heading != null) {
                                write("</span><br/>")
                            }
                            if (part.newline) write("<br/>")
                            length += part.text.length
                            bytes += chunkBytes + (if (part.newline) 1 else 0)
                            offset += part.text.length + (if (part.newline) 1 else 0)
                        }
                    }
                    if (count == 0) begin(title)
                    write("</body></html>")
                    zip.closeEntry()
                    }
                }
            }
            entry(
                "nav.xhtml",
                    "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Contents</title></head><body><nav epub:type='toc'><ol>" +
                    navigationMarkup(nav) +
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
        val segmentIndex = File(folder, "segments.idx")
        File(folder, "segments.version").writeText("2:${segmentIndex.length()}")
        File(folder, "edition.version").writeText("2")
    }

    private fun navigationMarkup(entries: List<NavigationEntry>): String {
        data class Node(val entry: NavigationEntry, val children: MutableList<Node> = mutableListOf())
        val roots = mutableListOf<Node>()
        val stack = mutableListOf<Node>()
        for (entry in entries) {
            while (stack.isNotEmpty() && stack.last().entry.level >= entry.level) stack.removeAt(stack.lastIndex)
            val node = Node(entry)
            if (stack.isEmpty()) roots += node else stack.last().children += node
            stack += node
        }
        fun render(nodes: List<Node>): String = nodes.joinToString("") { node ->
            "<li><a href='r${node.entry.resource}.xhtml'>${xml(node.entry.title)}</a>" +
                if (node.children.isEmpty()) "</li>"
                else "<ol>${render(node.children)}</ol></li>"
        }
        return render(roots)
    }

    suspend fun ensureSegmentIndex(folder: File): Boolean = withContext(Dispatchers.IO) {
        val destination = File(folder, "segments.idx")
        val version = File(folder, "segments.version")
        if (hasCurrentSegmentIndex(folder)) return@withContext false
        // Older EPUBs only contain line-start anchors. Keep their fallback locators until a
        // complete edition rebuild can publish matching anchors and indexes together.
        if (File(folder, "edition.version").takeIf { it.isFile }?.readText()?.trim() != "2")
            return@withContext false
        val text = File(folder, "text.utf8")
        val resources = File(folder, "resources.idx")
        check(text.isFile && resources.isFile && resources.length() >= 20L)
        val staged = File(folder, "segments.idx.rebuild")
        try {
            RandomAccessFile(resources, "r").use { resourceIndex ->
                DataOutputStream(staged.outputStream().buffered()).use { index ->
                    text.bufferedReader().use { input ->
                        var offset = 0L
                        var bytes = 0L
                        for (part in parts(input)) {
                            currentCoroutineContext().ensureActive()
                            var chunkBytes = 0
                            for (chunk in textChunks(part.text)) {
                                val encoded = chunk.text.toByteArray(Charsets.UTF_8)
                                val at = offset + chunk.start
                                index.writeLong(at)
                                index.writeLong(bytes + chunkBytes)
                                index.writeInt(floorIndex(resourceIndex, 20, at).toInt())
                                index.writeInt(encoded.size)
                                chunkBytes += encoded.size
                            }
                            offset += part.text.length + if (part.newline) 1 else 0
                            bytes += chunkBytes + if (part.newline) 1 else 0
                        }
                    }
                }
            }
            try {
                Files.move(
                    staged.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            version.writeText("2:${destination.length()}")
            true
        } catch (failure: Throwable) {
            staged.delete()
            throw failure
        }
    }
}
