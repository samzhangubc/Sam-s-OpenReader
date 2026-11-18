package org.sam.openreader

import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun sha(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { i ->
        val buf = ByteArray(65536)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

suspend fun Library.backup(
    uri: Uri,
    books: List<Book>,
    global: String,
    names: String,
    wholeShelf: Boolean,
) =
    withContext(Dispatchers.IO) {
        val root =
            JSONObject()
                .put("schema", 1)
                .put("library", wholeShelf)
                .put("settings", global)
                .put("styles", JSONObject(names.ifBlank { "{}" }))
        val records = JSONArray()
        root.put("books", records)
        ZipOutputStream(context.contentResolver.openOutputStream(uri, "wt")!!.buffered()).use { zip
            ->
            books.forEachIndexed { i, b ->
                currentCoroutineContext().ensureActive()
                val record =
                    JSONObject()
                        .put("title", b.title)
                        .put("author", b.author)
                        .put("format", b.format)
                        .put("visual", b.visual)
                        .put("spoken", b.spoken)
                        .put("progress", b.progress)
                        .put("style", b.style)
                val marks = JSONArray()
                shelf.allMarks(b.id).forEach {
                    marks.put(JSONObject().put("label", it.label).put("location", it.location))
                }
                val files = JSONObject()
                record.put("marks", marks).put("files", files)
                fun add(name: String, file: File) {
                    zip.putNextEntry(ZipEntry("books/" + i + "/" + name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    files.put(name, sha(file))
                }
                add("original", original(b))
                if (b.format == "txt") {
                    add("text.utf8", File(revision(b), "text.utf8"))
                    add("profile", File(revision(b), "profile"))
                    val enc = File(revision(b), "encoding")
                    if (enc.exists()) add("encoding", enc)
                }
                records.put(record)
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(root.toString().toByteArray())
            zip.closeEntry()
        }
    }

data class BackupPreview(val file: File, val manifest: JSONObject)

suspend fun Library.previewBackup(uri: Uri): BackupPreview =
    withContext(Dispatchers.IO) {
        val file = File.createTempFile("restore-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    var sum = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        sum += n
                        require(sum <= 2L * 1024 * 1024 * 1024)
                        out.write(buf, 0, n)
                    }
                }
            }
            ZipFile(file).use { z ->
                require(z.size() in 1..10001)
                val meta = requireNotNull(z.getEntry("manifest.json"))
                require(meta.size in 1..4_194_304)
                val bytes = z.getInputStream(meta).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 4_194_304)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                require(json.getInt("schema") == 1 && json.getJSONArray("books").length() <= 2000)
                BackupPreview(file, json)
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

fun mergeStyles(incoming: JSONObject, local: JSONObject): Map<String, String> {
    val map = mutableMapOf<String, String>()
    incoming.keys().forEach { name ->
        var dest = name
        var suffix = 2
        while (
            local.has(dest) &&
                Look.read(local.getString(dest)).copy(styleId = "") !=
                    Look.read(incoming.getString(name)).copy(styleId = "")
        ) dest = name + " (" + suffix++ + ")"
        map[name] = dest
        local.put(dest, Look.read(incoming.getString(name)).copy(styleId = dest).json())
    }
    return map
}

suspend fun Library.restoreBackup(
    preview: BackupPreview,
    skip: Boolean,
    renamed: Map<String, String>,
    global: String,
): List<Book> =
    withContext(Dispatchers.IO) {
        val staged = mutableListOf<Book>()
        val marks = mutableListOf<Bookmark>()
        try {
            ZipFile(preview.file).use { z ->
                val entries = preview.manifest.getJSONArray("books")
                var expanded = 0L
                for (i in 0 until entries.length()) {
                    currentCoroutineContext().ensureActive()
                    val j = entries.getJSONObject(i)
                    val files = j.getJSONObject("files")
                    val fmt = j.getString("format")
                    require(fmt in listOf("txt", "pdf", "epub"))
                    val hash = files.getString("original")
                    if (skip && (shelf.duplicate(hash) != null || staged.any { it.hash == hash }))
                        continue
                    val saved =
                        Look.read(
                            j.optString("style").ifBlank { preview.manifest.optString("settings") }
                        )
                    val effective = saved.copy(styleId = renamed[saved.styleId] ?: saved.styleId)
                    val override =
                        if (j.optString("style").isBlank() && Look.read(global) == effective) ""
                        else effective.json()
                    val b =
                        Book(
                            uid(),
                            j.getString("title"),
                            fmt,
                            hash,
                            true,
                            if (fmt == "txt") uid() else "",
                            j.optString("visual"),
                            j.optString("spoken"),
                            style = override,
                            author = j.optString("author"),
                            progress = j.optDouble("progress", -1.0),
                        )
                    staged += b
                    check(dir(b.id).mkdir())
                    fun get(key: String, to: File) {
                        val e = requireNotNull(z.getEntry("books/" + i + "/" + key))
                        require(e.size in 0..1_073_741_824)
                        expanded += e.size
                        require(expanded <= 2_147_483_648)
                        z.getInputStream(e).use { input ->
                            to.outputStream().use { out ->
                                val buf = ByteArray(32768)
                                var sum = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    sum += n
                                    require(sum <= e.size)
                                    out.write(buf, 0, n)
                                }
                                require(sum == e.size)
                            }
                        }
                        require(sha(to) == files.getString(key))
                    }
                    get("original", original(b))
                    if (fmt == "txt") {
                        val folder = revision(b).apply { mkdirs() }
                        get("text.utf8", File(folder, "text.utf8"))
                        get("profile", File(folder, "profile"))
                        if (files.has("encoding")) get("encoding", File(folder, "encoding"))
                        TextFiles.publish(
                            folder,
                            b.title,
                            HeadingRule.load(File(folder, "profile")),
                        )
                    } else if (fmt == "epub") publication(original(b)).close()
                    else PdfBook(original(b)).close()
                    val bm = j.getJSONArray("marks")
                    for (n in 0 until bm.length()) {
                        val m = bm.getJSONObject(n)
                        marks += Bookmark(b.id, m.getString("location"), m.getString("label"))
                    }
                }
            }
            db.withTransaction {
                staged.forEach { shelf.add(it) }
                marks.forEach { shelf.mark(it) }
            }
            staged.toList()
        } catch (e: Throwable) {
            staged.forEach { dir(it.id).deleteRecursively() }
            throw e
        }
    }
