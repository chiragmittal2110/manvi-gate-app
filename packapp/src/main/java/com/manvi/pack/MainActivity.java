package com.manvi.pack;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manvi Pack Box: opens straight into the camera. Scan a box barcode, enter the style and the quantity, save.
 * It talks to the same Manvi Hub "api" function that the Hub's Pack Box page uses, so stock stays identical.
 */
public class MainActivity extends AppCompatActivity {
    private static final String FUNCS = "https://avtjiroinquslddliyyi.supabase.co/functions/v1/";
    private static final String API = FUNCS + "api";
    private static final int NAVY = Color.parseColor("#0F2A43");
    private static final int GREEN = Color.parseColor("#0B7A3E");
    private static final int RED = Color.parseColor("#B3261E");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final ExecutorService cam = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private PreviewView preview;
    private BarcodeScanner scanner;
    private volatile boolean paused = false;
    private ProcessCameraProvider provider;

    private FrameLayout root;
    private TextView hint;
    private LinearLayout sheet;
    private View frameView;
    private Button typeBtn;
    private View topBar;

    // current form state
    private String curBarcode = "";
    private String pickedStyle = "";          // exact style number chosen from the list
    private final List<String> lastSuggestions = new ArrayList<String>();
    private int searchSeq = 0;
    private Runnable pendingSearch;
    private boolean editing = false;          // true while editing an already-packed box
    private JSONObject editBox = null;
    private Button oldBtn;                    // "Old style" button (style not in the system)

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pack", Context.MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
        if (prefs.getString("name", "").isEmpty()) askName();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else hint.setText("Camera permission is needed to scan. Allow it in Android settings, or use \"Type barcode\".");
    }

    // ---------------------------------------------------------------- UI
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button bigButton(String text, int bg) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setTextSize(18);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg); g.setCornerRadius(dp(14));
        bt.setBackground(g);
        bt.setMinHeight(dp(56));
        return bt;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // aiming frame
        View frame = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(Color.TRANSPARENT); fg.setStroke(dp(3), Color.WHITE); fg.setCornerRadius(dp(16));
        frame.setBackground(fg);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(280), dp(180), Gravity.CENTER);
        fp.bottomMargin = dp(60);
        frameView = frame;
        root.addView(frame, fp);

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(NAVY);
        top.setPadding(dp(16), dp(36), dp(16), dp(12));
        TextView title = new TextView(this);
        title.setText("Pack Box");
        title.setTextColor(Color.WHITE); title.setTextSize(22); title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView who = new TextView(this);
        who.setText(prefs.getString("name", "").isEmpty() ? "Set name" : prefs.getString("name", ""));
        who.setTextColor(Color.parseColor("#BFD0E0")); who.setTextSize(14);
        who.setOnClickListener(v -> askName());
        top.addView(who);
        topBar = top;
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        hint = new TextView(this);
        hint.setText("Point the camera at the box barcode");
        hint.setTextColor(Color.WHITE); hint.setTextSize(18); hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(Color.parseColor("#99000000"));
        hint.setPadding(dp(16), dp(10), dp(16), dp(10));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        hp.bottomMargin = dp(96);
        root.addView(hint, hp);

        Button type = bigButton("Type barcode", Color.parseColor("#33FFFFFF"));
        typeBtn = type;
        type.setOnClickListener(v -> typeBarcode());
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        tp.setMargins(dp(16), 0, dp(16), dp(20));
        root.addView(type, tp);

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(Color.WHITE);
        sheet.setBackground(sg);
        sheet.setPadding(dp(18), dp(18), dp(18), dp(18));
        sheet.setVisibility(View.GONE);
        sheet.setClickable(true);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.WHITE);
        sv.addView(sheet);
        sv.setVisibility(View.GONE);
        sv.setTag("sheetScroll");
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM);
        sp.topMargin = dp(84);
        root.addView(sv, sp);
    }

    private View sheetScroll() { return root.findViewWithTag("sheetScroll"); }

    private void showSheet() {
        sheet.setVisibility(View.VISIBLE); sheetScroll().setVisibility(View.VISIBLE);
        hint.setVisibility(View.INVISIBLE); typeBtn.setVisibility(View.INVISIBLE); frameView.setVisibility(View.INVISIBLE);
    }
    private void hideSheet() {
        sheet.removeAllViews(); sheet.setVisibility(View.GONE); sheetScroll().setVisibility(View.GONE);
        hint.setVisibility(View.VISIBLE); typeBtn.setVisibility(View.VISIBLE); frameView.setVisibility(View.VISIBLE);
        hideKeyboard();
    }
    private void hideKeyboard() {
        try { InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE); if (im != null) im.hideSoftInputFromWindow(root.getWindowToken(), 0); } catch (Exception ignored) {}
    }

    private TextView label(String t, int size, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private EditText field(String hintText, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setInputType(inputType);
        e.setTextSize(22);
        e.setSingleLine(true);
        e.setTextColor(Color.BLACK);
        return e;
    }

    // ---------------------------------------------------------------- camera
    private void startCamera() {
        scanner = BarcodeScanning.getClient();
        final ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
            try {
                provider = fut.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis ia = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                ia.setAnalyzer(cam, imageProxy -> {
                    if (paused || imageProxy.getImage() == null) { imageProxy.close(); return; }
                    InputImage img = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
                    scanner.process(img)
                            .addOnSuccessListener(codes -> {
                                for (Barcode c : codes) {
                                    String v = c.getRawValue();
                                    if (v != null && v.trim().length() > 0 && !paused) { paused = true; final String code = v.trim(); ui.post(() -> onBarcode(code)); break; }
                                }
                            })
                            .addOnCompleteListener(t -> imageProxy.close());
                });
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ia);
            } catch (Exception e) {
                hint.setText("Could not start the camera. You can still use \"Type barcode\".");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ---------------------------------------------------------------- flow
    private void typeBarcode() {
        if (paused) return;
        paused = true;
        hideKeyboard();
        sheet.removeAllViews();
        sheet.addView(label("Type the barcode", 20, true, NAVY));
        final EditText code = field("Barcode", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sheet.addView(code);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = bigButton("Cancel", Color.GRAY);
        Button ok = bigButton("Continue", NAVY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(0, dp(14), dp(8), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2);
        lp2.setMargins(dp(8), dp(14), 0, 0);
        row.addView(cancel, lp); row.addView(ok, lp2);
        sheet.addView(row);
        cancel.setOnClickListener(v -> resume());
        ok.setOnClickListener(v -> { String c = code.getText().toString().trim(); if (c.length() > 0) onBarcode(c); });
        showSheet();
        code.requestFocus();
    }

    private void resume() { editing = false; editBox = null; hideSheet(); paused = false; hint.setText("Point the camera at the box barcode"); }

    private void onBarcode(final String code) {
        paused = true;
        editing = false; editBox = null;
        curBarcode = code;
        sheet.removeAllViews();
        sheet.addView(label(code, 22, true, NAVY));
        sheet.addView(label("Checking…", 16, false, Color.DKGRAY));
        showSheet();
        net.execute(() -> {
            try {
                JSONObject r = get("/barcode/" + URLEncoder.encode(code, "UTF-8"));
                final boolean found = r.optBoolean("found", false);
                final JSONObject box = r.optJSONObject("box");
                ui.post(() -> { if (found && box != null) showAlreadyPacked(box); else showForm(); });
            } catch (Exception e) {
                ui.post(() -> showError("No internet connection. Check the network and scan again."));
            }
        });
    }

    private void showAlreadyPacked(final JSONObject box) {
        sheet.removeAllViews();
        sheet.addView(label(curBarcode, 22, true, NAVY));
        TextView t = label("This box is already registered", 20, true, RED);
        sheet.addView(t);
        String packedAt = box.optString("packed_at", "");
        if (packedAt.length() >= 10) packedAt = packedAt.substring(0, 10);
        sheet.addView(label(box.optString("style_number", "") + "  ·  " + box.optInt("pieces", 0) + " pcs\n" + box.optString("status", "") + (packedAt.isEmpty() ? "" : "  ·  " + packedAt), 18, false, Color.BLACK));
        if ("PACKED".equals(box.optString("status", ""))) {
            sheet.addView(label("Do you want to edit it?", 18, true, Color.BLACK));
            Button edit = bigButton("Edit this box", GREEN);
            LinearLayout.LayoutParams lpe = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpe.topMargin = dp(14);
            sheet.addView(edit, lpe);
            edit.setOnClickListener(v -> { editing = true; editBox = box; showForm(); });
        } else {
            sheet.addView(label("It can no longer be edited here (use the Manvi Hub).", 16, false, RED));
        }
        Button next = bigButton("Scan next box", NAVY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        sheet.addView(next, lp);
        next.setOnClickListener(v -> resume());
    }

    private void showError(String msg) {
        sheet.removeAllViews();
        sheet.addView(label(msg, 18, true, RED));
        Button next = bigButton("OK", NAVY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        sheet.addView(next, lp);
        next.setOnClickListener(v -> resume());
        showSheet();
    }

    private void showForm() {
        sheet.removeAllViews();
        pickedStyle = "";
        lastSuggestions.clear();
        sheet.addView(label((editing ? "Edit box · " : "New box · ") + curBarcode, 18, true, NAVY));

        sheet.addView(label("Style", 14, false, Color.DKGRAY));
        final EditText style = field("Type the style number", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sheet.addView(style);
        final LinearLayout sugg = new LinearLayout(this);
        sugg.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(sugg);

        sheet.addView(label("Quantity (pieces in this box)", 14, false, Color.DKGRAY));
        final EditText qty = field("Pieces", InputType.TYPE_CLASS_NUMBER);
        sheet.addView(qty);

        final TextView msg = label("", 16, true, RED);
        sheet.addView(msg);

        oldBtn = bigButton("Old style (not in system)", Color.parseColor("#B26A00"));
        oldBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams lpo = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpo.topMargin = dp(8);
        sheet.addView(oldBtn, lpo);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = bigButton("Cancel", Color.GRAY);
        final Button save = bigButton(editing ? "Save changes" : "Save box", GREEN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(0, dp(14), dp(8), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2);
        lp2.setMargins(dp(8), dp(14), 0, 0);
        row.addView(cancel, lp); row.addView(save, lp2);
        sheet.addView(row);

        // prefill with the last box (boxes of one style usually come together)
        String ls = prefs.getString("lastStyle", "");
        int lq = prefs.getInt("lastQty", 0);
        if (editing && editBox != null) {
            ls = editBox.optString("style_number", ""); lq = editBox.optInt("pieces", 0);
        }
        if (!ls.isEmpty()) { style.setText(ls); pickedStyle = ls; style.setSelection(ls.length()); }
        if (lq > 0) qty.setText(String.valueOf(lq));

        style.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable e) {
                String t = e.toString().trim();
                if (!t.equals(pickedStyle)) pickedStyle = "";
                msg.setText("");
                if (oldBtn != null) oldBtn.setVisibility(View.GONE);
                scheduleSearch(t, sugg, style, qty);
            }
        });

        cancel.setOnClickListener(v -> resume());
        save.setOnClickListener(v -> {
            String typed = style.getText().toString().trim();
            int pcs = 0;
            try { pcs = Integer.parseInt(qty.getText().toString().trim()); } catch (Exception ignored) {}
            if (typed.isEmpty()) { msg.setText("Enter the style."); return; }
            if (pcs < 1) { msg.setText("Enter the quantity (1 or more)."); return; }
            String norm = norm(typed);
            String use = pickedStyle;
            if (use.isEmpty()) {
                for (String s : lastSuggestions) if (norm(s).equals(norm)) { use = s; break; }
            }
            if (use.isEmpty()) {
                if (canOldStyle()) { msg.setText("Style not found. Tap the matching style from the list, or tap Old style."); oldBtn.setVisibility(View.VISIBLE); }
                else msg.setText("Style not found in the system. Tap the matching style from the list.");
                return;
            }
            save.setEnabled(false);
            if (editing) doEdit(use, pcs, false, msg, save); else doSave(use, pcs, msg, save);
        });
        oldBtn.setOnClickListener(v -> {
            final String typed = style.getText().toString().trim().toUpperCase();
            int pcs0 = 0;
            try { pcs0 = Integer.parseInt(qty.getText().toString().trim()); } catch (Exception ignored) {}
            if (norm(typed).length() < 2) { msg.setText("Enter the style."); return; }
            if (pcs0 < 1) { msg.setText("Enter the quantity (1 or more)."); return; }
            final int pcs = pcs0;
            new AlertDialog.Builder(this)
                .setTitle("Register old style?")
                .setMessage(typed + "\n\nThis style is not in the system. It will be registered as an old style and this box of " + pcs + " pcs counted as opening stock.\n\nCheck the style number carefully.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Yes, register", (d, w) -> { save.setEnabled(false); oldBtn.setEnabled(false); if (editing) doEdit(typed, pcs, true, msg, save); else doOld(typed, pcs, msg, save); })
                .show();
        });
        showSheet();
        if (prefs.getString("lastStyle", "").isEmpty()) style.requestFocus(); else qty.requestFocus();
        if (qty.getText().length() > 0) qty.selectAll();
    }

    private static String norm(String s) { return s == null ? "" : s.toUpperCase().replaceAll("[^A-Z0-9]", ""); }

    private void scheduleSearch(final String text, final LinearLayout sugg, final EditText style, final EditText qty) {
        if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
        final int seq = ++searchSeq;
        if (norm(text).length() < 2) { sugg.removeAllViews(); lastSuggestions.clear(); return; }
        pendingSearch = () -> net.execute(() -> {
            try {
                JSONObject r = get("/styles/search?q=" + URLEncoder.encode(text, "UTF-8"));
                JSONArray arr = r.optJSONArray("styles");
                final List<String> names = new ArrayList<String>();
                if (arr != null) for (int i = 0; i < arr.length() && names.size() < 6; i++) names.add(arr.getJSONObject(i).optString("style_number"));
                ui.post(() -> {
                    if (seq != searchSeq) return;
                    lastSuggestions.clear(); lastSuggestions.addAll(names);
                    sugg.removeAllViews();
                    boolean exact = false;
                    for (String n : names) if (norm(n).equals(norm(text))) exact = true;
                    if (oldBtn != null) oldBtn.setVisibility(!exact && norm(text).length() >= 2 && pickedStyle.isEmpty() && canOldStyle() ? View.VISIBLE : View.GONE);
                    for (final String n : names) {
                        if (n.equals(pickedStyle)) continue;
                        TextView tv = label(n, 20, true, NAVY);
                        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                        GradientDrawable g = new GradientDrawable();
                        g.setColor(Color.parseColor("#E8EEF4")); g.setCornerRadius(dp(10));
                        tv.setBackground(g);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        lp.topMargin = dp(4);
                        tv.setOnClickListener(v -> {
                            pickedStyle = n;
                            style.setText(n); style.setSelection(n.length());
                            sugg.removeAllViews();
                            qty.requestFocus();
                        });
                        sugg.addView(tv, lp);
                    }
                });
            } catch (Exception ignored) {}
        });
        ui.postDelayed(pendingSearch, 250);
    }

    private boolean canOldStyle() {
        return !editing || (editBox != null && "OLD_STYLE".equals(editBox.optString("source", "")));
    }

    private String who() { return prefs.getString("name", "").isEmpty() ? "Pack app" : prefs.getString("name", ""); }

    private void doOld(final String styleNumber, final int pcs, final TextView msg, final Button save) {
        final String code = curBarcode;
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("barcode", code); body.put("styleNumber", styleNumber); body.put("pieces", pcs); body.put("packedBy", who());
                final JSONObject r = call("POST", FUNCS + "packold", body);
                ui.post(() -> {
                    if (r.optBoolean("success", false)) {
                        prefs.edit().putString("lastStyle", styleNumber).putInt("lastQty", pcs).apply();
                        showDone("✔ Old style box saved", styleNumber, pcs);
                    } else {
                        msg.setText(r.optString("message", "Could not save. Try again."));
                        save.setEnabled(true); if (oldBtn != null) oldBtn.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> { msg.setText("No internet connection. Nothing was saved. Try again."); save.setEnabled(true); if (oldBtn != null) oldBtn.setEnabled(true); });
            }
        });
    }

    private void doEdit(final String styleNumber, final int pcs, final boolean oldStyle, final TextView msg, final Button save) {
        final String code = curBarcode;
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("barcode", code); body.put("styleNumber", styleNumber); body.put("pieces", pcs);
                body.put("editedBy", who()); body.put("oldStyle", oldStyle);
                final JSONObject r = call("POST", FUNCS + "boxedit", body);
                ui.post(() -> {
                    if (r.optBoolean("success", false)) {
                        showDone("✔ Box updated", styleNumber, pcs);
                    } else {
                        msg.setText(r.optString("message", "Could not save. Try again."));
                        save.setEnabled(true); if (oldBtn != null) oldBtn.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> { msg.setText("No internet connection. Nothing was changed. Try again."); save.setEnabled(true); if (oldBtn != null) oldBtn.setEnabled(true); });
            }
        });
    }

    private void doSave(final String styleNumber, final int pcs, final TextView msg, final Button save) {
        final String code = curBarcode;
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("barcode", code);
                body.put("styleNumber", styleNumber);
                body.put("pieces", pcs);
                body.put("packedBy", prefs.getString("name", "").isEmpty() ? "Pack app" : prefs.getString("name", ""));
                final JSONObject r = post("/barcode/pack", body);
                ui.post(() -> {
                    if (r.optBoolean("success", false)) {
                        prefs.edit().putString("lastStyle", styleNumber).putInt("lastQty", pcs).apply();
                        showDone("✔ Box saved", styleNumber, pcs);
                    } else {
                        msg.setText(r.optString("message", "Could not save. Try again."));
                        save.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> { msg.setText("No internet connection. Nothing was saved. Try again."); save.setEnabled(true); });
            }
        });
    }

    private void showDone(String title, String style, int pcs) {
        sheet.removeAllViews();
        sheet.addView(label(title, 24, true, GREEN));
        sheet.addView(label(style + "  ·  " + pcs + " pcs", 20, true, Color.BLACK));
        hideKeyboard();
        ui.postDelayed(this::resume, 1300);
    }

    // ---------------------------------------------------------------- settings
    private void askName() {
        final boolean wasPaused = paused;
        paused = true;
        sheet.removeAllViews();
        sheet.addView(label("Who is packing?", 20, true, NAVY));
        final EditText name = field("Your name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(prefs.getString("name", ""));
        sheet.addView(name);
        Button ok = bigButton("Save", NAVY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        sheet.addView(ok, lp);
        ok.setOnClickListener(v -> {
            prefs.edit().putString("name", name.getText().toString().trim()).apply();
            hideSheet();
            paused = wasPaused;
            recreate();
        });
        showSheet();
    }

    // ---------------------------------------------------------------- network
    private JSONObject get(String path) throws Exception { return call("GET", API + path, null); }
    private JSONObject post(String path, JSONObject body) throws Exception { return call("POST", API + path, body); }

    private JSONObject call(String method, String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(20000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        c.disconnect();
        try { return new JSONObject(sb.toString()); } catch (Exception e) { JSONObject j = new JSONObject(); j.put("success", false); j.put("message", "Server problem (" + code + "). Try again."); return j; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (scanner != null) scanner.close(); } catch (Exception ignored) {}
        net.shutdown(); cam.shutdown();
    }
}
