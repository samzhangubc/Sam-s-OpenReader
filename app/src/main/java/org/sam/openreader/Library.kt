package org.sam.openreader

import android.app.Application
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

val Context.settings by preferencesDataStore("preferences")

fun uid() = UUID.randomUUID().toString()

@Entity
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val format: String,
    val hash: String,
    val permanent: Boolean,
    val revision: String = "",
    val visual: String = "",
    val spoken: String = "",
    val opened: Long = System.currentTimeMillis(),
    val style: String = "",
    val author: String = "",
    val imported: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val removed: Long = 0,
    @ColumnInfo(defaultValue = "0") val progress: Double = 0.0,
)

@Entity(primaryKeys = ["bookId", "location"])
data class Bookmark(val bookId: String, val location: String, val label: String)

@Dao
interface Shelf {
    @Query("SELECT * FROM Book WHERE permanent=1 AND removed=0 ORDER BY opened DESC")
    fun books(): Flow<List<Book>>

    @Query("SELECT * FROM Book WHERE id=:id") suspend fun book(id: String): Book?

    @Query("SELECT * FROM Book WHERE permanent=1 AND removed=0 AND hash=:hash LIMIT 1")
    suspend fun duplicate(hash: String): Book?

    @Query("UPDATE Book SET removed=:at WHERE id=:id") suspend fun trash(id: String, at: Long)

    @Query("SELECT * FROM Book WHERE (permanent=0 OR removed>0) AND imported<:before")
    suspend fun abandoned(before: Long): List<Book>

    @Insert suspend fun add(book: Book)

    @Query("UPDATE Book SET permanent=1 WHERE id=:id") suspend fun keep(id: String)

    @Query("UPDATE Book SET visual=:locator, progress=:progress, opened=:time WHERE id=:id AND revision=:revision")
    suspend fun position(
        id: String,
        revision: String,
        locator: String,
        progress: Double,
        time: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE Book SET spoken=:locator WHERE id=:id AND revision=:revision")
    suspend fun speech(id: String, revision: String, locator: String)

    @Query("UPDATE Book SET title=:title WHERE id=:id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE Book SET style=:style WHERE id=:id") suspend fun style(id: String, style: String)

    @Query(
        "UPDATE Book SET revision=:next, visual=:location, spoken='' WHERE id=:id AND revision=:base"
    )
    suspend fun revise(id: String, base: String, next: String, location: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun mark(mark: Bookmark)

    @Delete suspend fun unmark(mark: Bookmark)

    @Query("SELECT * FROM Bookmark WHERE bookId=:id") fun marks(id: String): Flow<List<Bookmark>>

    @Query("SELECT * FROM Bookmark WHERE bookId=:id")
    suspend fun allMarks(id: String): List<Bookmark>

    @Query("DELETE FROM Bookmark WHERE bookId=:id") suspend fun clearMarks(id: String)

    @Query("DELETE FROM Book WHERE id=:id") suspend fun remove(id: String)

    @Query("SELECT id FROM Book WHERE permanent=0") suspend fun temporary(): List<String>
}

@Database(entities = [Book::class, Bookmark::class], version = 3, exportSchema = false)
abstract class LibraryDb : RoomDatabase() {
    abstract fun shelf(): Shelf
}

class ReaderApp : Application() {
    val scope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    val library by lazy { Library(this) }

    override fun onCreate() {
        super.onCreate()
        val started = System.currentTimeMillis()
        scope.launch { library.recover(started) }
    }
}

class Library(val context: Context) {
    val db =
        Room.databaseBuilder(context, LibraryDb::class.java, "library.db")
            .addMigrations(
                object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE Book ADD COLUMN removed INTEGER NOT NULL DEFAULT 0")
                    }
                },
                object : androidx.room.migration.Migration(2, 3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE Book ADD COLUMN progress REAL NOT NULL DEFAULT 0")
                    }
                }
            )
            .build()
    val shelf = db.shelf()
    private val http = DefaultHttpClient()
    private val assets = AssetRetriever(context.contentResolver, http)
    private val opener =
        PublicationOpener(DefaultPublicationParser(context, http, assets, pdfFactory = null))
    private val root = File(context.filesDir, "publications").apply { mkdirs() }

    fun dir(id: String) = File(root, UUID.fromString(id).toString())

    fun original(b: Book) = File(dir(b.id), "original." + b.format)

    fun prepared(b: Book, encoding: Charset) =
        File(dir(b.id), "prepared-" + encoding.name() + ".utf8")

    fun revision(b: Book) = File(dir(b.id), "revisions/" + UUID.fromString(b.revision))

    fun renderFile(b: Book) =
        if (b.format == "txt") File(revision(b), "reader.epub") else original(b)

    suspend fun recover(started: Long) {
        shelf.abandoned(started).forEach { remove(it) }
        root
            .listFiles()
            .orEmpty()
            .filter {
                it.isDirectory &&
                    it.lastModified() < started &&
                    runCatching { UUID.fromString(it.name) }.isSuccess
            }
            .forEach { if (shelf.book(it.name) == null) it.deleteRecursively() }
    }

    suspend fun publication(file: File): org.readium.r2.shared.publication.Publication {
        val asset = assets.retrieve(file.toUrl()).getOrNull() ?: throw IOException("EPUB")
        val pub = opener.open(asset, allowUserInteraction = false).getOrNull()
        if (pub == null || pub.readingOrder.isEmpty() || pub.isRestricted) {
            pub?.close()
            asset.close()
            throw IOException("EPUB / DRM")
        }
        return pub
    }

    // Staging stays on the same filesystem as the atomic rename. No source URI is retained.
    suspend fun stage(uri: Uri): Book =
        withContext(Dispatchers.IO) {
            val id = uid()
            val folder = dir(id).apply { check(mkdir()) }
            try {
                val name =
                    context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Book"
                val digest = MessageDigest.getInstance("SHA-256")
                val raw = File(folder, "incoming")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    raw.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            size += n
                            require(size <= 1024L * 1024 * 1024 && folder.usableSpace > 8_388_608)
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                        }
                    }
                }
                val head =
                    raw.inputStream().use {
                        val buf = ByteArray(5)
                        it.read(buf)
                        buf
                    }
                val fmt =
                    when {
                        String(head, Charsets.US_ASCII) == "%PDF-" -> "pdf"
                        head[0] == 80.toByte() && head[1] == 75.toByte() -> "epub"
                        else -> "txt"
                    }
                var b =
                    Book(
                        id,
                        name,
                        fmt,
                        digest.digest().joinToString("") { "%02x".format(it) },
                        false,
                    )
                Files.move(raw.toPath(), original(b).toPath())
                when (fmt) {
                    "pdf" ->
                        ParcelFileDescriptor.open(original(b), ParcelFileDescriptor.MODE_READ_ONLY)
                            .use { fd -> PdfRenderer(fd).use { require(it.pageCount > 0) } }
                    "epub" -> {
                        ZipFile(original(b)).use { zip ->
                            require(
                                zip.size() <= 20000 &&
                                    zip.getEntry("META-INF/container.xml") != null
                            )
                            var expanded = 0L
                            for (entry in zip.entries()) {
                                require(
                                    !entry.name.startsWith("/") &&
                                        !entry.name.contains('\\') &&
                                        ".." !in entry.name.split('/')
                                )
                                require(entry.size in 0..64L * 1024 * 1024)
                                expanded += entry.size
                                require(expanded <= 1024L * 1024 * 1024)
                            }
                        }
                        val pub = publication(original(b))
                        try {
                            b =
                                b.copy(
                                    title = pub.metadata.title?.takeIf { it.isNotBlank() } ?: name,
                                    author = pub.metadata.authors.joinToString { it.name },
                                )
                        } finally {
                            pub.close()
                        }
                    }
                }
                b
            } catch (e: Throwable) {
                folder.deleteRecursively()
                throw e
            }
        }

    suspend fun commit(b: Book, permanent: Boolean, charset: Charset, rule: HeadingRule): Book =
        withContext(Dispatchers.IO) {
            var result = b.copy(permanent = permanent)
            if (b.format == "txt") {
                val rev = uid()
                result = result.copy(revision = rev)
                val folder = revision(result).apply { mkdirs() }
                try {
                    val ready = prepared(b, charset)
                    if (ready.exists())
                        Files.copy(ready.toPath(), File(folder, "text.utf8").toPath())
                    else TextFiles.decode(original(b), File(folder, "text.utf8"), charset)
                    TextFiles.publish(folder, b.title, rule)
                    File(folder, "encoding").writeText(charset.name())
                } catch (e: Throwable) {
                    folder.deleteRecursively()
                    throw e
                }
            }
            shelf.add(result)
            dir(result.id)
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith("prepared-") }
                .forEach { it.delete() }
            result
        }

    suspend fun remove(b: Book) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                shelf.clearMarks(b.id)
                shelf.remove(b.id)
            }
            dir(b.id).deleteRecursively()
            Unit
        }
}
