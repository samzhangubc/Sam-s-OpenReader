package org.sam.openreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable fun ListenPanel(ctx:MainActivity,b:Book,state:ReaderState,close:()->Unit){
    val session by SpeechService.status.collectAsState();val live=session.book==b.id
    val scope=rememberCoroutineScope()
    val prefs by ctx.settings.data.collectAsState(emptyPreferences())
    val voiceKey=stringPreferencesKey("voice");val rateKey=stringPreferencesKey("rate")
    var voices by remember{mutableStateOf(emptyList<android.speech.tts.Voice>())}
    var chosen by remember(prefs[voiceKey]){mutableStateOf(prefs[voiceKey].orEmpty())}
    var rate by remember(prefs[rateKey]){mutableFloatStateOf(prefs[rateKey]?.toFloatOrNull()?:1f)}
    var minutes by remember{mutableIntStateOf(0)}
    var custom by remember{mutableStateOf("")};var sample by remember{mutableStateOf<TextToSpeech?>(null)}
    var available by remember{mutableStateOf(samsungDefault(ctx))}
    val settings=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){available=samsungDefault(ctx)}
    val notify=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    DisposableEffect(available){
        var t:TextToSpeech?=null;var alive=true
        if(available)t=TextToSpeech(ctx,{result->ctx.runOnUiThread {
            if(alive && result==TextToSpeech.SUCCESS && samsungDefault(ctx)){
                val offline=t?.voices.orEmpty().filter{!it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()}
                    .sortedBy{it.locale.toLanguageTag()+" "+it.name}
                voices=offline;if(offline.none{it.name==chosen})chosen=(offline.firstOrNull{it.locale.language==java.util.Locale.getDefault().language}?:offline.firstOrNull())?.name.orEmpty()
                sample=t
            }
        }},SAMSUNG)
        onDispose {alive=false;t?.stop();t?.shutdown()}
    }
    fun command(action:String){
        val i=Intent(ctx,SpeechService::class.java).setAction(action).putExtra("id",b.id).putExtra("rate",rate).putExtra("voice",chosen)
            .putExtra("minutes",minutes).putExtra("location",if(b.spoken.isNotBlank())b.spoken else state.location)
        if(action=="open"){
            if(Build.VERSION.SDK_INT>=33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            ContextCompat.startForegroundService(ctx,i)
        }else if(live)ctx.startService(i)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)){
        Text(stringResource(R.string.listen),style=MaterialTheme.typography.headlineSmall)
        if(voices.isEmpty() || session.problem!=0)Text(stringResource(if(session.problem!=0)session.problem else R.string.voice_error))
        Action(R.string.voice_settings){runCatching{settings.launch(Intent("com.android.settings.TTS_SETTINGS"))}.onFailure{settings.launch(Intent(android.provider.Settings.ACTION_SETTINGS))}}
        if(voices.isNotEmpty()){
            val names=voices.map{it.locale.displayName+" · "+it.name}
            Choice(stringResource(R.string.voice),names.getOrElse(voices.indexOfFirst{it.name==chosen}){chosen},names){
                chosen=voices[it].name;scope.launch{ctx.settings.edit{p->p[voiceKey]=chosen}}}
            Action(R.string.voice_sample){if(!live){val v=voices.firstOrNull{it.name==chosen};if(v!=null){sample?.voice=v;sample?.setSpeechRate(rate)
                sample?.speak(if(v.locale.language=="zh")"山雨欲來，書頁輕翻。" else "A quiet harbour at the end of the chapter.",TextToSpeech.QUEUE_FLUSH,null,"sample")}}}
        }
        Row(horizontalArrangement=Arrangement.SpaceEvenly,modifier=Modifier.fillMaxWidth()){
            TextButton(onClick={command("previous")},enabled=live){Text("←")}
            Button(onClick={if(live && session.playing)command("pause") else command("open")},enabled=voices.isNotEmpty()){
                Text(stringResource(if(live && session.playing)R.string.pause else R.string.play))}
            TextButton(onClick={command("next")},enabled=live){Text("→")}
            TextButton(onClick={command("quit");close()},enabled=live){Text("■")}
        }
        Text(stringResource(R.string.speed)+" · "+rate+"×")
        Slider(rate,{rate=(it*10).roundToInt()/10f},valueRange=.5f..3f,steps=24,onValueChangeFinished={
            command("rate");scope.launch{ctx.settings.edit{it[rateKey]=rate.toString()}}
        })
        val options=listOf(0,5,10,15,30,60,-1)
        val labels=options.map{if(it<0)stringResource(R.string.chapter_end) else if(it==0)stringResource(R.string.none) else it.toString()}
        Choice(stringResource(R.string.timer),labels.getOrElse(options.indexOf(minutes)){minutes.toString()},labels){minutes=options[it];command("timer")}
        Row{OutlinedTextField(custom,{custom=it.filter(Char::isDigit).take(3)},Modifier.weight(1f),label={Text("1–180 min")});Action(R.string.apply){
            custom.toIntOrNull()?.takeIf{it in 1..180}?.let{minutes=it;command("timer")}}}
        if(live && session.deadline>0){var left by remember{mutableLongStateOf(0L)}
            LaunchedEffect(session.deadline){while(true){left=(session.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0)/1000;if(left==0L)break;delay(1000)}}
            Text("%d:%02d".format(left/60,left%60))
        }
    }
}
