package org.sam.openreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment

val lookKey = stringPreferencesKey("appearance")
val stylesKey = stringPreferencesKey("styles")

class MainActivity : AppCompatActivity() {
    private var external by mutableStateOf<Uri?>(null)
    private var active by mutableStateOf(false)
    var selectingText by mutableStateOf(false)
        private set

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        selectingText = true
    }

    override fun onActionModeFinished(mode: ActionMode) {
        selectingText = false
        super.onActionModeFinished(mode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receive(intent)
        setContent {
            OpenReader(this, (application as ReaderApp).library, active, external) {
                external = null
            }
        }
    }

    override fun onStart() {
        super.onStart()
        active = true
    }

    override fun onStop() {
        active = false
        super.onStop()
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    @Suppress("DEPRECATION")
    private fun receive(i: Intent) {
        external =
            when (i.action) {
                Intent.ACTION_VIEW -> i.data
                Intent.ACTION_SEND ->
                    i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: i.clipData?.getItemAt(0)?.uri
                else -> null
            }?.takeIf { it.scheme == "content" }
    }
}

enum class Panel {
    MENU,
    CONTENTS,
    APPEARANCE,
    LISTEN,
    CHAPTERS,
    CLEANUP,
    SETTINGS,
    LICENSES,
    ACTIONS,
    REMOVE,
}

@Composable
private fun shelfProgress(book: Book): String =
    if (!book.progress.isFinite() || book.progress < 0.0) {
        if (book.format == "pdf")
            stringResource(R.string.saved_page, (book.visual.toIntOrNull() ?: 0) + 1)
        else stringResource(R.string.saved_position)
    } else {
        ((book.progress.coerceIn(0.0, 1.0) * 100).toInt()).toString() + "%"
    }

@Composable
fun OpenReader(
    activity: MainActivity,
    lib: Library,
    active: Boolean,
    external: Uri?,
    consumed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val shelf by lib.shelf.books().collectAsState(emptyList())
    val prefs by activity.settings.data.collectAsState(emptyPreferences())
    val names =
        remember(prefs) {
            val j = JSONObject(prefs[stylesKey] ?: "{}")
            j.keys().asSequence().associateWith { j.getString(it) }
        }
    val global = remember(prefs) { Look.read(prefs[lookKey] ?: "") }
    var book by remember { mutableStateOf<Book?>(null) }
    var id by rememberSaveable { mutableStateOf("") }
    var staged by remember { mutableStateOf<Book?>(null) }
    var duplicate by remember { mutableStateOf<Book?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var selected by remember { mutableStateOf<Book?>(null) }
    var message by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var restore by remember { mutableStateOf<BackupPreview?>(null) }
    var removed by remember { mutableStateOf<Book?>(null) }
    LaunchedEffect(Unit) {
        removed = lib.shelf.pendingRemoval(System.currentTimeMillis()).firstOrNull()
    }
    LaunchedEffect(removed) {
        removed?.let { item ->
            val removedAt = lib.shelf.book(item.id)?.removed ?: 0L
            if (removedAt > 0L) {
                delay((removedAt + 6500L - System.currentTimeMillis()).coerceAtLeast(0L))
                val latest = lib.shelf.book(item.id)
                if (latest?.removed == removedAt) lib.remove(latest)
            }
            if (removed?.id == item.id) removed = null
        }
    }
    val state =
        key(book?.id, book?.revision) {
            remember {
                ReaderState(
                    book?.visual.orEmpty(),
                    book?.progress?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                )
            }
        }
    var operation by remember { mutableStateOf<suspend (Uri) -> Unit>({}) }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            if (uri != null)
                job =
                    scope.launch {
                        try {
                            operation(uri)
                            message = R.string.copy_saved
                        } catch (e: Exception) {
                            if (e !is CancellationException) message = R.string.invalid
                        } finally {
                            job = null
                        }
                    }
        }
    fun run(action: suspend () -> Unit) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                try {
                    action()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    message = R.string.invalid
                } finally {
                    job = null
                }
            }
    }
    fun open(b: Book) {
        book = b
        id = b.id
        panel = null
        selected = null
    }
    fun stage(uri: Uri) {
        run {
            val stagedBook = lib.stage(uri)
            duplicate = lib.shelf.duplicate(stagedBook.hash)
            staged = stagedBook
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(::stage)
        }
    val backupPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) run { restore = lib.previewBackup(uri) }
        }
    fun closeStage() {
        val b = staged
        staged = null
        duplicate = null
        if (b != null) scope.launch(Dispatchers.IO) { lib.dir(b.id).deleteRecursively() }
    }
    LaunchedEffect(external) {
        external?.let {
            stage(it)
            consumed()
        }
    }
    LaunchedEffect(id) { if (id.isNotEmpty() && book?.id != id) book = lib.shelf.book(id) }
    fun leave() {
        val b = book
        book = null
        id = ""
        panel = null
        selected = null
        if (b != null)
            scope.launch {
                if (state.positionReady && state.location.isNotEmpty())
                    lib.shelf.position(
                        b.id,
                        b.revision,
                        state.location,
                        state.progress.coerceIn(0.0, 1.0),
                    )
                if (!b.permanent && SpeechService.status.value.book != b.id) lib.remove(b)
            }
    }
    BackHandler(enabled = book != null && panel == null && staged == null) { leave() }
    BackHandler(enabled = panel == Panel.MENU) { panel = null }
    val base = book?.style?.takeIf { it.isNotBlank() }?.let(Look::read) ?: global
    val look =
        names[base.styleId]?.let {
            val saved = Look.read(it)
            base.copy(family = saved.family, variant = saved.variant, colors = saved.colors)
        } ?: base
    val scheme =
        lightColorScheme(
            primary = look.color(6),
            onPrimary = look.color(7),
            surface = look.color(4),
            onSurface = look.color(5),
            background = look.color(0),
            onBackground = look.color(1),
            outline = look.color(9),
        )
    fun copyFile(b: Book, edited: Boolean) {
        val file = if (edited) File(lib.revision(b), "text.utf8") else lib.original(b)
        operation = { uri ->
            withContext(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            }
            Unit
        }
        export.launch(
            if (edited) b.title.substringBeforeLast('.') + "-edited.txt"
            else if (b.title.endsWith("." + b.format, true)) b.title else b.title + "." + b.format
        )
    }
    fun pack(books: List<Book>, wholeShelf: Boolean = false) {
        operation = { uri ->
            lib.backup(uri, books, global.json(), prefs[stylesKey] ?: "{}", wholeShelf)
        }
        export.launch(
            if (books.size == 1) books.first().title + ".openreader.zip"
            else "OpenReader-library.zip"
        )
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val b = book
                if (b == null) {
                    var query by rememberSaveable { mutableStateOf("") }
                    var sort by rememberSaveable { mutableIntStateOf(0) }
                    val sorts =
                        listOf(
                                R.string.recent,
                                R.string.title,
                                R.string.author,
                                R.string.import_date,
                            )
                            .map { stringResource(it) }
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.library),
                                style = MaterialTheme.typography.headlineLarge,
                            )
                            Action(R.string.settings) { panel = Panel.SETTINGS }
                        }
                        Button(
                            onClick = {
                                picker.launch(
                                    arrayOf(
                                        "application/epub+zip",
                                        "text/plain",
                                        "application/pdf",
                                        "application/octet-stream",
                                        "*/*",
                                    )
                                )
                            }
                        ) {
                            Text(stringResource(R.string.import_book))
                        }
                        if (shelf.isEmpty())
                            Text(
                                stringResource(R.string.empty_shelf),
                                Modifier.padding(vertical = 20.dp),
                            )
                        else {
                            val current = shelf.first()
                            ListItem(
                                headlineContent = { Text(current.title) },
                                supportingContent = {
                                    Text(stringResource(R.string.continue_reading) + " · " + shelfProgress(current))
                                },
                                modifier = Modifier.clickable { open(current) },
                            )
                            OutlinedTextField(
                                query,
                                { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.search)) },
                                singleLine = true,
                            )
                            Choice(stringResource(R.string.sort), sorts[sort], sorts) { sort = it }
                            val matches =
                                shelf.filter {
                                    it.title.contains(query, true) ||
                                        it.author.contains(query, true)
                                }
                            val rows =
                                when (sort) {
                                    1 -> matches.sortedBy { it.title.lowercase() }
                                    2 -> matches.sortedBy { it.author }
                                    3 -> matches.sortedByDescending { it.imported }
                                    else -> matches
                                }
                            LazyColumn {
                                items(rows, key = { it.id }) { item ->
                                    ListItem(
                                        headlineContent = { Text(item.title) },
                                        supportingContent = {
                                            Text(
                                                listOf(
                                                        item.format.uppercase(),
                                                        item.author,
                                                        shelfProgress(item),
                                                    )
                                                    .filter { it.isNotBlank() }
                                                    .joinToString(" · ")
                                            )
                                        },
                                        trailingContent = {
                                            TextButton(
                                                onClick = {
                                                    selected = item
                                                    panel = Panel.ACTIONS
                                                }
                                            ) {
                                                Text("⋮", contentDescriptionModifier())
                                            }
                                        },
                                        modifier = Modifier.clickable { open(item) },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                } else if (active)
                    key(b.id, b.revision) {
                        ForegroundReader(activity) {
                            ReaderScreen(
                                activity,
                                lib,
                                b,
                                state,
                                look,
                                panel,
                                { panel = it },
                                ::leave,
                                { message = R.string.invalid },
                            )
                        }
                    }
                val p = panel
                if (p != null && p != Panel.MENU) {
                    ModalBottomSheet(
                        onDismissRequest = { panel = if (book == null) null else Panel.MENU }
                    ) {
                        when (p) {
                            Panel.SETTINGS ->
                                Column(Modifier.padding(20.dp)) {
                                    val locales =
                                        listOf(
                                            stringResource(R.string.follow_system),
                                            "English",
                                            "简体中文",
                                            "繁體中文",
                                        )
                                    val tags = listOf("", "en", "zh-Hans", "zh-Hant")
                                    Choice(
                                        stringResource(R.string.language),
                                        locales[
                                            tags
                                                .indexOf(
                                                    AppCompatDelegate.getApplicationLocales()
                                                        .toLanguageTags()
                                                )
                                                .coerceAtLeast(0)],
                                        locales,
                                    ) {
                                        AppCompatDelegate.setApplicationLocales(
                                            LocaleListCompat.forLanguageTags(tags[it])
                                        )
                                    }
                                    Action(R.string.colors) { panel = Panel.APPEARANCE }
                                    Action(R.string.backup) { pack(shelf, true) }
                                    Action(R.string.restore_backup) {
                                        backupPicker.launch(
                                            arrayOf(
                                                "application/zip",
                                                "application/octet-stream",
                                                "*/*",
                                            )
                                        )
                                    }
                                    Action(R.string.licenses) { panel = Panel.LICENSES }
                                }
                            Panel.LICENSES -> LicensePanel(activity)
                            Panel.APPEARANCE ->
                                Appearance(
                                    look,
                                    b != null,
                                    names,
                                    b?.format == "pdf",
                                    { v, local ->
                                        run {
                                            if (local && b != null) {
                                                lib.shelf.style(b.id, v.json())
                                                book = b.copy(style = v.json())
                                            } else {
                                                activity.settings.edit { it[lookKey] = v.json() }
                                                if (b != null) {
                                                    lib.shelf.style(b.id, "")
                                                    book = b.copy(style = "")
                                                }
                                            }
                                            panel = null
                                        }
                                    },
                                    {
                                        if (b != null)
                                            run {
                                                lib.shelf.style(b.id, "")
                                                book = b.copy(style = "")
                                                panel = null
                                            }
                                    },
                                    { name, v ->
                                        scope.launch {
                                            activity.settings.edit {
                                                it[stylesKey] =
                                                    JSONObject(it[stylesKey] ?: "{}")
                                                        .put(name, v.json())
                                                        .toString()
                                            }
                                        }
                                    },
                                    { name, newName ->
                                        scope.launch {
                                            activity.settings.edit {
                                                val j = JSONObject(it[stylesKey] ?: "{}")
                                                val removedStyle=j.remove(name)
                                                if(newName!=null && removedStyle!=null)j.put(newName,Look.read(removedStyle.toString()).copy(styleId=newName).json())
                                                val current=Look.read(it[lookKey]?:"")
                                                if(current.styleId==name){
                                                    it[lookKey]=if(newName==null)current.copy(family="Paper",variant=2,colors=preset("Paper",2),styleId="").json()
                                                        else current.copy(styleId=newName).json()
                                                }
                                                it[stylesKey] = j.toString()
                                            }
                                            for(item in (shelf+listOfNotNull(book)).distinctBy{it.id}){
                                                val current=Look.read(item.style)
                                                if(current.styleId==name){
                                                    val value=if(newName==null)"" else current.copy(styleId=newName).json()
                                                    lib.shelf.style(item.id,value)
                                                    if(book?.id==item.id)book=book?.copy(style=value)
                                                }
                                            }
                                        }
                                    },
                                )
                            Panel.CONTENTS ->
                                if (b != null) ContentsPanel(lib, b, state) { panel = null }
                            Panel.LISTEN ->
                                if (b != null) ListenPanel(activity, b, state) { panel = null }
                            Panel.CHAPTERS,
                            Panel.CLEANUP ->
                                if (b != null) {
                                    if (!b.permanent)
                                        Column(Modifier.padding(18.dp)) {
                                            Text(stringResource(R.string.transient_hint))
                                            Action(R.string.keep) {
                                                run {
                                                    lib.shelf.keep(b.id)
                                                    book = b.copy(permanent = true)
                                                }
                                            }
                                        }
                                    else
                                        EditPanel(
                                            lib,
                                            b,
                                            p == Panel.CHAPTERS,
                                            { changed ->
                                                book = changed
                                                panel = null
                                            },
                                            { message = R.string.invalid },
                                        )
                                }
                            Panel.ACTIONS ->
                                (selected ?: b)?.let { item ->
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .padding(18.dp)
                                    ) {
                                        var title by
                                            remember(item.id) { mutableStateOf(item.title) }
                                        OutlinedTextField(
                                            title,
                                            { title = it },
                                            label = { Text(stringResource(R.string.title)) },
                                        )
                                        Action(R.string.rename) {
                                            if (title.isNotBlank())
                                                run {
                                                    lib.shelf.rename(item.id, title)
                                                    if (b?.id == item.id)
                                                        book = item.copy(title = title)
                                                    panel = null
                                                }
                                        }
                                        Action(R.string.export_original) { copyFile(item, false) }
                                        if (item.format == "txt") {
                                            Action(R.string.export_edited) { copyFile(item, true) }
                                            if (b != null) {
                                                Action(R.string.chapters) { panel = Panel.CHAPTERS }
                                                Action(R.string.cleanup) { panel = Panel.CLEANUP }
                                            }
                                        }
                                        Action(R.string.package_book) { pack(listOf(item)) }
                                        if (item.permanent)
                                            Action(R.string.remove) { panel = Panel.REMOVE }
                                        else
                                            Action(R.string.keep) {
                                                run {
                                                    lib.shelf.keep(item.id)
                                                    book = item.copy(permanent = true)
                                                    panel = null
                                                }
                                            }
                                    }
                                }
                            Panel.REMOVE ->
                                Column(Modifier.padding(20.dp)) {
                                    Text(stringResource(R.string.remove_blurb))
                                    Action(R.string.remove) {
                                        val item = selected ?: b
                                        if (item != null)
                                            run {
                                                SpeechService.quit(activity, item.id)
                                                removed?.let { lib.remove(it) }
                                                lib.shelf.trash(item.id, System.currentTimeMillis())
                                                removed = item
                                                if (b?.id == item.id) {
                                                    book = null
                                                    id = ""
                                                }
                                                selected = null
                                                panel = null
                                            }
                                    }
                                }
                            else -> Unit
                        }
                    }
                }
                removed?.let { item ->
                    Snackbar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        action = {
                            Action(R.string.undo) {
                                run {
                                    lib.shelf.trash(item.id, 0)
                                    removed = null
                                }
                            }
                        },
                    ) {
                        Text(item.title)
                    }
                }
                staged?.let { pending ->
                    ImportDialog(
                        lib,
                        pending,
                        duplicate != null,
                        ::closeStage,
                        {
                            duplicate?.let { open(it) }
                            closeStage()
                        },
                    ) { permanent, encoding, rule ->
                        run {
                            val saved = lib.commit(pending, permanent, charset(encoding), rule)
                            staged = null
                            duplicate = null
                            open(saved)
                        }
                    }
                }
                restore?.let { snapshot ->
                    val entries = snapshot.manifest.getJSONArray("books")
                    AlertDialog(
                        onDismissRequest = {
                            restore = null
                            snapshot.file.delete()
                        },
                        title = { Text(stringResource(R.string.restore_backup)) },
                        text = {
                            Text(
                                (0 until minOf(12, entries.length())).joinToString("\n") {
                                    entries.getJSONObject(it).getString("title")
                                }
                            )
                        },
                        confirmButton = {
                            Column {
                                listOf(true, false).forEach { skip ->
                                    Action(
                                        if (skip) R.string.skip_duplicates
                                        else R.string.import_copies
                                    ) {
                                        run {
                                            val imported =
                                                snapshot.manifest.optJSONObject("styles")
                                                    ?: JSONObject()
                                            val merged = JSONObject(prefs[stylesKey] ?: "{}")
                                            val mapped = mergeStyles(imported, merged)
                                            val restoreGlobal =
                                                snapshot.manifest.optBoolean("library") &&
                                                    shelf.isEmpty()
                                            val incoming =
                                                Look.read(snapshot.manifest.optString("settings"))
                                            val destGlobal =
                                                if (restoreGlobal)
                                                    incoming
                                                        .copy(
                                                            styleId =
                                                                mapped[incoming.styleId]
                                                                    ?: incoming.styleId
                                                        )
                                                        .json()
                                                else global.json()
                                            lib.restoreBackup(snapshot, skip, mapped, destGlobal)
                                            activity.settings.edit { p0 ->
                                                p0[stylesKey] = merged.toString()
                                                if (restoreGlobal) p0[lookKey] = destGlobal
                                            }
                                            restore = null
                                            snapshot.file.delete()
                                            panel = null
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
                if (job != null)
                    AlertDialog(
                        onDismissRequest = {},
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator()
                                Spacer(Modifier.width(16.dp))
                                Text(stringResource(R.string.loading))
                            }
                        },
                        confirmButton = { Action(R.string.cancel) { job?.cancel() } },
                    )
                if (message != 0)
                    AlertDialog(
                        onDismissRequest = { message = 0 },
                        text = { Text(stringResource(message)) },
                        confirmButton = { Action(R.string.close) { message = 0 } },
                    )
            }
        }
    }
}

@Composable
private fun contentDescriptionModifier(): Modifier {
    val desc = stringResource(R.string.more)
    return Modifier.semantics { contentDescription = desc }
}

@Composable
private fun LicensePanel(activity: MainActivity) {
    val files = remember {
        activity.assets.list("fonts").orEmpty().filter { it.endsWith("-LICENSE.txt") }
    }
    var file by remember { mutableStateOf(files.first()) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
        Choice(stringResource(R.string.licenses), file.substringBefore('-'), files) {
            file = files[it]
        }
        Text(
            remember(file) {
                activity.assets.open("fonts/" + file).bufferedReader().use { it.readText() }
            }
        )
    }
}
