package org.sam.openreader;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.speech.tts.*;
import java.io.*;
import java.util.*;

/** Screen-independent paragraph playback using the installed Samsung engine. */
public class SpeechService extends Service {
    private TextToSpeech engine;
    private RandomAccessFile input;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long paragraphStart, paragraphEnd;
    private boolean playing, ready;
    private int generation;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final Runnable deadline = () -> stopSelf();

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("speech", "Reading aloud", NotificationManager.IMPORTANCE_LOW));
        startForeground(7, notification());
        getSharedPreferences("speech",0).edit().putBoolean("session",true).apply();
        audio=getSystemService(AudioManager.class);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {if(change<0) pause();},handler).build();
        engine = new TextToSpeech(this, status -> {
            ready=status==TextToSpeech.SUCCESS;
            if(!ready) { stopSelf(); return; }
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) {}
                public void onError(String id) {handler.post(() -> pause());}
                public void onDone(String id) {
                    handler.post(() -> {
                        if(playing && id.equals(Integer.toString(generation))) speak();
                    });
                }
            });
            if(playing) speak();
        }, "com.samsung.SMT");
    }
    private Notification notification() {
        Intent activity=new Intent(this,MainActivity.class);
        Notification.Builder b=new Notification.Builder(this,"speech")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("OpenReader · Samsung TTS")
            .setContentText("Read aloud")
            .setContentIntent(PendingIntent.getActivity(this,0,activity,PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true);
        for(String action:new String[]{"pause","quit"}) {
            b.addAction(new Notification.Action.Builder(null,action,
                PendingIntent.getService(this,action.hashCode(),new Intent(this,SpeechService.class).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return b.build();
    }
    @Override public int onStartCommand(Intent command,int flags,int startId) {
        if(command==null) {stopSelf();return START_NOT_STICKY;}
        String action=command.getAction();
        try {
            if("quit".equals(action)) {stopSelf();return START_NOT_STICKY;}
            if("timer".equals(action)) {handler.removeCallbacks(deadline);handler.postDelayed(deadline,15*60*1000L);return START_NOT_STICKY;}
            if("pause".equals(action)) {pause();return START_NOT_STICKY;}
            if("play".equals(action)) {
                if(input!=null) input.close();
                input=new RandomAccessFile(new File(getFilesDir(),"speech.txt"),"r");
                paragraphStart=paragraphEnd=0;
            }
            if(input==null) {stopSelf();return START_NOT_STICKY;}
            if("prev".equals(action)) {
                long position=Math.max(0,paragraphStart-2);
                while(position>0) {input.seek(position);if(input.read()==10) {position++;break;}position--;}
                paragraphEnd=position;
            }
            if("next".equals(action)||"prev".equals(action)) {
                engine.stop(); generation++;
                if(!playing) return START_NOT_STICKY;
            } else if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return START_NOT_STICKY;
            playing=true;
            if(ready) speak();
        } catch(Exception ex) {stopSelf();}
        return START_NOT_STICKY;
    }
    private void speak() {
        try {
            input.seek(paragraphEnd);
            paragraphStart=paragraphEnd;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            int value;
            while(bytes.size()<3000 && (value=input.read())!=-1) {
                bytes.write(value);
                if(value==10 && bytes.size()>1) break;
            }
            // Finish a UTF-8 sequence when a bounded chunk ends inside one.
            while(input.getFilePointer()<input.length()) {
                long at=input.getFilePointer();int next=input.read();
                if((next&0xC0)!=0x80) {input.seek(at);break;}bytes.write(next);
            }
            paragraphEnd=input.getFilePointer();
            if(bytes.size()==0) {stopSelf();return;}
            String text=bytes.toString("UTF-8");
            engine.setSpeechRate(getSharedPreferences("speech",0).getFloat("rate",1f));
            int status=engine.speak(text,TextToSpeech.QUEUE_FLUSH,null,Integer.toString(++generation));
            if(status==TextToSpeech.ERROR) pause();
        } catch(Exception ex) {stopSelf();}
    }
    private void pause() {
        playing=false;generation++;
        if(engine!=null) engine.stop();
        paragraphEnd=paragraphStart;
        audio.abandonAudioFocusRequest(focus);
    }
    @Override public void onDestroy() {
        playing=false;handler.removeCallbacksAndMessages(null);
        getSharedPreferences("speech",0).edit().putBoolean("session",false).apply();
        if(engine!=null) {engine.stop();engine.shutdown();}
        if(audio!=null)audio.abandonAudioFocusRequest(focus);
        try {if(input!=null)input.close();}catch(IOException ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}
