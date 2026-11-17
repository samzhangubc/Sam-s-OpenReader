package org.sam.openreader

import android.graphics.Color.parseColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.json.JSONObject

val englishFonts = linkedMapOf("Source Serif 4" to "SourceSerif4.ttf","CMU Serif" to "CMUSerif.ttf","Noto Serif" to "NotoSerif.ttf",
    "EB Garamond" to "EBGaramond.ttf","Libre Baskerville" to "LibreBaskerville.ttf","Roboto" to "Roboto.ttf",
    "Source Sans 3" to "SourceSans3.ttf","IBM Plex Mono" to "IBMPlexMono.ttf")
val chineseFonts = linkedMapOf("思源宋体 · Source Han Serif" to "SourceHanSerifCN.otf","思源黑体 · Source Han Sans" to "SourceHanSansCN.otf",
    "霞鹜文楷 · LXGW WenKai" to "WenKai.ttf","源流明體 · GenRyuMin" to "GenRyuMin.otf","源樣明體 · GenYoMin" to "GenYoMin.otf",
    "粉圓 · Huninn" to "Huninn.ttf","芫荽 · Iansui" to "Iansui.ttf","得意黑 · Smiley Sans" to "SmileySans.ttf")
val families = linkedMapOf(
    "Claude" to "FFCBAC F0AB88 914526 F5ECE6 78574B","ChatGPT" to "A2F5DC 80D7B8 14634F EAF2EE 46665D",
    "GitHub" to "B6DCFF 88BAF5 215DA3 ECF0F5 4E637D","Visual Studio" to "E1C6FF C5A5E8 70449A F1ECF5 695578",
    "Adobe Photoshop" to "A6EBFF 79CCEF 116384 E8F1F4 476873","Overleaf" to "C0F4A6 99CF88 38682B EFF2E5 536748",
    "Spotify" to "A5F8B8 70DA92 176731 E7F1E8 49694F","Discord" to "D5D4FF B0B3FF 504CA4 EEEDF6 605E83",
    "Paper" to "F6E5C6 D9C7A6 665233 F5EDDC 70604B")
val roles = listOf(R.string.paper,R.string.ink,R.string.footer,R.string.link,R.string.menu_bg,R.string.menu_ink,R.string.primary,
    R.string.on_primary,R.string.focus,R.string.divider,R.string.selection,R.string.on_selection,R.string.highlight,R.string.on_highlight)
fun preset(family: String,variant: Int): List<String> {
    val row=families.getValue(family).split(" ")
    val bg=listOf("0B0D10","202327","FFFEFA",row[3])[variant]
    val ink=listOf("FAFAFA","E5E7E9","202124","343837")[variant]
    val accent=row[if(variant==3) 4 else variant]
    return listOf(bg,ink,ink,accent,bg,ink,accent,bg,accent,accent,accent,bg,ink,bg).map { "#"+it }
}
data class Look(val family: String="Paper", val variant: Int=2, val colors: List<String> = preset("Paper",2),
                val english: String=englishFonts.keys.first(), val chinese: String=chineseFonts.keys.first(),
                val size: Int=100, val turn: Int=0, val publisher: Boolean=false, val percent: Boolean=false,
                val styleId: String="") {
    fun json() = JSONObject().put("family",family).put("variant",variant).put("colors",colors.joinToString(" "))
        .put("en",english).put("zh",chinese).put("size",size).put("turn",turn).put("publisher",publisher)
        .put("percent",percent).put("style",styleId).toString()
    fun color(i:Int)=Color(parseColor(colors[i]))
    companion object {
        fun read(s:String): Look = runCatching {
            val j=JSONObject(s); val f=j.getString("family"); val v=j.getInt("variant")
            val c=j.getString("colors").split(" ")
            require(f in families && v in 0..3 && c.size==roles.size && c.all { it.matches(Regex("#[0-9a-fA-F]{6}")) })
            Look(f,v,c,j.getString("en").takeIf { it in englishFonts } ?: englishFonts.keys.first(),
                j.getString("zh").takeIf { it in chineseFonts } ?: chineseFonts.keys.first(),
                j.optInt("size",100).coerceIn(60,240),j.optInt("turn").coerceIn(0,3),j.optBoolean("publisher"),
                j.optBoolean("percent"),j.optString("style"))
        }.getOrDefault(Look())
    }
}
@Composable fun Choice(label: String, value: String, choices: List<String>, change:(Int)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true}) { Text(label+" · "+value) }
        DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=360.dp)) {
            choices.forEachIndexed { i,s -> DropdownMenuItem(text={Text(s)},onClick={expanded=false;change(i)}) }
        }
    }
}
@Composable fun Action(id:Int, click:()->Unit) { TextButton(onClick=click) { Text(stringResource(id)) } }
@Composable fun Toggle(id:Int, value:Boolean, change:(Boolean)->Unit) {
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Switch(value,change); Spacer(Modifier.width(8.dp)); Text(stringResource(id))
    }
}

@Composable fun Appearance(initial:Look, book:Boolean, names:Map<String,String>, fixed:Boolean,
                          save:(Look,Boolean)->Unit, inherit:()->Unit, saveNamed:(String,Look)->Unit,
                          deleteNamed:(String)->Unit) {
    var look by remember { mutableStateOf(initial) }
    var local by remember { mutableStateOf(book) }
    var role by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var hex by remember(look.colors,role) { mutableStateOf(look.colors[role]) }
    val variants=listOf(R.string.high_dark,R.string.dark,R.string.light,R.string.wash).map { stringResource(it) }
    val turns=listOf(R.string.slide,R.string.cover,R.string.uncover,R.string.none).map { stringResource(it) }
    val labels=roles.map { stringResource(it) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.appearance),style=MaterialTheme.typography.headlineSmall)
        Column(Modifier.fillMaxWidth().background(look.color(0)).padding(12.dp)) {
            Text("山雨欲來 · A quiet harbour",color=look.color(1))
            Text("1 / 12",color=look.color(2))
        }
        Choice(stringResource(R.string.family),look.family,families.keys.toList()) { i ->
            val f=families.keys.elementAt(i);look=look.copy(family=f,colors=preset(f,look.variant),styleId="") }
        Choice(stringResource(R.string.variant),variants[look.variant],variants){look=look.copy(variant=it,colors=preset(look.family,it),styleId="")}
        Choice(stringResource(R.string.colors),labels[role],labels){role=it}
        OutlinedTextField(hex,{s->hex=s;if(s.matches(Regex("#[a-fA-F0-9]{6}"))) look=look.copy(colors=look.colors.toMutableList().also { it[role]=s },styleId="")},
            label={Text(labels[role]+" #RRGGBB")},singleLine=true)
        if(names.isNotEmpty()) Choice(stringResource(R.string.personal),look.styleId,names.keys.toList()) { i ->
            val n=names.keys.elementAt(i);val s=Look.read(names.getValue(n));look=look.copy(family=s.family,variant=s.variant,colors=s.colors,styleId=n);name=n }
        OutlinedTextField(name,{name=it},label={Text(stringResource(R.string.style_name))},singleLine=true)
        Row { Action(R.string.save_style) { if(name.isNotBlank()){look=look.copy(styleId=name.trim());saveNamed(name.trim(),look)} }
            if(name in names) Action(R.string.delete_style){deleteNamed(name);look=look.copy(styleId="");name=""} }
        if(fixed) Text(stringResource(R.string.fixed_layout)) else {
            Choice(stringResource(R.string.english_font),look.english,englishFonts.keys.toList()){look=look.copy(english=englishFonts.keys.elementAt(it))}
            Choice(stringResource(R.string.chinese_font),look.chinese,chineseFonts.keys.toList()){look=look.copy(chinese=chineseFonts.keys.elementAt(it))}
            Text(stringResource(R.string.font_size)+" · "+look.size+"%")
            Slider(look.size.toFloat(),{look=look.copy(size=it.toInt())},valueRange=60f..240f)
            Toggle(R.string.publisher,look.publisher){look=look.copy(publisher=it)}
        }
        Choice(stringResource(R.string.animation),turns[look.turn],turns){look=look.copy(turn=it)}
        Toggle(R.string.show_percent,look.percent){look=look.copy(percent=it)}
        if(book){Toggle(R.string.this_book,local){local=it};Action(R.string.inherit,inherit)}
        Button(onClick={save(look,local)}){Text(stringResource(R.string.apply))}
    }
}
