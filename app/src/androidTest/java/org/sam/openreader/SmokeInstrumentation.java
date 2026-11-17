package org.sam.openreader;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import java.nio.file.Files;

/** Device smoke test through the library's normal entry point. */
public class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            File folder=new File(getTargetContext().getFilesDir(),"books");
            folder.mkdirs();
            File sample=new File(folder,"1700000000000-Smoke.txt");
            Files.write(sample.toPath(),"第一章 山雨\n\nA quiet harbour.\n\n第二章 歸途\n\nHello 🌙.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Intent launch=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity=startActivitySync(launch);
            waitForIdleSync();
            runOnMainSync(() -> {
                View book=find(activity.getWindow().getDecorView(),"Smoke.txt");
                if(book==null) throw new AssertionError("Imported book missing from library");
                book.performClick();
            });
            boolean opened=false;
            for(int i=0;i<50;i++) {
                Thread.sleep(100);
                final boolean[] found={false};
                runOnMainSync(() -> found[0]=find(activity.getWindow().getDecorView(),"A quiet harbour.")!=null);
                if(found[0]) {opened=true;break;}
            }
            if(!opened)throw new AssertionError("Reader failed to display Unicode sample");
            result.putString("stream","PASS: library to TXT reader, Unicode content");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("stream","FAIL: "+error);
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private View find(View view,String text) {
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(text))return view;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                View found=find(group.getChildAt(i),text);if(found!=null)return found;
            }
        }
        return null;
    }
}
