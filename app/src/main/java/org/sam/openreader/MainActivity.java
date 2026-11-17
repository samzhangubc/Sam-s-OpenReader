package org.sam.openreader;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.Html;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.*;

/** Initial native reader. Imported originals remain private and immutable. */
public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private TextView text, footer;
    private File book;
    private String content = "";
    private int offset, end, pdfIndex;
    private float downX;
    private boolean reading;
    private android.content.SharedPreferences prefs;
    private int paper = 0xfffffaef, ink = 0xff263b31;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        library();
        if (getIntent().getData() != null) importUri(getIntent().getData());
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    private Button button(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private void layout(String title) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 12, 20, 12);
        root.setBackgroundColor(paper);
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(23);
        heading.setTextColor(ink);
        root.addView(heading);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(20, bars.top + 12, 20, bars.bottom + 12);
            return insets;
        });
    }
    private void library() {
        reading = false;
        layout("OpenReader");
        root.addView(button("Import book / 导入 / 匯入", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, 2);
        }));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(1);
        File dir = new File(getFilesDir(), "books");
        File[] books = dir.listFiles(f -> !f.getName().endsWith(".working") && !f.getName().endsWith(".undo"));
        if (books != null) for (File f : books) {
            list.addView(button(f.getName().substring(14), () -> open(f)));
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == 2) importUri(data.getData());
        if (request == 3) {
            Uri uri = data.getData();
            File original = book;
            worker.execute(() -> {
                try (InputStream in = new FileInputStream(original);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    in.transferTo(out);
                    runOnUiThread(() -> message("Original exported"));
                } catch (Exception ex) { failure(ex); }
            });
        }
    }
    private void importUri(Uri uri) {
        String name = "book.txt";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
        }
        String safe = name.replaceAll("[^\\p{L}\\p{N} ._-]", "_");
        new AlertDialog.Builder(this).setTitle(safe)
            .setItems(new String[]{"Add private library copy", "Open once"}, (d, index) -> {
                File dir = new File(getFilesDir(), index == 0 ? "books" : "temporary");
                dir.mkdirs();
                File target = new File(dir, System.currentTimeMillis() + "-" + safe);
                worker.execute(() -> {
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[32768];
                        long count = 0;
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            count += n;
                            if (count > 50L * 1024 * 1024) throw new IOException("Alpha import limit is 50 MiB");
                            out.write(buffer, 0, n);
                        }
                        runOnUiThread(() -> open(target));
                    } catch (Exception ex) { target.delete(); failure(ex); }
                });
            }).show();
    }
    private void open(File file) {
        book = file;
        pdfIndex = prefs.getInt(file.getName() + ".pdf", 0);
        offset = prefs.getInt(file.getName(), 0);
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) { pdf(); return; }
        message("Opening…");
        worker.execute(() -> {
            try {
                File working = new File(file + ".working");
                String loaded = file.getName().toLowerCase(Locale.ROOT).endsWith(".epub") ?
                    epub(file) : new String(java.nio.file.Files.readAllBytes((working.exists() ? working : file).toPath()),
                    working.exists() ? StandardCharsets.UTF_8 : Charset.forName(prefs.getString("encoding", "UTF-8")));
                runOnUiThread(() -> { content = loaded; reader(); });
            } catch (Exception ex) { failure(ex); }
        });
    }
    private String epub(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document container;
            try (InputStream in = zip.getInputStream(zip.getEntry("META-INF/container.xml"))) {
                container = factory.newDocumentBuilder().parse(in);
            }
            String opf = ((org.w3c.dom.Element)container.getElementsByTagName("rootfile").item(0)).getAttribute("full-path");
            org.w3c.dom.Document packageDoc;
            try (InputStream in = zip.getInputStream(zip.getEntry(opf))) {
                packageDoc = factory.newDocumentBuilder().parse(in);
            }
            Map<String,String> paths = new HashMap<>();
            org.w3c.dom.NodeList manifest = packageDoc.getElementsByTagName("item");
            for (int i = 0; i < manifest.getLength(); i++) {
                org.w3c.dom.Element item = (org.w3c.dom.Element)manifest.item(i);
                paths.put(item.getAttribute("id"), item.getAttribute("href"));
            }
            StringBuilder result = new StringBuilder();
            org.w3c.dom.NodeList spine = packageDoc.getElementsByTagName("itemref");
            for (int i = 0; i < spine.getLength(); i++) {
                String href = paths.get(((org.w3c.dom.Element)spine.item(i)).getAttribute("idref"));
                String path = (opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "") + href;
                ZipEntry entry = zip.getEntry(Uri.decode(path));
                if (entry == null) continue;
                if (entry.getSize() > 5 * 1024 * 1024 || result.length() > 10 * 1024 * 1024)
                    throw new IOException("EPUB exceeds alpha text limits");
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(5 * 1024 * 1024 + 1);
                    if (bytes.length > 5 * 1024 * 1024) throw new IOException("EPUB resource too large");
                    result.append(Html.fromHtml(new String(bytes, StandardCharsets.UTF_8),
                        Html.FROM_HTML_MODE_LEGACY).toString()).append("\n\n");
                }
            }
            return result.toString();
        }
    }
    private void reader() {
        reading = true;
        layout(book.getName().substring(14));
        text = new TextView(this);
        text.setTextSize(prefs.getInt("size", 20));
        text.setTextColor(ink);
        text.setLineSpacing(4, 1.1f);
        root.addView(text, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = new TextView(this);
        footer.setTextColor(ink);
        footer.setTextSize(12);
        root.addView(footer);
        text.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) { downX = event.getX(); return true; }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float distance = event.getX() - downX;
                if (Math.abs(distance) > 60) turn(distance < 0);
                else if (event.getX() < text.getWidth() * .3) turn(false);
                else if (event.getX() > text.getWidth() * .7) turn(true);
                else menu();
                return true;
            }
            return true;
        });
        text.post(this::showPage);
    }
    private void showPage() {
        offset = Math.max(0, Math.min(offset, content.length()));
        int width = Math.max(1, text.getWidth());
        android.text.TextPaint paint = text.getPaint();
        android.text.StaticLayout lines = android.text.StaticLayout.Builder.obtain(content, offset, content.length(), paint, width)
            .setLineSpacing(4, 1.1f).setIncludePad(false).build();
        int line = Math.max(0, lines.getLineForVertical(Math.max(1, text.getHeight() - 12)) - 1);
        end = Math.min(content.length(), offset + lines.getLineEnd(line));
        if (end <= offset) end = Math.min(content.length(), offset + 1);
        text.setText(content.substring(offset, end));
        footer.setText(String.format(Locale.ROOT, "%.1f%%", content.isEmpty() ? 0 : 100.0 * offset / content.length()));
        prefs.edit().putInt(book.getName(), offset).apply();
    }
    private void turn(boolean forward) {
        if (forward) offset = end;
        else offset = Math.max(0, offset - Math.max(1, end - offset));
        showPage();
    }
    private void menu() {
        Dialog sheet = new Dialog(this);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(1);
        for (String label : new String[]{"Contents / 目录", "Appearance", "Listen", "Clean up TXT", "Undo cleanup", "Export original", "Library"}) {
            actions.addView(button(label, () -> {
                sheet.dismiss();
                if (label.startsWith("Contents")) chapters();
                else if (label.equals("Appearance")) appearance();
                else if (label.equals("Listen")) speech();
                else if (label.equals("Clean up TXT")) cleanup();
                else if (label.equals("Undo cleanup")) undo();
                else if (label.equals("Export original")) export();
                else library();
            }));
        }
        sheet.setContentView(actions);
        Window w = sheet.getWindow();
        w.setGravity(Gravity.BOTTOM);
        sheet.show();
        w.setLayout(-1, -2);
    }
    private void chapters() {
        EditText prefix = new EditText(this);
        prefix.setHint("Starts with e.g. 第 or Chapter");
        prefix.setText(prefs.getString("prefix", "第"));
        new AlertDialog.Builder(this).setTitle("Chapter rule: prefix + number + marker")
            .setMessage("Marker must follow the number. Review candidates before jumping.").setView(prefix)
            .setPositiveButton("Preview", (d,w) -> {
                String p = prefix.getText().toString();
                prefs.edit().putString("prefix", p).apply();
                Pattern pattern = Pattern.compile("(?im)^[ \\t]*" + Pattern.quote(p) + "[ \\t]*" +
                    "(?:[0-9０-９一二三四五六七八九十百千零〇两兩壹貳參肆伍陸柒捌玖拾佰仟]+|[IVXLCDM]+)" +
                    (p.equals("第") ? "[章回节節]" : "") + "(?:[ \\t:：][^\\r\\n。！？.!?]{0,40})?$");
                Matcher m = pattern.matcher(content);
                List<String> names = new ArrayList<>(); List<Integer> positions = new ArrayList<>();
                while (m.find() && names.size() < 3000) { names.add(m.group().trim()); positions.add(m.start()); }
                new AlertDialog.Builder(this).setTitle(names.size() + " candidate headings")
                    .setItems(names.toArray(new String[0]), (a,b) -> { offset = positions.get(b); showPage(); })
                    .setNegativeButton("Close", null).show();
            }).setNegativeButton("Cancel", null).show();
    }
    private void appearance() {
        String[] choices = {"Larger text", "Smaller text", "Light", "Dark", "Watercolor", "TXT encoding"};
        new AlertDialog.Builder(this).setTitle("Appearance").setItems(choices, (d,n) -> {
            if (n < 2) prefs.edit().putInt("size", Math.max(12, Math.min(48, prefs.getInt("size",20)+(n==0?2:-2)))).apply();
            if (n == 2) { paper=0xfffffaef; ink=0xff263b31; }
            if (n == 3) { paper=0xff151b18; ink=0xfff5efdf; }
            if (n == 4) { paper=0xffeaf2ee; ink=0xff343837; }
            if (n == 5) new AlertDialog.Builder(this).setTitle("Reopen original as")
                .setItems(new String[]{"UTF-8","UTF-16","GB18030","Big5"}, (a,b) -> {
                    prefs.edit().putString("encoding", new String[]{"UTF-8","UTF-16","GB18030","Big5"}[b]).apply(); open(book);
                }).show();
            else reader();
        }).show();
    }
    private void cleanup() {
        if (!book.getName().endsWith(".txt")) { message("TXT editing only"); return; }
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(1);
        EditText find = new EditText(this), replace = new EditText(this);
        find.setHint("Find literal text"); replace.setHint("Replacement (empty deletes)");
        fields.addView(find); fields.addView(replace);
        new AlertDialog.Builder(this).setTitle("Whole-book cleanup").setView(fields)
            .setPositiveButton("Preview", (d,n) -> {
                String needle = find.getText().toString();
                if (needle.isEmpty()) return;
                int count = (content.length() - content.replace(needle, "").length()) / needle.length();
                new AlertDialog.Builder(this).setMessage(count + " replacements. Original file is preserved.")
                    .setPositiveButton("Apply", (a,b) -> {
                        try {
                            write(new File(book + ".undo"), content);
                            String changed = content.replace(needle, replace.getText().toString());
                            write(new File(book + ".working"), changed);
                            content = changed; offset = 0; reader();
                        } catch (Exception ex) { failure(ex); }
                    }).setNegativeButton("Cancel", null).show();
            }).setNegativeButton("Cancel", null).show();
    }
    private void undo() {
        try {
            File saved = new File(book + ".undo");
            if (!saved.exists()) { message("No cleanup to undo"); return; }
            content = new String(java.nio.file.Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8);
            write(new File(book + ".working"), content); offset = 0; reader();
        } catch (Exception ex) { failure(ex); }
    }
    private void write(File file, String value) throws IOException {
        File staging = new File(file + ".part");
        java.nio.file.Files.write(staging.toPath(), value.getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.move(staging.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private void export() {
        Intent out = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream");
        out.addCategory(Intent.CATEGORY_OPENABLE); out.putExtra(Intent.EXTRA_TITLE, book.getName().substring(14));
        startActivityForResult(out, 3);
    }
    private void speech() {
        if (!"com.samsung.SMT".equals(android.provider.Settings.Secure.getString(
                getContentResolver(), "tts_default_synth"))) {
            message("Set Samsung TTS as your preferred engine in phone Settings first");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Samsung TTS")
            .setItems(new String[]{"Start / resume", "Pause", "← Previous paragraph", "Next paragraph →", "■ Quit TTS", "Speed", "Stop after 15 minutes"},
                (d,n) -> {
                    if (n == 5) {
                        new AlertDialog.Builder(this).setItems(new String[]{"0.5×","1.0×","1.5×","2.0×","3.0×"}, (a,b) ->
                            getSharedPreferences("speech",0).edit().putFloat("rate", new float[]{.5f,1,1.5f,2,3}[b]).apply()).show();
                        return;
                    }
                    boolean resume = getSharedPreferences("speech",0).getBoolean("session",false);
                    if (n == 0 && !resume) {
                        try { write(new File(getFilesDir(), "speech.txt"), content.substring(offset)); }
                        catch(Exception ex) { failure(ex); return; }
                    }
                    Intent command = new Intent(this, SpeechService.class).setAction(new String[]{"play","pause","prev","next","quit","rate","timer"}[n]);
                    if(n==0 && resume) command.setAction("resume");
                    if (n==0) startForegroundService(command); else startService(command);
                }).show();
    }
    private void pdf() {
        reading = true;
        layout(book.getName().substring(14));
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        root.addView(image, new LinearLayout.LayoutParams(-1,0,1));
        worker.execute(() -> {
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(book, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(fd)) {
                pdfIndex=Math.max(0,Math.min(pdfIndex,renderer.getPageCount()-1));
                int total=renderer.getPageCount();
                try (PdfRenderer.Page p = renderer.openPage(pdfIndex)) {
                    Bitmap bitmap=Bitmap.createBitmap(1080,Math.max(1,(int)(1080f*p.getHeight()/p.getWidth())),Bitmap.Config.ARGB_8888);
                    p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    runOnUiThread(() -> {
                        image.setImageBitmap(bitmap);
                        root.addView(button("←  "+(pdfIndex+1)+"/"+total+"  →", () -> {if(pdfIndex+1<total){pdfIndex++;pdf();}}));
                        root.addView(button("Previous page", () -> {if(pdfIndex>0){pdfIndex--;pdf();}}));
                        root.addView(button("Library",this::library));
                        prefs.edit().putInt(book.getName()+".pdf",pdfIndex).apply();
                    });
                }
            } catch (Exception ex) { failure(ex); }
        });
    }
    private void message(String msg) { Toast.makeText(this,msg,Toast.LENGTH_LONG).show(); }
    private void failure(Exception ex) { runOnUiThread(() -> message(ex.getMessage()==null?"Unable to open book":ex.getMessage())); }
    @Override public void onBackPressed() { if(reading) library(); else super.onBackPressed(); }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
