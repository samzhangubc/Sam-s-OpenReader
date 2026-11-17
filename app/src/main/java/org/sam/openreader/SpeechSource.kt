package org.sam.openreader

import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content
import java.io.File
import java.io.RandomAccessFile
import java.text.BreakIterator
import java.util.Locale

data class Passage(val text:String,val location:String,val chapter:String)
interface SpeechSource:AutoCloseable {
    suspend fun step(direction:Int,paragraph:Boolean=false):Passage?
}
private class TxtSpeech(folder:File,location:String):SpeechSource {
    private val lines=RandomAccessFile(File(folder,"lines.idx"),"r")
    private val data=RandomAccessFile(File(folder,"text.utf8"),"r")
    private val resources=RandomAccessFile(File(folder,"resources.idx"),"r")
    private val last=lines.length()/16-1
    private var line=if(location.startsWith("t:"))location.split(':').getOrNull(1)?.toLongOrNull()?:0
        else floorIndex(lines,16,textOffset(folder,location))
    private var within=if(location.startsWith("t:"))location.substringAfterLast(':').toLongOrNull()?:0 else 0
    private var consumed=0L
    private fun byteAt(index:Long):Long {
        if(index>last)return data.length()
        lines.seek(index*16+8);return lines.readLong()
    }
    private fun blank(i:Long):Boolean {
        if(i !in 0..last)return true
        val len=byteAt(i+1)-byteAt(i)
        if(len>64)return false
        data.seek(byteAt(i));val a=ByteArray(len.toInt());data.readFully(a);return String(a,Charsets.UTF_8).isBlank()
    }
    override suspend fun step(direction:Int,paragraph:Boolean):Passage? {
        if(paragraph){
            val saved=line;val savedWithin=within
            if(direction>0){while(line<=last && !blank(line))line++;while(line<=last && blank(line))line++}
            else {while(line>0 && !blank(line-1))line--;line--;while(line>=0 && blank(line))line--
                while(line>0 && !blank(line-1))line--}
            if(line !in 0..last){line=saved;within=savedWithin;return null};within=0
        }else if(direction>0)within+=consumed
        while(line in 0..last){
            val begin=byteAt(line)+within;val end=byteAt(line+1)
            if(begin>=end){line++;within=0;continue}
            data.seek(begin)
            val bytes=ByteArray(minOf(4096L,end-begin).toInt())
            data.readFully(bytes)
            var n=bytes.size
            if(begin+n<end){val next=data.read();if(next and 192==128){
                while(n>0 && bytes[n-1].toInt() and 192==128)n--
                if(n>0)n--
            }}
            val text=String(bytes,0,n,Charsets.UTF_8)
            val part=sentence(text)
            consumed=part.toByteArray().size.toLong()
            if(part.isBlank()){within+=consumed;continue}
            lines.seek(line*16);val offset=lines.readLong()
            val idx=floorIndex(resources,20,offset);resources.seek(idx*20+16)
            return Passage(part,"t:"+line+":"+within,resources.readInt().toString())
        }
        return null
    }
    override fun close(){lines.close();data.close();resources.close()}
}
private fun sentence(text:String):String {
    if(text.isEmpty())return text
    val bounded=text.take(1800)
    val sentences=BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(bounded) }
    val first=sentences.next()
    var end=if(first==BreakIterator.DONE)bounded.length else first
    if(end==bounded.length && text.length>end){
        val graphemes=BreakIterator.getCharacterInstance(Locale.ROOT).apply{setText(text.take(end+100))}
        if(!graphemes.isBoundary(end))end=graphemes.preceding(end)
    }
    return text.substring(0,end.coerceAtLeast(1))
}
private class EpubSpeech(val pub:Publication,loc:String):SpeechSource{
    private val parsed=parseLocator(loc)
    private var iter=pub.content(parsed)?.iterator()
    private var current:Content.TextualElement?=null
    private var offset=runCatching{org.json.JSONObject(loc).optInt("speechOffset")}.getOrDefault(0)
    private var used=0
    override suspend fun step(direction:Int,paragraph:Boolean):Passage?{
        if(!paragraph && direction>0)offset+=used
        if(paragraph || current==null || offset>=(current?.text?.length?:0)){
            val backwards=direction<0
            val it=if(backwards && current!=null)pub.content(current!!.locator)?.iterator() else iter
            while(true){
                val el=(if(backwards)it?.previousOrNull() else it?.nextOrNull())?:return null
                if(el is Content.TextualElement && !el.text.isNullOrBlank()){
                    if(current!=null || paragraph)offset=0
                    current=el
                    if(backwards){iter=pub.content(el.locator)?.iterator();iter?.nextOrNull()}
                    break
                }
            }
        }
        val el=current?:return null
        val text=el.text.orEmpty()
        offset=offset.coerceIn(0,(text.length-1).coerceAtLeast(0))
        val part=sentence(text.substring(offset,minOf(text.length,offset+2048)))
        used=part.length
        return Passage(part,el.locator.toJSON().put("speechOffset",offset).toString(),el.locator.href.toString())
    }
    override fun close(){pub.close()}
}
private class PdfSpeech(val pdf:PdfBook,location:String):SpeechSource{
    private var page=location.substringBefore(':').toIntOrNull()?.coerceIn(0,pdf.count-1)?:0
    private var offset=location.substringAfter(':',"0").toIntOrNull()?:0
    private var used=0
    private var cachedPage=-1
    private var text=""
    override suspend fun step(direction:Int,paragraph:Boolean):Passage?{
        if(paragraph){if(page+direction !in 0 until pdf.count)return null;page+=direction;offset=0}
        else if(direction>0)offset+=used
        while(page<pdf.count){
            if(cachedPage!=page){text=pdf.text(page);cachedPage=page}
            if(offset<text.length){
                val part=sentence(text.substring(offset,minOf(offset+2048,text.length)));used=part.length
                return Passage(part,page.toString()+":"+offset,page.toString())
            }
            page++;offset=0
        }
        return null
    }
    override fun close(){pdf.close()}
}
suspend fun Library.speechSource(b:Book,location:String):SpeechSource=when(b.format){
    "txt"->TxtSpeech(revision(b),location)
    "epub"->EpubSpeech(publication(original(b)),location)
    else->PdfSpeech(PdfBook(original(b)),location)
}
