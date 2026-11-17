package org.sam.openreader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
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
import java.io.File

data class Destination(val label:String,val location:String)
fun parseLocator(s:String):Locator? = runCatching { Locator.fromJSON(JSONObject(s)) }.getOrNull()
class ReaderState(initial:String) {
    var location by mutableStateOf(initial)
    var section by mutableIntStateOf(1)
    var page by mutableIntStateOf(1)
    var total by mutableIntStateOf(0)
    var progress by mutableDoubleStateOf(0.0)
    var destinations by mutableStateOf(emptyList<Destination>())
    var jump:(String)->Unit={}
    var flip:(Boolean)->Unit={}
    var search: suspend (String) -> List<Destination> = { emptyList() }
    var previous by mutableStateOf("")
    fun visit(dest:String){previous=location;jump(dest)}
}

private fun Look.preferences()=EpubPreferences(fontSize=size/100.0,fontFamily=FontFamily(english+" / "+chinese),
    publisherStyles=publisher,scroll=false,backgroundColor=org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor(colors[0])),
    textColor=org.readium.r2.navigator.preferences.Color(android.graphics.Color.parseColor(colors[1])))

@Composable fun EpubView(activity: FragmentActivity, pub:Publication, state:ReaderState, look:Look, modifier:Modifier, toggle:()->Unit) {
    var host by remember { mutableStateOf<PageTurn?>(null) }
    var nav by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val onToggle by rememberUpdatedState(toggle)
    AndroidView(modifier=modifier,factory={ ctx ->
        PageTurn(ctx).also { h ->
            h.surface.addView(FragmentContainerView(ctx).apply { id=R.id.navigator_container },-1,-1)
            host=h
        }
    },update={it.mode=look.turn})
    DisposableEffect(host,pub) {
        val h=host
        val fm=activity.supportFragmentManager
        if(h==null) onDispose {} else {
            val tc=pub.metadata.languages.any { it.contains("Hant",true) || it.contains("TW",true) || it.contains("HK",true) }
            val config=EpubNavigatorFragment.Configuration {
                servedAssets=listOf("fonts/.*")
                val chinese=chineseFonts.mapValues { (_,v)->if(tc) v.replace("CN.","TW.").replace("WenKai.ttf","WenKaiTC.ttf") else v }
                for ((name,file) in chinese) addFontFamilyDeclaration(FontFamily(name)) { addFontFace { addSource("fonts/"+file) } }
                for ((en,file) in englishFonts) for (zh in chinese.keys) {
                    addFontFamilyDeclaration(FontFamily(en+" / "+zh),listOf(FontFamily(zh),FontFamily(chinese.keys.first()))) {
                        addFontFace { addSource("fonts/"+file) }
                    }
                }
            }
            val factory=EpubNavigatorFactory(pub).createFragmentFactory(parseLocator(state.location),initialPreferences=look.preferences(),
                paginationListener=object:EpubNavigatorFragment.PaginationListener{
                    override fun onPageChanged(pageIndex:Int,totalPages:Int,locator:Locator){state.page=pageIndex+1;state.total=totalPages}
                },configuration=config)
            val fragment=factory.instantiate(activity.classLoader,EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
            fm.commitNow { replace(R.id.navigator_container,fragment) }
            var departure:Locator?=null
            h.advance={ next->departure=fragment.currentLocator.value;if(next)fragment.goForward(false) else fragment.goBackward(false) }
            h.restore={departure?.let { fragment.go(it,false) }}
            fragment.addInputListener(object:InputListener{
                override fun onTap(event:TapEvent):Boolean {
                    when {event.point.x < h.width*0.29 -> h.turn(false);event.point.x > h.width*0.71 -> h.turn(true);else -> onToggle() }
                    return true
                }
            })
            state.flip=h::turn
            state.jump={s->parseLocator(s)?.let{fragment.go(it,false)}}
            val entries=mutableListOf<Destination>()
            fun add(links:List<Link>){for(link in links){
                pub.locatorFromLink(link)?.let { entries+=Destination(link.title?:link.href.toString(),it.toJSON().toString()) }
                add(link.children)
            }}
            add(pub.tableOfContents.ifEmpty { pub.readingOrder })
            state.destinations=entries
            state.search={ query->
                val result=mutableListOf<Destination>()
                pub.search(query)?.let { iter -> try {
                    while(result.size<100) {
                        currentCoroutineContext().ensureActive()
                        val batch=iter.next().getOrNull()?:break
                        for(loc in batch.locators) result+=Destination(listOfNotNull(loc.text.before,loc.text.highlight,loc.text.after).joinToString(""),loc.toJSON().toString())
                    }
                } finally { iter.close() } }
                result.take(100)
            }
            nav=fragment
            onDispose { nav=null; if(fragment.isAdded && !fm.isDestroyed) fm.beginTransaction().remove(fragment).commitNowAllowingStateLoss() }
        }
    }
    LaunchedEffect(nav,look){nav?.submitPreferences(look.preferences())}
    LaunchedEffect(nav){
        nav?.currentLocator?.collectLatest { loc ->
            // No provisional drag location reaches persistent resume state.
            while(host?.pending==true) delay(24)
            state.location=loc.toJSON().toString()
            val at=pub.readingOrder.indexOfFirst { it.url()==loc.href }
            val toc=state.destinations.indexOfLast { d -> val href=parseLocator(d.location)?.href
                pub.readingOrder.indexOfFirst { it.url()==href } in 0..at }
            state.section=(toc+1).coerceAtLeast(1)
            state.progress=loc.locations.totalProgression ?: ((at.coerceAtLeast(0)+(loc.locations.progression?:0.0))/pub.readingOrder.size)
        }
    }
}
class PdfBook(val file:File):AutoCloseable {
    private val descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer=PdfRenderer(descriptor)
    val count=renderer.pageCount
    @Synchronized fun render(i:Int,w:Int,h:Int):Bitmap=renderer.openPage(i).use { page ->
        val scale=minOf(w.coerceIn(1,2200).toFloat()/page.width,h.coerceIn(1,3000).toFloat()/page.height)
        Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(android.graphics.Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
    }
    @Synchronized fun text(i:Int):String = if(Build.VERSION.SDK_INT>=35) renderer.openPage(i).use { it.textContents.joinToString("\n"){part->part.text} } else ""
    @Synchronized override fun close(){renderer.close();descriptor.close()}
}
@Composable fun PdfView(pdf:PdfBook,state:ReaderState,look:Look,modifier:Modifier,toggle:()->Unit) {
    var page by remember { mutableIntStateOf((state.location.toIntOrNull()?:0).coerceIn(0,pdf.count-1)) }
    var host by remember { mutableStateOf<PageTurn?>(null) }
    var target by remember { mutableStateOf<ImageView?>(null) }
    val onToggle by rememberUpdatedState(toggle)
    AndroidView(modifier=modifier,factory={ctx->
        PageTurn(ctx).also { h ->
            val img=ImageView(ctx);img.scaleType=ImageView.ScaleType.FIT_CENTER
            h.surface.addView(img,-1,-1)
            var origin=page
            var zoom=1f
            val scale=ScaleGestureDetector(ctx,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
                override fun onScale(d:ScaleGestureDetector):Boolean {zoom=(zoom*d.scaleFactor).coerceIn(1f,4f);img.scaleX=zoom;img.scaleY=zoom
                    if(zoom==1f){img.translationX=0f;img.translationY=0f};return true}
            })
            val tap=GestureDetector(ctx,object:GestureDetector.SimpleOnGestureListener(){
                override fun onDown(e:MotionEvent)=true
                override fun onSingleTapConfirmed(e:MotionEvent):Boolean{
                    if(zoom>1 || e.x in h.width*.29..h.width*.71) onToggle() else h.turn(e.x>h.width/2);return true
                }
                override fun onScroll(e1:MotionEvent?,e2:MotionEvent,dx:Float,dy:Float):Boolean{
                    if(zoom<=1f)return false
                    img.translationX=(img.translationX-dx).coerceIn(-img.width*(zoom-1)/2,img.width*(zoom-1)/2)
                    img.translationY=(img.translationY-dy).coerceIn(-img.height*(zoom-1)/2,img.height*(zoom-1)/2);return true
                }
            })
            img.setOnTouchListener { _,e->scale.onTouchEvent(e);tap.onTouchEvent(e);true }
            h.allowTurn={zoom==1f}
            h.advance={next->origin=page;val dest=(page+if(next)1 else -1).coerceIn(0,pdf.count-1);val moved=dest!=page;page=dest;moved}
            h.restore={page=origin}; h.settled={state.location=page.toString()}
            state.flip=h::turn;state.jump={s->s.toIntOrNull()?.let { page=it.coerceIn(0,pdf.count-1) }}
            host=h;target=img
        }
    },update={it.mode=look.turn})
    LaunchedEffect(pdf){state.destinations=(0 until pdf.count).map{Destination((it+1).toString(),it.toString())}
        state.search={q->withContext(Dispatchers.IO){ buildList { for(i in 0 until pdf.count){ensureActive();val t=pdf.text(i);val j=t.indexOf(q,ignoreCase=true)
            if(j>=0)add(Destination(t.substring((j-25).coerceAtLeast(0),(j+q.length+25).coerceAtMost(t.length)),i.toString()));if(size>=100)break} } }}}
    LaunchedEffect(target,page){
        val view=target?:return@LaunchedEffect
        while(view.width==0 || view.height==0) delay(16)
        val bitmap=withContext(Dispatchers.IO){pdf.render(page,view.width,view.height)}
        view.setImageBitmap(bitmap);state.page=page+1;state.total=pdf.count;state.progress=(page+1.0)/pdf.count
        if(host?.pending!=true)state.location=page.toString()
    }
}
