package org.sam.openreader

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun viewAndShareOpenPrivateSnapshots() {
        // Deliver the public Activity intents using a granted content URI from a document provider.
        for (name in listOf("notes.txt", "fixture.pdf")) {
            val uri = Uri.parse("content://org.sam.openreader.test.fixtures/" + name)
            val type = if (name.endsWith("txt")) "text/plain" else "application/pdf"
            val intent =
                if (type == "text/plain") Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                else Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            ui.runOnUiThread {
                ui.activity.startActivity(
                    intent
                        .setClass(ui.activity, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                )
            }
            ui.waitUntil(15000) {
                ui.onAllNodes(hasText("Open once") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            ui.onNodeWithText("Open once").performClick()
            val label = if (name.endsWith("txt")) "Section 1" else "Page 1 / 3"
            ui.waitUntil(30000) {
                ui.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(label, substring = true).performClick()
            ui.onNodeWithText("Back").performClick()
            ui.onNodeWithText("Import book").assertExists()
        }
    }

    @Test
    fun localLibraryToRenderedBook() {
        val lib = (ui.activity.application as ReaderApp).library
        val id = uid()
        val staged = Book(id, "Smoke-" + id, "txt", "test-" + id, false)
        lib.dir(id).mkdirs()
        val body = "Chapter 1\nA quiet harbour. 海風 🌊\n\n第十二章 山雨\nAway."
        lib.original(staged).writeText(body)
        val book = runBlocking { lib.commit(staged, true, Charsets.UTF_8, HeadingRule()) }
        try {
            ui.waitUntil(15000) {
                ui.onAllNodesWithText(book.title).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText(book.title).performClick()
            ui.waitUntil(30000) {
                ui.onAllNodesWithText("Section 1", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            ui.onNodeWithText("Section 1", substring = true).performClick()
            ui.onNodeWithText("Contents").performClick()
            ui.waitUntil(20000) {
                ui.onAllNodesWithText("第十二章 山雨").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithText("第十二章 山雨").performClick()
            ui.waitUntil(20000) {
                ui.onAllNodesWithText("Section 2", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } finally {
            runBlocking { lib.remove(book) }
        }
    }

    @Test
    fun revisionOriginalAndVoiceTransport() =
        runBlocking<Unit> {
            val lib = (ui.activity.application as ReaderApp).library
            val b = Book(uid(), "Test", "txt", uid(), false)
            lib.dir(b.id).mkdirs()
            val original = "Chapter 1\nSea.\n\nCloud.\n\nRain."
            lib.original(b).writeText(original)
            val book = lib.commit(b, true, Charsets.UTF_8, HeadingRule())
            try {
                val d = lib.draft(book, HeadingRule(), "Cloud", "云")
                assertEquals(1L, d.count)
                val changed = lib.applyDraft(d)
                assertEquals(original, lib.original(changed).readText())
                assertEquals(
                    original.replace("Cloud", "云"),
                    File(lib.revision(changed), "text.utf8").readText(),
                )
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { lib.applyDraft(d) }
                }
                lib.speechSource(changed, "").use { speech ->
                    assertTrue(speech.step(0)!!.text.contains("Chapter"))
                    assertEquals("云.", speech.step(1, true)!!.text.trim())
                    assertTrue(speech.step(-1, true)!!.text.contains("Chapter"))
                }
                lib.publication(lib.renderFile(changed)).also {
                    assertEquals(1, it.tableOfContents.size)
                    it.close()
                }
            } finally {
                lib.remove(book)
            }
        }
}
