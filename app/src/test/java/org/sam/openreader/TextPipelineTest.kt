package org.sam.openreader

import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextPipelineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun newLibraryBooksStartAtZeroProgress() {
        assertEquals(0.0, Book(uid(), "New", "txt", "hash", true).progress, 0.0)
    }

    @Test
    fun fullLineRulesRejectBodySentences() {
        val rule = HeadingRule()
        for (s in
            listOf(
                "第１２節：歸途",
                "第十二章 山雨",
                "【第壹拾貳回】",
                "Chapter XII — The harbour",
                "Part Three",
                "楔子",
            )) assertNotNull(s, rule.reason(s, 4))
        for (s in
            listOf(
                "我在第十二章读到这句话。",
                "第三天他读完了这一章",
                "第十二章讲的是回家",
                "Chapter 2 was difficult.",
                "Chapter 2 was difficult",
                "Chapter 2 describes a storm",
                "Chapter IC",
                "***",
                "12",
            )) assertNull(s, rule.reason(s, 4))
        assertNotNull(rule.copy(loose = true).reason("第十二章讲的是回家", 4))
        assertNull(rule.copy(excluded = setOf(4)).reason("第十二章", 4))
        assertNotNull(rule.copy(bare = true).reason("（三）", 4))
        assertNotNull(rule.reason("第2.3節 分類", 5))
        assertNotNull(HeadingRule(prefix = "Chapter|Scene", markers = ":|—").reason("Scene 21: Sea", 6))
        assertNotNull(rule.reason("Chapter Twenty-one — Sea", 7))
        assertNotNull(rule.reason("Chapter One Hundred and Five", 8))
        assertTrue(validEnglishNumber("twenty-one"))
        assertTrue(validEnglishNumber("one hundred and five"))
        assertFalse(validEnglishNumber("one one"))
        assertNull(rule.reason("Chapter IC", 8))
    }

    @Test
    fun contentsEntriesDoNotBecomeFrontMatterOrChapterBoundaries() {
        val candidates =
            listOf(
                Heading(1, "目錄", "contents"),
                Heading(2, "第一章 山雨", "第 # 章|回|節|节|卷|部"),
                Heading(3, "前言", "§"),
                Heading(4, "前言", "§"),
                Heading(6, "第一章 山雨", "第 # 章|回|節|节|卷|部"),
                Heading(8, "前言", "§"),
            )

        assertEquals(
            listOf(1, 4, 6),
            filterFrontMatter(candidates).map { it.line },
        )
    }

    @Test
    fun prefaceAliasesAllowSubtitlesAndContentsDetectDecoratedTitles() {
        val rule = HeadingRule(blocks = strictBlocks)
        assertEquals("§", rule.reason("前言：作者的话", 1))
        assertEquals("§", rule.reason("【Preface — A note】", 2))
        assertEquals("contents", rule.reason("Table of Contents", 3))
        assertEquals("contents", rule.reason("【目 錄】", 4))
        assertNull(rule.reason("The preface explains the setting.", 5))
    }

    @Test
    fun unlabelledDotLeaderListGetsOneContentsBoundary() = runBlocking {
        val file = temp.newFile().apply {
            writeText(
                "Chapter 1 …… 1\nChapter 2 …… 4\nChapter 3 …… 9\n" +
                    "Chapter 1\nRain falls.\nChapter 2\nWind rises."
            )
        }

        val headings = TextFiles.headings(file, HeadingRule())

        assertEquals(listOf(1, 4, 6), headings.map { it.line })
        assertEquals("contents", headings.first().reason)
    }

    @Test
    fun strictDecodeNormalizesOnlyLineEndings() =
        runBlocking<Unit> {
            for (cs in TextFiles.encodings) {
                val charset = charset(cs)
                val input = if (cs == "windows-1252") "café\r\na\rb" else "書\r\na\rb"
                val f = temp.newFile()
                f.writeBytes(input.toByteArray(charset))
                val out = temp.newFile()
                TextFiles.decode(f, out, charset)
                assertEquals(input.replace("\r\n", "\n").replace('\r', '\n'), out.readText())
            }
            val invalid = temp.newFile().apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
            assertThrows(java.nio.charset.CharacterCodingException::class.java) {
                runBlocking { TextFiles.decode(invalid, temp.newFile(), Charsets.UTF_8) }
            }
        }

    @Test
    fun generatedEpubIsIndexedAndEscaped() = runBlocking {
        val folder = temp.newFolder()
        val original =
            "Opening 🌊\n第十二章 山雨\n<hello> & 🙂\n" +
                "海".repeat(100000) + "UniquePositionMarker\nChapter 2\nEnd"
        File(folder, "text.utf8").writeText(original)
        TextFiles.publish(folder, "A & B", HeadingRule())
        val result = File(folder, "reader.epub")
        ZipFile(result).use { z ->
            val first = z.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(0, first.method)
            fun text(name: String) =
                z.getInputStream(z.getEntry(name)).bufferedReader().use { it.readText() }
            val nav = text("nav.xhtml")
            assertTrue(nav.contains("第十二章 山雨"))
            assertTrue(nav.contains("Chapter 2"))
            assertTrue(text("r1.xhtml").contains("&lt;hello&gt; &amp; 🙂"))
            assertTrue(z.size() > 7)
        }
        assertEquals(original, File(folder, "text.utf8").readText())
        assertEquals(original.length.toString(), File(folder, "text.length").readText())
        assertEquals(6L * 16, File(folder, "lines.idx").length())
        assertTrue(File(folder, "segments.idx").length() > 0)
        assertEquals(0L, File(folder, "segments.idx").length() % 24)

        val target = original.indexOf("UniquePositionMarker") + 4
        val resume = indexedTextLocation(folder, target.toLong())
        assertEquals(target.toLong(), resume.canonicalOffset)
        assertTrue(resume.highlight.orEmpty().startsWith("uePosition"))
        assertTrue(resume.resource >= 0)
        assertTrue(target.toLong() - resume.anchor in 0L..256L)
        ZipFile(result).use { zip ->
            val resource = zip.getInputStream(zip.getEntry("r${resume.resource}.xhtml"))
                .bufferedReader().use { it.readText() }
            assertTrue(resource.contains("id='s${resume.anchor}'"))
        }

        File(folder, "segments.idx").delete()
        File(folder, "segments.version").delete()
        assertTrue(TextFiles.ensureSegmentIndex(folder))
        assertTrue(indexedTextLocation(folder, target.toLong()).highlight.orEmpty().startsWith("uePosition"))
    }

    @Test
    fun dottedNumberHeadingsCreateNestedContentsNavigation() = runBlocking {
        val folder = temp.newFolder()
        File(folder, "text.utf8").writeText(
            "1 Parent\n1.1 First child\nSome content.\n1.2 Second child\nMore content.\n2 Parent\n2.1 Next child\nEnd."
        )
        TextFiles.publish(folder, "Nested", HeadingRule(advanced = "[0-9]+(?:\\.[0-9]+)? .{1,40}"))
        val nav = ZipFile(File(folder, "reader.epub")).use { zip ->
            zip.getInputStream(zip.getEntry("nav.xhtml")).bufferedReader().use { it.readText() }
        }
        assertTrue(nav.contains("<a href='r0.xhtml'>1 Parent</a><ol><li><a href='r1.xhtml'>1.1 First child</a></li>"))
        assertTrue(nav.contains("<li><a href='r2.xhtml'>1.2 Second child</a></li></ol></li>"))
        assertTrue(nav.contains("<a href='r3.xhtml'>2 Parent</a><ol><li><a href='r4.xhtml'>2.1 Next child</a></li></ol>"))
    }

    @Test
    fun manualChapterCorrectionsFollowBulkInsertions() = runBlocking {
        val source = temp.newFolder()
        File(source, "text.utf8").writeText("Preface\nChapter 1\nChapter 2\n")
        TextFiles.publish(source, "Book", HeadingRule())
        val result = temp.newFolder()
        val target = File(result, "text.utf8").apply {
            writeText("Extra\nPreface\nChapter 1\nChapter 2\n")
        }
        DataOutputStream(File(result, "edits.000").outputStream()).use {
            it.writeLong(0)
            it.writeInt(0)
            it.writeInt(6)
        }

        val mapped =
            remapManualDecisions(
                source,
                target,
                result,
                HeadingRule(excluded = setOf(2), included = setOf(3)),
            )

        assertEquals(setOf(3), mapped.excluded)
        assertEquals(setOf(4), mapped.included)
    }

    @Test
    fun externalProfileRoundTripsAndMatchesFullLine() {
        val rule =
            HeadingRule(
                prefix = "Scene ",
                markers = ":",
                contains = "海",
                advanced = "Scene [0-9]+",
                excluded = setOf(31),
                included = setOf(32),
            )
        val file = temp.newFile()
        rule.save(file)
        assertEquals(rule, HeadingRule.load(file))
    }

    @Test
    fun catalogHas36OfflineCombinations() {
        assertEquals(9, families.size)
        assertEquals(8, englishFonts.size)
        assertEquals(8, chineseFonts.size)
        assertEquals("CMUSerif.ttf", englishFonts["CMU Serif"])
        for (f in families.keys) for (v in 0..3) {
            val c = preset(f, v)
            assertEquals(14, c.size)
            fun luminance(hex: String): Double {
                val linear =
                    hex.drop(1).chunked(2).map {
                        val x = it.toInt(16) / 255.0
                        if (x <= .04045) x / 12.92 else Math.pow((x + .055) / 1.055, 2.4)
                    }
                return .2126 * linear[0] + .7152 * linear[1] + .0722 * linear[2]
            }
            for ((a, b) in listOf(0 to 1, 0 to 2, 0 to 3, 4 to 5, 6 to 7, 10 to 11, 12 to 13)) {
                val one = luminance(c[a])
                val two = luminance(c[b])
                assertTrue(
                    f + " " + v,
                    (maxOf(one, two) + .05) / (minOf(one, two) + .05) >= if (v == 0) 7.0 else 4.5,
                )
            }
        }
    }
}
