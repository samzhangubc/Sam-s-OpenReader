package org.sam.openreader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        return "notes.txt".equals(uri.getLastPathSegment()) ? "text/plain" : "application/pdf";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME });
        cursor.addRow(new Object[] { uri.getLastPathSegment() });
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read only");
        String name = uri.getLastPathSegment();
        if (!"notes.txt".equals(name) && !"fixture.pdf".equals(name)) throw new IllegalArgumentException("Fixture");
        File file = new File(getContext().getCacheDir(), name);
        if ("fixture.pdf".equals(name)) {
            PdfDocument doc = new PdfDocument();
            try {
                for (int page = 1; page <= 3; page++) {
                    PdfDocument.Page p = doc.startPage(new PdfDocument.PageInfo.Builder(300,400,page).create());
                    p.getCanvas().drawText("Page " + page,20,40,new Paint());
                    doc.finishPage(p);
                }
                try (FileOutputStream out = new FileOutputStream(file)) { doc.writeTo(out); }
                catch (IOException e) { throw new IllegalStateException(e); }
            } finally { doc.close(); }
        } else {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write("Chapter 1\nThe first harbour.\n\n第十二章 山雨\n第二段。🌊".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch(IOException e) { throw new IllegalStateException(e); }
        }
        try { return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY); }
        catch(IOException e) { throw new IllegalStateException(e); }
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String s,String[] a){return 0;}
    @Override public int delete(Uri uri,String s,String[] a){return 0;}
}
