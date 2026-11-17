package org.sam.openreader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class TextPipelineTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun fullLineRulesRejectBodySentences() {
        val rule=HeadingRule()
        for (s in listOf("第１２節：歸途","第十二章 山雨","【第壹拾貳回】","Chapter XII — The harbour","Part Three","楔子"))
            assertNotNull(s,rule.reason(s,4))
        for (s in listOf("我在第十二章读到这句话。","第三天他读完了这一章","第十二章讲的是回家","Chapter 2 was difficult.","Chapter IC","***","12"))
            assertNull(s,rule.reason(s,4))
        assertNotNull(rule.copy(loose=true).reason("第十二章讲的是回家",4))
        assertNull(rule.copy(excluded=setOf(4)).reason("第十二章",4))
        assertNotNull(rule.copy(bare=true).reason("（三）",4))
    }

    @Test fun strictDecodeNormalizesOnlyLineEndings() = runBlocking<Unit> {
        for(cs in TextFiles.encodings){
            val charset=charset(cs)
            val input=if(cs=="windows-1252")"café\r\na\rb" else "書\r\na\rb"
            val f=temp.newFile();f.writeBytes(input.toByteArray(charset))
            val out=temp.newFile();TextFiles.decode(f,out,charset)
            assertEquals(input.replace("\r\n","\n").replace('\r','\n'),out.readText())
        }
        val invalid=temp.newFile().apply{writeBytes(byteArrayOf(0xc3.toByte(),0x28))}
        assertThrows(java.nio.charset.CharacterCodingException::class.java){runBlocking{TextFiles.decode(invalid,temp.newFile(),Charsets.UTF_8)}}
    }

    @Test fun generatedEpubIsIndexedAndEscaped()=runBlocking{
        val folder=temp.newFolder()
        val original="Opening 🌊\n第十二章 山雨\n<hello> & 🙂\n"+"海".repeat(100000)+"\nChapter 2\nEnd"
        File(folder,"text.utf8").writeText(original)
        TextFiles.publish(folder,"A & B",HeadingRule())
        val result=File(folder,"reader.epub")
        ZipFile(result).use { z->
            val first=z.entries().nextElement()
            assertEquals("mimetype",first.name);assertEquals(0,first.method)
            fun text(name:String)=z.getInputStream(z.getEntry(name)).bufferedReader().use{it.readText()}
            val nav=text("nav.xhtml")
            assertTrue(nav.contains("第十二章 山雨"));assertTrue(nav.contains("Chapter 2"))
            assertTrue(text("r1.xhtml").contains("&lt;hello&gt; &amp; 🙂"))
            assertTrue(z.size()>7)
        }
        assertEquals(original,File(folder,"text.utf8").readText())
        assertEquals(original.length.toString(),File(folder,"text.length").readText())
        assertEquals(6L*16,File(folder,"lines.idx").length())
    }

    @Test fun externalProfileRoundTripsAndMatchesFullLine(){
        val rule=HeadingRule(prefix="Scene ",markers=":",contains="海",advanced="Scene [0-9]+",excluded=setOf(31),included=setOf(32))
        val file=temp.newFile();rule.save(file)
        assertEquals(rule,HeadingRule.load(file))
    }
    @Test fun catalogHas36OfflineCombinations(){
        assertEquals(9,families.size);assertEquals(8,englishFonts.size);assertEquals(8,chineseFonts.size)
        assertEquals("CMUSerif.ttf",englishFonts["CMU Serif"])
        for(f in families.keys)for(v in 0..3){
            val c=preset(f,v);assertEquals(14,c.size)
            fun luminance(hex:String):Double {
                val linear=hex.drop(1).chunked(2).map{val x=it.toInt(16)/255.0;if(x<=.04045)x/12.92 else Math.pow((x+.055)/1.055,2.4)}
                return .2126*linear[0]+.7152*linear[1]+.0722*linear[2]
            }
            for((a,b) in listOf(0 to 1,0 to 2,0 to 3,4 to 5,6 to 7,10 to 11,12 to 13)){
                val one=luminance(c[a]);val two=luminance(c[b]);assertTrue(f+" "+v,(maxOf(one,two)+.05)/(minOf(one,two)+.05)>=if(v==0)7.0 else 4.5)
            }
        }
    }
}
