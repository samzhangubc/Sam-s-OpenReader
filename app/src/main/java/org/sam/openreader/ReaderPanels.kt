package org.sam.openreader

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.*
import org.readium.r2.shared.publication.Publication

/** Dispose renderer resources at ON_STOP even when the window recomposer is suspended. */
@Composable
fun ForegroundReader(activity: MainActivity, content: @Composable () -> Unit) {
    val latest by rememberUpdatedState(content)
    val parent = rememberCompositionContext()
    val host =
        remember(activity) { ComposeView(activity).apply { setParentCompositionContext(parent) } }
    AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
    DisposableEffect(activity, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> host.setContent { latest() }
                Lifecycle.Event.ON_STOP -> host.disposeComposition()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            host.disposeComposition()
        }
    }
}

@Composable
fun ReaderScreen(
    activity: MainActivity,
    lib: Library,
    b: Book,
    state: ReaderState,
    look: Look,
    panel: Panel?,
    show: (Panel?) -> Unit,
    leave: () -> Unit,
    error: () -> Unit,
) {
    var pub by remember { mutableStateOf<Publication?>(null) }
    var pdf by remember { mutableStateOf<PdfBook?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(b.id, b.revision) {
        try {
            if (b.format == "pdf") pdf = withContext(Dispatchers.IO) { PdfBook(lib.original(b)) }
            else pub = withContext(Dispatchers.IO) { lib.publication(lib.renderFile(b)) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed = true
            error()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            pub?.close()
            pdf?.close()
            val loc = state.location
            activity.lifecycleScope.launch {
                lib.shelf.position(b.id, b.revision, loc, state.progress.coerceIn(0.0, 1.0))
            }
        }
    }
    LaunchedEffect(state.location) {
        if (state.location.isNotEmpty()) {
            delay(250)
            lib.shelf.position(
                b.id,
                b.revision,
                state.location,
                state.progress.coerceIn(0.0, 1.0),
            )
        }
    }
    val toggle = { show(if (panel == null) Panel.MENU else null) }
    val prefs by activity.settings.data.collectAsState(emptyPreferences())
    val speech by SpeechService.status.collectAsState()
    LaunchedEffect(speech.locator, speech.book, prefs[booleanPreferencesKey("follow")]) {
        if (
            speech.book == b.id &&
                speech.playing &&
                speech.locator.isNotBlank() &&
                prefs[booleanPreferencesKey("follow")] == true
        ) {
            val dest =
                withContext(Dispatchers.IO) {
                    when (b.format) {
                        "txt" ->
                            textLocation(
                                lib.revision(b),
                                textOffset(lib.revision(b), speech.locator),
                            )
                        "pdf" -> speech.locator.substringBefore(':')
                        else -> speech.locator
                    }
                }
            state.jump(dest)
        }
    }
    Box(Modifier.fillMaxSize().background(look.color(0))) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val textLength =
                    remember(b.revision) {
                        if (b.format == "txt")
                            File(lib.revision(b), "text.length").readText().toLong()
                        else null
                    }
                pub?.let {
                    EpubView(activity, it, state, look, Modifier.fillMaxSize(), textLength, toggle)
                }
                pdf?.let { PdfView(it, state, look, Modifier.fillMaxSize(), toggle) }
                if (pub == null && pdf == null)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (failed) Action(R.string.back, leave) else CircularProgressIndicator()
                    }
            }
            Box(Modifier.height(34.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (panel == null)
                    Text(
                        (if (b.format == "pdf") ""
                        else stringResource(R.string.section) + " " + state.section + "  ·  ") +
                            stringResource(R.string.page) +
                            " " +
                            (if (state.chapterPageKnown) state.page.toString() else "…") +
                            (if (state.total > 0) " / " + state.total else " / …") +
                            (if (look.percent)
                                "  ·  " + (state.progress * 100).toInt().coerceIn(0, 100) + "%"
                            else ""),
                        color = look.color(2),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { show(Panel.MENU) },
                    )
            }
        }
        if (panel == Panel.MENU) {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), shadowElevation = 3.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Action(R.string.back, leave)
                    Text(b.title, Modifier.weight(1f), maxLines = 1)
                    Action(R.string.more) { show(Panel.ACTIONS) }
                }
            }
        }
        AnimatedVisibility(
            panel == Panel.MENU,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                shadowElevation = 6.dp,
                shape =
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Action(R.string.contents) { show(Panel.CONTENTS) }
                    TextButton(onClick = { show(Panel.APPEARANCE) }) { Text("Aa") }
                    Action(R.string.listen) { show(Panel.LISTEN) }
                }
            }
        }
    }
}

@Composable
fun ImportDialog(
    lib: Library,
    b: Book,
    dupe: Boolean,
    cancel: () -> Unit,
    existing: () -> Unit,
    commit: (Boolean, String, HeadingRule) -> Unit,
) {
    var encoding by remember {
        mutableStateOf(if (b.format == "txt") TextFiles.guess(lib.original(b)) else "UTF-8")
    }
    var preview by remember { mutableStateOf("") }
    var valid by remember { mutableStateOf(b.format != "txt") }
    var detect by remember { mutableStateOf(true) }
    var headings by remember { mutableStateOf(emptyList<Heading>()) }
    var excluded by remember { mutableStateOf(emptySet<Int>()) }
    var full by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(encoding) {
        if (b.format == "txt") {
            valid = false
            full = false
            scanning = true
            excluded = emptySet()
            headings = emptyList()
            val s =
                withContext(Dispatchers.IO) {
                    runCatching { TextFiles.sample(lib.original(b), charset(encoding)) }.getOrNull()
                }
            preview = s.orEmpty()
            try {
                if (s != null) {
                    headings =
                        withContext(Dispatchers.IO) {
                            val ready = lib.prepared(b, charset(encoding))
                            if (!ready.exists()) {
                                val tmp = File(lib.dir(b.id), "decode-" + uid())
                                try {
                                    TextFiles.decode(lib.original(b), tmp, charset(encoding))
                                    java.nio.file.Files.move(tmp.toPath(), ready.toPath())
                                } finally {
                                    tmp.delete()
                                }
                            }
                            TextFiles.headings(ready, HeadingRule())
                        }
                    valid = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                valid = false
            } finally {
                scanning = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(b.title, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.original_copy))
                if (b.format == "txt") {
                    Choice(stringResource(R.string.encoding), encoding, TextFiles.encodings) {
                        encoding = TextFiles.encodings[it]
                    }
                    Toggle(R.string.chapters, detect) { detect = it }
                    if (scanning) LinearProgressIndicator()
                    else if (!valid) Text(stringResource(R.string.invalid))
                    Text(preview.take(250))
                    if (valid && detect) {
                        Action(R.string.preview_all) { full = !full }
                        Text(
                            stringResource(R.string.matches) +
                                " · " +
                                (headings.size - excluded.size)
                        )
                        if (full)
                            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                                items(headings) { h ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            h.line !in excluded,
                                            { yes ->
                                                excluded =
                                                    if (yes) excluded - h.line
                                                    else excluded + h.line
                                            },
                                        )
                                        Text(h.title)
                                    }
                                }
                            }
                    }
                }
            }
        },
        confirmButton = {
            Column {
                if (dupe) Action(R.string.existing, existing)
                val rule =
                    if (detect) HeadingRule(excluded = excluded)
                    else HeadingRule(contains = "\u0000")
                Button(onClick = { commit(true, encoding, rule) }, enabled = valid) {
                    Text(stringResource(R.string.keep))
                }
                TextButton(onClick = { commit(false, encoding, rule) }, enabled = valid) {
                    Text(stringResource(R.string.open_once))
                }
            }
        },
        dismissButton = { Action(R.string.cancel, cancel) },
    )
}

@Composable
fun ContentsPanel(lib: Library, b: Book, state: ReaderState, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val marks by lib.shelf.marks(b.id).collectAsState(emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Destination>()) }
    var loading by remember { mutableStateOf(false) }
    fun visit(loc: String) {
        state.visit(loc)
        close()
    }
    LaunchedEffect(query, tab) {
        if (tab == 2 && query.isNotBlank()) {
            delay(350)
            loading = true
            try {
                results = withContext(Dispatchers.IO) { state.search(query) }
            } finally {
                loading = false
            }
        } else results = emptyList()
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 16.dp)) {
        Row {
            listOf(R.string.contents, R.string.bookmarks, R.string.search).forEachIndexed { i, s ->
                Action(s) { tab = i }
            }
        }
        Row {
            Action(R.string.previous) {
                state.flip(false)
                close()
            }
            Action(R.string.next) {
                state.flip(true)
                close()
            }
            if (state.previous.isNotBlank())
                Action(R.string.return_to) {
                    val prev = state.previous
                    state.previous = ""
                    state.jump(prev)
                    close()
                }
        }
        if (tab == 0 && b.format == "pdf") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.weight(1f),
                    label = { Text(stringResource(R.string.page)) },
                )
                Action(R.string.go) {
                    query
                        .toIntOrNull()
                        ?.takeIf { it in 1..state.total }
                        ?.let { visit((it - 1).toString()) }
                }
            }
        }
        if (tab == 1)
            Action(R.string.bookmark) {
                val loc = state.location
                scope.launch {
                    lib.shelf.mark(Bookmark(b.id, loc, "§ " + state.section + " · " + state.page))
                }
            }
        if (tab == 2)
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search)) },
            )
        if (tab == 1)
            LazyColumn {
                items(marks) { m ->
                    ListItem(
                        headlineContent = { Text(m.label) },
                        modifier = Modifier.clickable { visit(m.location) },
                        trailingContent = {
                            Action(R.string.remove) { scope.launch { lib.shelf.unmark(m) } }
                        },
                    )
                }
            }
        else {
            val rows = if (tab == 0) state.destinations else results
            if (loading) LinearProgressIndicator()
            else if (tab == 2 && query.isNotBlank() && rows.isEmpty())
                Text(stringResource(R.string.no_results))
            LazyColumn {
                items(rows) { d ->
                    ListItem(
                        headlineContent = { Text(d.label, maxLines = 3) },
                        modifier = Modifier.clickable { visit(d.location) },
                    )
                }
            }
        }
    }
}

@Composable
fun EditPanel(
    lib: Library,
    b: Book,
    chapters: Boolean,
    changed: (Book) -> Unit,
    error: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val folder = remember(b) { lib.revision(b) }
    var initial by remember { mutableStateOf(HeadingRule()) }
    var prefix by remember { mutableStateOf("第") }
    var marker by remember { mutableStateOf("章|回|节|節|卷|部") }
    var contains by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf("") }
    var bare by remember { mutableStateOf(false) }
    var loose by remember { mutableStateOf(false) }
    var excluded by remember { mutableStateOf(emptySet<Int>()) }
    var included by remember { mutableStateOf(emptySet<Int>()) }
    var manual by remember { mutableStateOf("") }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var blocks by remember { mutableStateOf(emptyList<Block>()) }
    var multiline by remember { mutableStateOf(false) }
    val profiles = remember { File(lib.context.filesDir, "profiles").apply { mkdirs() } }
    var profileName by remember { mutableStateOf("") }
    var profileFiles by remember { mutableStateOf(profiles.list().orEmpty().toList().sorted()) }
    var candidates by remember { mutableStateOf(emptyList<Heading>()) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    var regexEdit by remember { mutableStateOf(false) }
    var sensitive by remember { mutableStateOf(true) }
    var word by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(emptyList<EditRule>()) }
    LaunchedEffect(folder) {
        initial = withContext(Dispatchers.IO) { HeadingRule.load(File(folder, "profile")) }
        prefix = initial.prefix
        marker = initial.markers
        contains = initial.contains
        regex = initial.advanced
        bare = initial.bare
        loose = initial.loose
        excluded = initial.excluded
        included = initial.included
        blocks = initial.blocks
        multiline = initial.multiline
    }
    fun rule() =
        HeadingRule(
            prefix,
            marker,
            contains,
            loose,
            bare,
            regex,
            excluded,
            included,
            blocks,
            multiline,
        )
    fun discard() {
        val d = draft
        draft = null
        report = false
        if (d != null) scope.launch(Dispatchers.IO) { lib.revision(d.result).deleteRecursively() }
    }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e !is CancellationException) error()
            } finally {
                busy = false
            }
        }
    }
    fun apply(d: Draft) {
        work {
            SpeechService.quit(lib.context, b.id)
            val result = lib.applyDraft(d)
            draft = null
            changed(result)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val pending = draft
            if (pending != null) lib.revision(pending.result).deleteRecursively()
        }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(16.dp)) {
        Text(
            stringResource(if (chapters) R.string.chapters else R.string.cleanup),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                if (chapters) {
                    if (profileFiles.isNotEmpty())
                        Choice(stringResource(R.string.profiles), profileName, profileFiles) { idx
                            ->
                            discard()
                            profileName = profileFiles[idx]
                            val r = HeadingRule.load(File(profiles, profileName))
                            prefix = r.prefix
                            marker = r.markers
                            contains = r.contains
                            regex = r.advanced
                            bare = r.bare
                            loose = r.loose
                            blocks = r.blocks
                            multiline = r.multiline
                        }
                    OutlinedTextField(
                        prefix,
                        {
                            discard()
                            prefix = it
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.prefix)) },
                    )
                    OutlinedTextField(
                        marker,
                        {
                            discard()
                            marker = it
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.marker)) },
                    )
                    OutlinedTextField(
                        contains,
                        {
                            discard()
                            contains = it
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.contains)) },
                    )
                    OutlinedTextField(
                        regex,
                        {
                            discard()
                            regex = it
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.advanced)) },
                    )
                    Toggle(R.string.loose, loose) {
                        if (!busy) {
                            discard()
                            loose = it
                        }
                    }
                    Toggle(R.string.bare, bare) {
                        if (!busy) {
                            discard()
                            bare = it
                        }
                    }
                    Toggle(R.string.multiline, multiline) {
                        if (!busy) {
                            discard()
                            multiline = it
                        }
                    }
                    BlockEditor(blocks) {
                        if (!busy && it.size <= 32) {
                            discard()
                            blocks = it
                        }
                    }
                    OutlinedTextField(
                        profileName,
                        { profileName = it },
                        label = { Text(stringResource(R.string.profile_name)) },
                    )
                    Action(R.string.save) {
                        work {
                            val name = profileName.trim()
                            require(
                                name.length in 1..80 &&
                                    name.none { it == '/' || it == '\\' } &&
                                    name != "." &&
                                    name != ".."
                            )
                            rule()
                                .copy(excluded = emptySet(), included = emptySet())
                                .save(File(profiles, name))
                            profileFiles = profiles.list().orEmpty().toList().sorted()
                        }
                    }
                    Row {
                        OutlinedTextField(
                            manual,
                            { manual = it },
                            Modifier.weight(1f),
                            label = { Text(stringResource(R.string.line_number)) },
                        )
                        TextButton(
                            onClick = {
                                manual
                                    .toIntOrNull()
                                    ?.takeIf { it > 0 }
                                    ?.let {
                                        discard()
                                        included = included + it
                                        excluded = excluded - it
                                        manual = ""
                                    }
                            }
                        ) {
                            Text("+")
                        }
                    }
                } else {
                    OutlinedTextField(
                        find,
                        {
                            discard()
                            find = it
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.find_text)) },
                    )
                    OutlinedTextField(
                        replace,
                        {
                            discard()
                            replace = it
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.replacement)) },
                    )
                    Toggle(R.string.regex, regexEdit) {
                        if (!busy) {
                            discard()
                            regexEdit = it
                        }
                    }
                    Toggle(R.string.sensitive, sensitive) {
                        if (!busy) {
                            discard()
                            sensitive = it
                        }
                    }
                    val latin = find.all { it.code < 0x250 }
                    if (latin)
                        Toggle(R.string.whole_word, word) {
                            if (!busy) {
                                discard()
                                word = it
                            }
                        }
                    Action(R.string.add_rule) {
                        if (!busy && find.isNotEmpty() && batch.size < 31) {
                            discard()
                            batch =
                                batch + EditRule(find, replace, regexEdit, sensitive, word && latin)
                            find = ""
                            replace = ""
                        }
                    }
                    batch.forEachIndexed { i, r ->
                        TextButton(
                            onClick = {
                                if (!busy) {
                                    discard()
                                    batch = batch.filterIndexed { j, _ -> j != i }
                                }
                            }
                        ) {
                            Text(
                                (i + 1).toString() + " · " + r.find + " → " + r.replacement + "   ×"
                            )
                        }
                    }
                }
                if (busy) LinearProgressIndicator()
                Action(R.string.preview) {
                    work {
                        discard()
                        if (chapters) {
                            val previewRule = rule().copy(excluded = emptySet())
                            candidates =
                                withContext(Dispatchers.IO) {
                                    TextFiles.headings(File(folder, "text.utf8"), previewRule)
                                }
                            report = true
                        } else {
                            val rules =
                                batch +
                                    listOfNotNull(
                                        find
                                            .takeIf { it.isNotEmpty() }
                                            ?.let {
                                                EditRule(
                                                    it,
                                                    replace,
                                                    regexEdit,
                                                    sensitive,
                                                    word && it.all { c -> c.code < 0x250 },
                                                )
                                            }
                                    )
                            require(rules.isNotEmpty())
                            draft = lib.draft(b, initial, rules = rules)
                            report = true
                        }
                    }
                }
                if (report)
                    Text(
                        stringResource(R.string.matches) +
                            " · " +
                            (if (chapters) candidates.count { it.line !in excluded }.toLong()
                            else draft?.count ?: 0)
                    )
                draft?.examples?.forEach { Text(it) }
            }
            if (chapters)
                items(candidates, key = { it.line }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            c.line !in excluded,
                            { on ->
                                if (!busy) {
                                    excluded = if (on) excluded - c.line else excluded + c.line
                                }
                            },
                        )
                        Column {
                            Text(c.line.toString() + " · " + c.title)
                            Text(c.reason, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
        }
        Row {
            if (report)
                Action(R.string.apply) {
                    if (chapters)
                        work {
                            val d = lib.draft(b, rule())
                            SpeechService.quit(lib.context, b.id)
                            changed(lib.applyDraft(d))
                        }
                    else draft?.let(::apply)
                }
            val parent = File(folder, "parent")
            if (parent.exists())
                Action(R.string.undo) {
                    work {
                        val prev = b.copy(revision = parent.readText())
                        SpeechService.quit(lib.context, b.id)
                        val restored = lib.applyDraft(Draft(b, prev, 0, emptyList()))
                        changed(restored)
                    }
                }
            if (!chapters)
                Action(R.string.restore) {
                    work {
                        val next = b.copy(revision = uid())
                        val target = lib.revision(next)
                        withContext(Dispatchers.IO) {
                            target.mkdirs()
                            File(target, "parent").writeText(b.revision)
                            val enc =
                                File(folder, "encoding").takeIf { it.exists() }?.readText()
                                    ?: TextFiles.guess(lib.original(b))
                            TextFiles.decode(
                                lib.original(b),
                                File(target, "text.utf8"),
                                charset(enc),
                            )
                            File(target, "encoding").writeText(enc)
                            TextFiles.publish(target, b.title, initial)
                        }
                        discard()
                        draft = Draft(b, next, 0, emptyList())
                        report = true
                    }
                }
        }
    }
}
