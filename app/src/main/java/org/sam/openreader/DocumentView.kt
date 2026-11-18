package org.sam.openreader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.search

data class Destination(val label: String, val location: String)

fun parseLocator(s: String): Locator? = runCatching { Locator.fromJSON(JSONObject(s)) }.getOrNull()

class ReaderState(initial: String, initialProgress: Double = 0.0) {
    var location by mutableStateOf(initial)
    var positionReady by mutableStateOf(false)
    var section by mutableIntStateOf(1)
    var page by mutableIntStateOf(1)
    var total by mutableIntStateOf(0)
    var progress by mutableDoubleStateOf(initialProgress.coerceIn(0.0, 1.0))
    var chapterPageKnown by mutableStateOf(true)
    var destinations by mutableStateOf(emptyList<Destination>())
    var jump: (String) -> Unit = {}
    var onManualTurn: (String) -> Unit = {}
    var flip: (Boolean) -> Unit = {}
    var search: suspend (String) -> List<Destination> = { emptyList() }
    var previous by mutableStateOf("")

    fun visit(dest: String) {
        previous = location
        onManualTurn(dest)
        jump(dest)
    }
}

private fun Look.preferences() =
    EpubPreferences(
        fontSize = size / 100.0,
        fontFamily = FontFamily(english + " / " + chinese),
        publisherStyles = publisher,
        scroll = false,
        lineHeight = spacing / 100.0,
        pageMargins = margins / 100.0,
        backgroundColor =
            org.readium.r2.navigator.preferences.Color(
                android.graphics.Color.parseColor(colors[0])
            ),
        textColor =
            org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor(colors[1])),
    )

@Composable
fun EpubView(
    activity: MainActivity,
    pub: Publication,
    state: ReaderState,
    look: Look,
    modifier: Modifier,
    textLength: Long?,
    toggle: () -> Unit,
) {
    var host by remember { mutableStateOf<PageTurn?>(null) }
    var nav by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var loaded by remember { mutableIntStateOf(0) }
    var resourcePage by remember { mutableIntStateOf(1) }
    var resourceTotal by remember { mutableIntStateOf(0) }
    val onToggle by rememberUpdatedState(toggle)
    val zone by rememberUpdatedState(look.zone / 100f)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PageTurn(ctx).also { h ->
                h.surface.addView(
                    FragmentContainerView(ctx).apply { id = R.id.navigator_container },
                    -1,
                    -1,
                )
                host = h
            }
        },
        update = { it.mode = look.turn },
    )
    DisposableEffect(host, pub) {
        val h = host
        val fm = activity.supportFragmentManager
        if (h == null) onDispose {}
        else {
            val tc =
                pub.metadata.languages.any {
                    it.contains("Hant", true) || it.contains("TW", true) || it.contains("HK", true)
                }
            val config =
                EpubNavigatorFragment.Configuration {
                    servedAssets = listOf("fonts/.*")
                    val chinese =
                        chineseFonts.mapValues { (_, v) ->
                            if (tc) v.replace("CN.", "TW.").replace("WenKai.ttf", "WenKaiTC.ttf")
                            else v
                        }
                    for ((name, file) in chinese) addFontFamilyDeclaration(FontFamily(name)) {
                        addFontFace { addSource("fonts/" + file) }
                    }
                    for ((en, file) in englishFonts) for (zh in chinese.keys) {
                        addFontFamilyDeclaration(
                            FontFamily(en + " / " + zh),
                            listOf(FontFamily(zh), FontFamily(chinese.keys.first())),
                        ) {
                            addFontFace { addSource("fonts/" + file) }
                        }
                    }
                }
            val factory =
                EpubNavigatorFactory(pub)
                    .createFragmentFactory(
                        parseLocator(state.location),
                        initialPreferences = look.preferences(),
                        paginationListener =
                            object : EpubNavigatorFragment.PaginationListener {
                                override fun onPageChanged(
                                    pageIndex: Int,
                                    totalPages: Int,
                                    locator: Locator,
                                ) {
                                    resourcePage = pageIndex + 1
                                    resourceTotal = totalPages
                                }

                                override fun onPageLoaded() {
                                    loaded++
                                }
                            },
                        configuration = config,
                    )
            val fragment =
                factory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name)
                    as EpubNavigatorFragment
            fm.commitNow { replace(R.id.navigator_container, fragment) }
            var departure: Locator? = null
            h.advance = { next ->
                departure = fragment.currentLocator.value
                if (next) fragment.goForward(false) else fragment.goBackward(false)
            }
            h.restore = { departure?.let { fragment.go(it, false) } }
            h.allowTurn = { !activity.selectingText }
            h.onTurnAccepted = {
                state.onManualTurn(fragment.currentLocator.value.toJSON().toString())
            }
            fragment.addInputListener(
                object : InputListener {
                    override fun onTap(event: TapEvent): Boolean {
                        if (activity.selectingText) return true
                        when {
                            event.point.x < h.width * zone -> h.turn(false)
                            event.point.x > h.width * (1 - zone) -> h.turn(true)
                            else -> onToggle()
                        }
                        return true
                    }
                }
            )
            state.flip = h::turn
            state.jump = { s -> parseLocator(s)?.let { fragment.go(it, false) } }
            val entries = mutableListOf<Destination>()
            fun add(links: List<Link>) {
                for (link in links) {
                    pub.locatorFromLink(link)?.let {
                        entries +=
                            Destination(link.title ?: link.href.toString(), it.toJSON().toString())
                    }
                    add(link.children)
                }
            }
            add(pub.tableOfContents.ifEmpty { pub.readingOrder })
            state.destinations = entries
            state.search = { query ->
                val result = mutableListOf<Destination>()
                pub.search(query)?.let { iter ->
                    try {
                        while (result.size < 100) {
                            currentCoroutineContext().ensureActive()
                            val batch = iter.next().getOrNull() ?: break
                            for (loc in batch.locators) result +=
                                Destination(
                                    listOfNotNull(
                                            loc.text.before,
                                            loc.text.highlight,
                                            loc.text.after,
                                        )
                                        .joinToString(""),
                                    loc.toJSON().toString(),
                                )
                        }
                    } finally {
                        iter.close()
                    }
                }
                result.take(100)
            }
            nav = fragment
            onDispose {
                nav = null
                if (fragment.isAdded && !fm.isDestroyed)
                    fm.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
            }
        }
    }
    LaunchedEffect(nav, look) { nav?.submitPreferences(look.preferences()) }
    LaunchedEffect(nav, loaded, look.colors, look.publisher) {
        val chapterTitles =
            if (look.publisher) ""
            else
                "h1,h2,h3{font-size:1.35em!important;font-weight:600!important;" +
                    "margin-bottom:.5em!important;break-after:avoid-page;page-break-after:avoid}"
        val css =
            "a { color:" +
                look.colors[3] +
                " !important;text-decoration:underline } ::selection {background:" +
                look.colors[10] +
                ";color:" +
                look.colors[11] +
                "} :focus-visible {outline:2px solid " +
                look.colors[8] +
                "}" + chapterTitles
        nav?.evaluateJavascript(
            "(()=>{let s=document.getElementById('openreader-user-colors');if(!s){s=document.createElement('style');s.id='openreader-user-colors';document.head.append(s)}s.textContent=" +
                JSONObject.quote(css) +
                "})()"
        )
    }
    val counts =
        remember(
            pub,
            look.size,
            look.english,
            look.chinese,
            look.margins,
            look.spacing,
            look.publisher,
        ) {
            mutableMapOf<Int, Int>()
        }
    LaunchedEffect(nav, resourcePage, resourceTotal, loaded, counts) {
        nav?.currentLocator?.collectLatest { loc ->
            // No provisional drag location reaches persistent resume state.
            while (host?.pending == true) delay(24)
            val at = pub.readingOrder.indexOfFirst { it.url() == loc.href }
            val tocLocations = state.destinations.mapNotNull { parseLocator(it.location) }
            val spine = pub.readingOrder.map { it.url() }
            val anchors = tocLocations.map { spine.indexOf(it.href) }
            // Resolve interior navigation anchors against their actual on-screen position.
            val ids =
                org.json.JSONArray(
                    tocLocations.map {
                        if (it.href == loc.href) it.locations.fragments.firstOrNull().orEmpty()
                        else ""
                    }
                )
            val positions =
                nav?.evaluateJavascript(
                        "Array.from(" +
                            ids +
                            ").map(id=>{const e=id&&document.getElementById(id);if(!e)return null;const r=e.getBoundingClientRect();return Math.floor((r.left+window.scrollX+1)/Math.max(1,innerWidth))})"
                    )
                    ?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
            val toc =
                anchors.indices.lastOrNull { i ->
                    anchors[i] in 0 until at ||
                        (anchors[i] == at && (positions?.optInt(i, 0) ?: 0) < resourcePage)
                } ?: 0
            state.section = (toc + 1).coerceAtLeast(1)
            val start = anchors.getOrElse(toc) { at }.coerceAtLeast(0)
            val next = anchors.getOrNull(toc + 1) ?: spine.size
            val startPage = positions?.optInt(toc, 0) ?: 0
            if (resourceTotal > 0) counts[at] = resourceTotal
            state.chapterPageKnown =
                (start until at).all { it in counts } &&
                    (start == at ||
                        tocLocations.getOrNull(toc)?.locations?.fragments.isNullOrEmpty())
            state.page =
                ((start until at).sumOf { counts[it] ?: 0 } + resourcePage - startPage)
                    .coerceAtLeast(1)
            val sameResourceEnd = next == at && positions?.isNull(toc + 1) == false
            val fragmentedEnd =
                tocLocations.getOrNull(toc + 1)?.locations?.fragments?.isNotEmpty() == true
            state.total =
                when {
                    sameResourceEnd && start == at ->
                        ((positions?.optInt(toc + 1) ?: 0) - startPage + 1).coerceAtLeast(1)
                    !fragmentedEnd &&
                        state.chapterPageKnown &&
                        (start until next).all { it in counts } ->
                        (start until next).sumOf { counts[it] ?: 0 } - startPage
                    else -> 0
                }
            val json = loc.toJSON()
            state.progress =
                loc.locations.totalProgression
                    ?: ((at.coerceAtLeast(0) + (loc.locations.progression ?: 0.0)) /
                        pub.readingOrder.size)
            if (textLength != null) {
                val found =
                    nav?.evaluateJavascript(
                        """(()=>{const list=document.querySelectorAll("span[id^='s']");if(!list.length)return null;let l=0,r=list.length-1;while(l<r){const m=(l+r)>>1;const rs=list[m].getClientRects();if(rs.length&&rs[rs.length-1].right<=1)l=m+1;else r=m}const e=list[l],t=e.firstChild;let a=0,b=t&&t.length||0;while(a<b){const m=(a+b)>>1,range=document.createRange();range.setStart(t,m);range.setEnd(t,m+1);if(range.getBoundingClientRect().right<=1)a=m+1;else b=m}return [e.id,Number(e.id.slice(1))+a]})()"""
                    )
                val result = runCatching { org.json.JSONArray(found) }.getOrNull()
                if (result != null) {
                    val offset = result.getLong(1)
                    val locations =
                        json.optJSONObject("locations")
                            ?: JSONObject().also { json.put("locations", it) }
                    locations.put("canonicalOffset", offset)
                    state.progress = if (textLength == 0L) 0.0 else offset.toDouble() / textLength
                }
            }
            if (
                textLength != 0L &&
                    at == spine.lastIndex &&
                    resourceTotal > 0 &&
                    resourcePage == resourceTotal
            )
                state.progress = 1.0
            state.location = json.toString()
            state.positionReady = true
        }
    }
}

class PdfBook(val file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    val count = renderer.pageCount

    @Synchronized
    fun render(i: Int, w: Int, h: Int): Bitmap =
        renderer.openPage(i).use { page ->
            val scale =
                minOf(
                    w.coerceIn(1, 2200).toFloat() / page.width,
                    h.coerceIn(1, 3000).toFloat() / page.height,
                )
            Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                .also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
        }

    @Synchronized
    fun text(i: Int): String =
        if (Build.VERSION.SDK_INT >= 35)
            renderer.openPage(i).use { it.textContents.joinToString("\n") { part -> part.text } }
        else ""

    @Synchronized
    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

@Composable
fun PdfView(pdf: PdfBook, state: ReaderState, look: Look, modifier: Modifier, toggle: () -> Unit) {
    var page by remember {
        mutableIntStateOf((state.location.toIntOrNull() ?: 0).coerceIn(0, pdf.count - 1))
    }
    var host by remember { mutableStateOf<PageTurn?>(null) }
    var target by remember { mutableStateOf<ImageView?>(null) }
    val onToggle by rememberUpdatedState(toggle)
    val zone by rememberUpdatedState(look.zone / 100f)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PageTurn(ctx).also { h ->
                val img = ImageView(ctx)
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                h.surface.addView(img, -1, -1)
                var origin = page
                var zoom = 1f
                val scale =
                    ScaleGestureDetector(
                        ctx,
                        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            override fun onScale(d: ScaleGestureDetector): Boolean {
                                zoom = (zoom * d.scaleFactor).coerceIn(1f, 4f)
                                img.scaleX = zoom
                                img.scaleY = zoom
                                if (zoom == 1f) {
                                    img.translationX = 0f
                                    img.translationY = 0f
                                }
                                return true
                            }
                        },
                    )
                val tap =
                    GestureDetector(
                        ctx,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent) = true

                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                if (zoom > 1 || e.x in h.width * zone..h.width * (1 - zone))
                                    onToggle()
                                else h.turn(e.x > h.width / 2)
                                return true
                            }

                            override fun onScroll(
                                e1: MotionEvent?,
                                e2: MotionEvent,
                                dx: Float,
                                dy: Float,
                            ): Boolean {
                                if (zoom <= 1f) return false
                                img.translationX =
                                    (img.translationX - dx).coerceIn(
                                        -img.width * (zoom - 1) / 2,
                                        img.width * (zoom - 1) / 2,
                                    )
                                img.translationY =
                                    (img.translationY - dy).coerceIn(
                                        -img.height * (zoom - 1) / 2,
                                        img.height * (zoom - 1) / 2,
                                    )
                                return true
                            }
                        },
                    )
                img.setOnTouchListener { _, e ->
                    scale.onTouchEvent(e)
                    tap.onTouchEvent(e)
                    true
                }
                h.allowTurn = { zoom == 1f }
                h.advance = { next ->
                    origin = page
                    val dest = (page + if (next) 1 else -1).coerceIn(0, pdf.count - 1)
                    val moved = dest != page
                    page = dest
                    moved
                }
                h.restore = { page = origin }
                h.onTurnAccepted = { state.onManualTurn(page.toString()) }
                h.settled = { state.location = page.toString() }
                state.flip = h::turn
                state.jump = { s -> s.toIntOrNull()?.let { page = it.coerceIn(0, pdf.count - 1) } }
                host = h
                target = img
            }
        },
        update = { it.mode = look.turn },
    )
    LaunchedEffect(pdf) {
        state.destinations =
            (0 until pdf.count).map { Destination((it + 1).toString(), it.toString()) }
        state.search = { q ->
            withContext(Dispatchers.IO) {
                buildList {
                    for (i in 0 until pdf.count) {
                        ensureActive()
                        val t = pdf.text(i)
                        val j = t.indexOf(q, ignoreCase = true)
                        if (j >= 0)
                            add(
                                Destination(
                                    t.substring(
                                        (j - 25).coerceAtLeast(0),
                                        (j + q.length + 25).coerceAtMost(t.length),
                                    ),
                                    i.toString(),
                                )
                            )
                        if (size >= 100) break
                    }
                }
            }
        }
    }
    LaunchedEffect(target, page) {
        val view = target ?: return@LaunchedEffect
        while (view.width == 0 || view.height == 0) delay(16)
        val bitmap = withContext(Dispatchers.IO) { pdf.render(page, view.width, view.height) }
        view.setImageBitmap(bitmap)
        state.page = page + 1
        state.total = pdf.count
        state.progress = (page + 1.0) / pdf.count
        if (host?.pending != true) state.location = page.toString()
        state.positionReady = true
    }
}
