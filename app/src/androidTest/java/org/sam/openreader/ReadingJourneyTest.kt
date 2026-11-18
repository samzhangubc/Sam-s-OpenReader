package org.sam.openreader

import android.net.Uri
import android.content.Intent
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun rapidTurnAfterSwipePreservesBothPageRequests() {
        ui.runOnUiThread {
            var page = 5
            var accepted = 0
            val turn = PageTurn(ui.activity).apply {
                surface.isClickable = true
                onTurnAccepted = { accepted++ }
                advance = { next ->
                    page += if (next) 1 else -1
                    true
                }
                measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 400, 800)
            }
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float, after: Long) {
                val event =
                    MotionEvent.obtain(down, down + after, action, x, 200f, 0)
                try {
                    turn.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN, 320f, 0)
            send(MotionEvent.ACTION_MOVE, 120f, 40)
            send(MotionEvent.ACTION_UP, 120f, 60)
            assertEquals("first swipe reaches page 6", 6, page)
            accepted = 0
            turn.turn(true)
            assertEquals("fresh command reaches page 7", 7, page)
            assertEquals("first immediate turn notifies playback", 1, accepted)
            turn.turn(false)
            assertEquals("second immediate turn reaches page 6", 6, page)
            assertEquals("each immediate turn notifies playback", 2, accepted)
            assertFalse(turn.pending)
        }
    }

    @Test
    fun samsungOfflineVoiceStartsPausesAndStops() {
        assertTrue("Samsung TTS must be the device default", samsungDefault(ui.activity))
        val initialized = CountDownLatch(1)
        var probe: TextToSpeech? = null
        ui.runOnUiThread {
            probe = TextToSpeech(ui.activity, { initialized.countDown() }, SAMSUNG)
        }
        assertTrue("Samsung TTS initialization timed out", initialized.await(20, TimeUnit.SECONDS))
        val voice =
            probe?.voices.orEmpty().firstOrNull {
                !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
        assertNotNull("An installed Samsung offline voice is required", voice)

        val lib = (ui.activity.application as ReaderApp).library
        val book = Book(uid(), "TTS smoke", "txt", uid(), true)
        lib.dir(book.id).mkdirs()
        lib.original(book).writeText(
            "Chapter 1\n" + "A quiet harbour at the edge of the sea. 海風拂過書頁。 ".repeat(30)
        )
        val committed = runBlocking { lib.commit(book, true, Charsets.UTF_8, HeadingRule()) }
        try {
            val intent =
                Intent(ui.activity, SpeechService::class.java)
                    .setAction("open")
                    .putExtra("id", committed.id)
                    .putExtra("location", "")
                    .putExtra("rate", 1f)
                    .putExtra("voice", requireNotNull(voice).name)
                    .putExtra("minutes", 0)
                    .putExtra("fromHere", true)
            ContextCompat.startForegroundService(ui.activity, intent)
            ui.waitUntil(30000) {
                SpeechService.status.value.let {
                    it.book == committed.id && it.voiceReady && it.utteranceStarted
                }
            }
            assertTrue("Samsung voice should begin speaking", SpeechService.status.value.playing)
            ui.activity.startService(Intent(ui.activity, SpeechService::class.java).setAction("pause"))
            ui.waitUntil(10000) {
                SpeechService.status.value.book == committed.id && !SpeechService.status.value.playing
            }
            ui.activity.startService(Intent(ui.activity, SpeechService::class.java).setAction("quit"))
            ui.waitUntil(10000) { SpeechService.status.value.book.isEmpty() }
        } finally {
            probe?.shutdown()
            SpeechService.quit(ui.activity, committed.id)
            runBlocking { lib.remove(committed) }
        }
    }

    @Test
    fun externalTxtImportKeepsPrivateSnapshot() = importExternalOnce("notes.txt")

    @Test
    fun externalPdfImportKeepsPrivateSnapshot() = importExternalOnce("fixture.pdf")

    private fun importExternalOnce(name: String) {
        val lib = (ui.activity.application as ReaderApp).library
        val uri = Uri.parse("content://org.sam.openreader.test.fixtures/" + name)
        val staged = runBlocking { lib.stage(uri) }
        var imported: Book? = null
        try {
            assertEquals("original." + staged.format, lib.original(staged).name)
            if (staged.format == "txt") {
                assertEquals(
                    "Chapter 1\nThe first harbour.\n\n第十二章 山雨\n第二段。🌊",
                    lib.original(staged).readText(),
                )
            } else {
                assertEquals("pdf", staged.format)
            }
            val committed = runBlocking { lib.commit(staged, true, Charsets.UTF_8, HeadingRule()) }
            imported = committed
            assertTrue(lib.original(committed).isFile)
            if (committed.format == "txt")
                assertTrue(File(lib.revision(committed), "text.utf8").isFile)
        } finally {
            if (imported != null) runBlocking { lib.remove(imported!!) }
            else runBlocking { lib.dir(staged.id).deleteRecursively() }
        }
    }

    @Test
    fun localLibraryToRenderedBook() {
        val lib = (ui.activity.application as ReaderApp).library
        val id = uid()
        val staged = Book(id, "Smoke-" + id, "txt", "test-" + id, false)
        lib.dir(id).mkdirs()
        val paragraph = "A quiet harbour. 海風 🌊 ".repeat(700)
        val body = "Chapter 1\n$paragraph\n\n第十二章 山雨\nAway."
        lib.original(staged).writeText(body)
        val book = runBlocking { lib.commit(staged, true, Charsets.UTF_8, HeadingRule()) }
        try {
            val target = body.indexOf("harbour") + paragraph.length / 2
            val saved = textLocation(lib.revision(book), target.toLong())
            runBlocking {
                lib.shelf.position(
                    book.id,
                    book.revision,
                    saved,
                    target.toDouble() / body.length,
                )
            }
            ui.waitUntil(60000) {
                ui.onAllNodesWithText(book.title).fetchSemanticsNodes().isNotEmpty()
            }
            ui.onAllNodesWithText(book.title).onLast().performClick()
            val sectionLabel = ui.activity.getString(R.string.section) + " 1"
            ui.waitUntil(30000) {
                ui.onAllNodesWithText(sectionLabel, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            ui.waitUntil(30000) {
                ui.activity.supportFragmentManager.fragments.any {
                    it is org.readium.r2.navigator.epub.EpubNavigatorFragment
                }
            }
            var restoredOffset = -1L
            ui.runOnUiThread {
                val locator =
                    ui.activity.supportFragmentManager.fragments
                        .filterIsInstance<org.readium.r2.navigator.epub.EpubNavigatorFragment>()
                        .first()
                        .currentLocator
                        .value
                restoredOffset = textOffset(lib.revision(book), locator.toJSON().toString())
            }
            assertTrue(
                "reader must reopen at the saved mid-paragraph passage",
                kotlin.math.abs(restoredOffset - target) <= 512,
            )
            ui.onNodeWithText(sectionLabel, substring = true).performClick()
            ui.onNodeWithText(ui.activity.getString(R.string.contents)).performClick()
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
