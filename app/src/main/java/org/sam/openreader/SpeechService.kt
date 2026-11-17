package org.sam.openreader

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

const val SAMSUNG="com.samsung.SMT"
fun samsungDefault(ctx:Context)=Settings.Secure.getString(ctx.contentResolver,Settings.Secure.TTS_DEFAULT_SYNTH)==SAMSUNG
data class Listening(val book:String="",val playing:Boolean=false,val deadline:Long=0,val problem:Int=0,val locator:String="")
class SpeechService:Service(){
    companion object {
        val status=MutableStateFlow(Listening())
        fun quit(ctx:Context,id:String){if(status.value.book==id)ctx.stopService(Intent(ctx,SpeechService::class.java))}
    }
    private val lib get()=(application as ReaderApp).library
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val io=Dispatchers.IO.limitedParallelism(1)
    private val handler=Handler(Looper.getMainLooper())
    private var engine:TextToSpeech?=null
    private var source:SpeechSource?=null
    private var b:Book?=null
    private var reading:Passage?=null
    private var load:Job?=null
    private var generation=0
    private var playing=false
    private var ready=false
    private var activeId=""
    private var rate=1f
    private var voice=""
    private var startLoc=""
    private var until=0L
    private var stopChapter=false
    private var firstChapter:String?=null
    private var lastSave=0L
    private var error=0
    private lateinit var audio:AudioManager
    private lateinit var focus:AudioFocusRequest
    private lateinit var media:MediaSession
    private val deadline=Runnable{end()}
    private val release=Runnable{if(!playing){engine?.shutdown();engine=null;ready=false}}
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){pause()}}
    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("speech",getString(R.string.speech_channel),NotificationManager.IMPORTANCE_LOW))
        audio=getSystemService(AudioManager::class.java)
        focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({if(it<0)pause()},handler).build()
        ContextCompat.registerReceiver(this,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        media=MediaSession(this,"OpenReader").apply {
            setCallback(object:MediaSession.Callback(){
                override fun onPause()=pause()
                override fun onPlay()=play()
                override fun onStop()=end()
                override fun onSkipToNext()=seek(1)
                override fun onSkipToPrevious()=seek(-1)
            });isActive=true
        }
    }
    override fun onBind(intent:Intent?)=null
    private fun intent(action:String)=PendingIntent.getService(this,action.hashCode(),Intent(this,SpeechService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification():Notification {
        val builder=Notification.Builder(this,"speech").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(b?.title?:getString(R.string.listen))
            .setOnlyAlertOnce(true).setOngoing(playing)
            .setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0,1,2))
        fun add(icon:Int,label:Int,action:String){builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,icon),getString(label),intent(action)).build())}
        add(android.R.drawable.ic_media_previous,R.string.prev_para,"previous")
        if(playing)add(android.R.drawable.ic_media_pause,R.string.pause,"pause") else add(android.R.drawable.ic_media_play,R.string.play,"play")
        add(android.R.drawable.ic_media_next,R.string.next_para,"next")
        add(android.R.drawable.ic_delete,R.string.quit,"quit")
        return builder.build()
    }
    private fun update(){
        status.value=Listening(activeId,playing,until,error,reading?.location.orEmpty())
        media.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if(playing)PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,PlaybackState.PLAYBACK_POSITION_UNKNOWN,rate).build())
        getSystemService(NotificationManager::class.java).notify(31,notification())
    }
    override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
        when(i?.action){
            "open"->{
                startForeground(31,notification())
                val id=i.getStringExtra("id")?:return START_NOT_STICKY
                if(activeId!=id || source==null){
                    invalidate();checkpoint();disposeSource();activeId=id;b=null;reading=null;error=0
                    startLoc=i.getStringExtra("location").orEmpty()
                    val gen=generation
                    load=scope.launch{try{
                        val book=lib.shelf.book(id)?:return@launch end()
                        val src=withContext(io){lib.speechSource(book,startLoc.ifBlank{book.spoken.ifBlank{book.visual}})}
                        if(gen!=generation){withContext(io){src.close()};return@launch}
                        b=book;source=src;play()
                    }catch(e:Exception){if(e !is CancellationException)fail(R.string.unavailable)}}
                }else play()
                rate=i.getFloatExtra("rate",1f);voice=i.getStringExtra("voice").orEmpty()
                timer(i.getIntExtra("minutes",0));update()
            }
            "play"->play()
            "pause"->pause()
            "previous"->seek(-1)
            "next"->seek(1)
            "quit"->end()
            "rate"->{rate=i.getFloatExtra("rate",1f);engine?.setSpeechRate(rate);if(playing){invalidate();speakCurrent()}}
            "timer"->{timer(i.getIntExtra("minutes",0));update()}
        }
        return START_NOT_STICKY
    }
    private fun timer(minutes:Int){
        handler.removeCallbacks(deadline);stopChapter=minutes<0;firstChapter=reading?.chapter
        val ms=minutes.coerceIn(0,180)*60000L
        until=if(ms==0L)0L else SystemClock.elapsedRealtime()+ms
        if(ms>0)handler.postDelayed(deadline,ms)
    }
    private fun initialise(){
        if(!samsungDefault(this)){fail(R.string.voice_error);return}
        engine=TextToSpeech(this,{code->handler.post{
            val t=engine?:return@post
            if(code!=TextToSpeech.SUCCESS || !samsungDefault(this)){fail(R.string.voice_error);return@post}
            val chosen=t.voices.orEmpty().firstOrNull{it.name==voice && !it.isNetworkConnectionRequired}
            if(chosen==null || t.setVoice(chosen)!=TextToSpeech.SUCCESS){fail(R.string.voice_error);return@post}
            t.setSpeechRate(rate);ready=true
            t.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String){}
                override fun onDone(id:String){handler.post{if(playing && id==generation.toString())fetch(1,false)}}
                @Deprecated("Platform callback") override fun onError(id:String){handler.post{if(id==generation.toString())fail(R.string.voice_error)}}
            })
            if(playing)speakCurrent()
        }},SAMSUNG)
    }
    private fun play(){
        if(activeId.isEmpty() || source==null)return
        if(until!=0L && SystemClock.elapsedRealtime()>=until){end();return}
        handler.removeCallbacks(release)
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){pause();return}
        playing=true;error=0
        if(engine==null)initialise() else if(ready)speakCurrent()
        update()
    }
    private fun invalidate(){generation++;load?.cancel();engine?.stop()}
    private fun pause(){invalidate();playing=false;audio.abandonAudioFocusRequest(focus);checkpoint()
        handler.removeCallbacks(release);handler.postDelayed(release,60000);update()}
    private fun end(){playing=false;invalidate();checkpoint();stopSelf()}
    private fun fail(res:Int){error=res;end()}
    private fun seek(dir:Int){if(source==null)return;invalidate();fetch(dir,true)}
    private fun fetch(dir:Int,para:Boolean){
        val src=source?:return;val gen=++generation
        load=scope.launch {
            try{
                val part=withContext(io){src.step(dir,para)}
                if(gen!=generation)return@launch
                if(part==null){if(para){if(playing)speakCurrent()}else if(reading==null)fail(R.string.unavailable) else end();return@launch}
                if(stopChapter && firstChapter!=null && firstChapter!=part.chapter){end();return@launch}
                if(firstChapter==null)firstChapter=part.chapter
                reading=part
                if(SystemClock.elapsedRealtime()-lastSave>10000 || para)checkpoint()
                if(playing)speakCurrent() else update()
            }catch(e:Exception){if(e !is CancellationException)fail(R.string.unavailable)}
        }
    }
    private fun speakCurrent(){
        if(!ready || !playing)return
        if(until!=0L && SystemClock.elapsedRealtime()>=until){end();return}
        val p=reading?:return fetch(0,false)
        if(engine?.speak(p.text,TextToSpeech.QUEUE_FLUSH,null,(++generation).toString())!=TextToSpeech.SUCCESS)fail(R.string.voice_error)
        else status.value=status.value.copy(locator=p.location)
    }
    private fun checkpoint(){
        val book=b?:return;val loc=reading?.location?:return
        lastSave=SystemClock.elapsedRealtime()
        (application as ReaderApp).scope.launch{lib.shelf.speech(book.id,book.revision,loc)}
    }
    private fun disposeSource(){val src=source;source=null;if(src!=null)(application as ReaderApp).scope.launch(io){src.close()}}
    override fun onDestroy(){
        checkpoint();handler.removeCallbacksAndMessages(null);playing=false;generation++;scope.cancel()
        engine?.stop();engine?.shutdown();engine=null;disposeSource();unregisterReceiver(noisy)
        audio.abandonAudioFocusRequest(focus);media.release();stopForeground(STOP_FOREGROUND_REMOVE)
        status.value=Listening(problem=error);super.onDestroy()
    }
}
