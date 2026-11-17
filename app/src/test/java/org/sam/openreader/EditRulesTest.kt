package org.sam.openreader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditRulesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun literalCrossesBufferAndDoesNotReplaceRecursively() = runBlocking<Unit> {
        val source = temp.newFile().apply { writeText("x".repeat(32766) + "rain\nrain 🌊") }
        val target = temp.newFile()
        val count = rewrite(source, target, temp.newFile(), EditRule("rain", "rainbow"), mutableListOf())
        assertEquals(2L, count)
        assertEquals("x".repeat(32766) + "rainbow\nrainbow 🌊", target.readText())
    }

    @Test fun captureAndWordModesHaveDeclaredBoundaries() = runBlocking<Unit> {
        val source = temp.newFile().apply { writeText("Chapter 12\nsea seaward SEA\n海") }
        val target = temp.newFile()
        val capture = EditRule("Chapter ([0-9]+)", "第\$1章", regex = true)
        assertEquals(1L, rewrite(source, target, temp.newFile(), capture, mutableListOf()))
        assertTrue(target.readText().startsWith("第12章\n"))
        val output = temp.newFile()
        assertEquals(2L, rewrite(target, output, temp.newFile(), EditRule("sea", "", sensitive = false, word = true), mutableListOf()))
        assertEquals("第12章\n seaward \n海", output.readText())
    }

    @Test fun blocksRequireNumberMarkerAndSeparatedTitle() {
        val rule = HeadingRule(blocks = strictBlocks)
        assertNotNull(rule.reason("第十二章 山雨", 1))
        assertNotNull(rule.reason("第１２節：歸途", 2))
        assertNull(rule.reason("第三天他读完了这一章", 3))
        assertNull(rule.reason("第十二章讲的是回家", 4))
        assertNull(rule.reason("第十百章", 5))
        assertNull(rule.reason("第十二章 山雨。", 6))
    }
}
