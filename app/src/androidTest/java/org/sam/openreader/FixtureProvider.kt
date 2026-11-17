package org.sam.openreader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Installed only in the instrumentation APK. Fixtures are addressed by fixed names. */
class FixtureProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) =
        if (uri.lastPathSegment == "notes.txt") "text/plain" else "application/pdf"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?,
    ) =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf(uri.lastPathSegment))
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val name = uri.lastPathSegment
        require(name in listOf("notes.txt", "fixture.pdf"))
        val file = File(requireNotNull(context).cacheDir, name!!)
        if (name.endsWith(".pdf")) {
            val doc = PdfDocument()
            try {
                for (i in 1..3) {
                    val p = doc.startPage(PdfDocument.PageInfo.Builder(300, 400, i).create())
                    p.canvas.drawText("Page " + i, 20f, 40f, android.graphics.Paint())
                    doc.finishPage(p)
                }
                file.outputStream().use { doc.writeTo(it) }
            } finally {
                doc.close()
            }
        } else file.writeText("Chapter 1\nThe first harbour.\n\n第十二章 山雨\n第二段。🌊")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0

    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
