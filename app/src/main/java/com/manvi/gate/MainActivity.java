package com.manvi.gate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String DEFAULT_URL = "https://gate.manvihub.com/";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String PREFS = "gate";

    private WebView web;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String printScript = "";

    // hidden admin gesture: tap the top-left corner 7 times
    private int cornerTaps = 0;
    private long lastCornerTap = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        printScript = readAsset("print-bridge.js");

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new PrinterBridge(this), "AndroidPrinter");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String host = r.getUrl().getHost();
                String home = Uri.parse(kioskUrl()).getHost();
                return host == null || !host.equals(home); // stay on the kiosk site only
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (printScript.length() > 0) v.evaluateJavascript(printScript, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            req.grant(req.getResources());
                        } else {
                            req.deny();
                        }
                    }
                });
            }
        });

        requestNeededPermissions();
        web.loadUrl(kioskUrl());
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (web != null) web.reload(); // so the camera works right after it is allowed
    }

    private String kioskUrl() { return prefs.getString("url", DEFAULT_URL); }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    // ---------- full screen ----------
    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        try { startLockTask(); } catch (Exception ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    public void onBackPressed() { /* no way out with the back button */ }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getX() < 160 && ev.getY() < 160) {
            long now = System.currentTimeMillis();
            cornerTaps = (now - lastCornerTap < 1500) ? cornerTaps + 1 : 1;
            lastCornerTap = now;
            if (cornerTaps >= 7) { cornerTaps = 0; showAdmin(); return true; }
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---------- admin menu ----------
    private void showAdmin() {
        final String[] items = {"Choose printer", "Test print", "Set kiosk address", "Reload page", "Exit kiosk"};
        new AlertDialog.Builder(this).setTitle("Gate admin").setItems(items, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int which) {
                if (which == 0) pickPrinter();
                else if (which == 1) testPrint();
                else if (which == 2) askUrl();
                else if (which == 3) web.loadUrl(kioskUrl());
                else exitKiosk();
            }
        }).show();
    }

    private void askUrl() {
        final EditText in = new EditText(this);
        in.setText(kioskUrl());
        new AlertDialog.Builder(this).setTitle("Kiosk address (paste the setup link)").setView(in)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String u = in.getText().toString().trim();
                        if (u.length() > 0) { prefs.edit().putString("url", u).apply(); web.loadUrl(u); }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void exitKiosk() {
        try { stopLockTask(); } catch (Exception ignored) {}
        finish();
    }

    // ---------- printer ----------
    String getPrinterAddress() { return prefs.getString("printer", null); }

    private boolean btAllowed() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    void pickPrinter() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !btAllowed()) { toast("Bluetooth is off or not allowed. Allow it and try again."); requestNeededPermissions(); return; }
        if (!ad.isEnabled()) { toast("Switch Bluetooth on, pair the printer in Android settings, then try again."); return; }
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        final List<BluetoothDevice> list = new ArrayList<BluetoothDevice>(bonded);
        if (list.isEmpty()) { toast("No paired devices. Pair the printer in Android Bluetooth settings first."); return; }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).getName() + "\n" + list.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("Choose the printer").setItems(names, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int which) {
                prefs.edit().putString("printer", list.get(which).getAddress()).apply();
                toast("Printer saved.");
            }
        }).show();
    }

    private void testPrint() {
        Bitmap bmp = Bitmap.createBitmap(EscPos.WIDTH_DOTS, 220, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(40);
        c.drawText("MANVI GATE", EscPos.WIDTH_DOTS / 2f, 70, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(28);
        c.drawText("Printer test OK", EscPos.WIDTH_DOTS / 2f, 120, p);
        c.drawText("केवल टेस्ट", EscPos.WIDTH_DOTS / 2f, 165, p);
        sendAsync(EscPos.fromBitmap(bmp));
    }

    void printPngAsync(String b64) {
        try {
            int comma = b64.indexOf(',');
            if (comma >= 0) b64 = b64.substring(comma + 1);
            byte[] png = Base64.decode(b64, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bmp == null) { fail("Could not read the slip image."); return; }
            sendAsync(EscPos.fromBitmap(bmp));
        } catch (Exception e) { fail("Print error: " + e.getMessage()); }
    }

    private void sendAsync(final byte[] data) {
        final String addr = getPrinterAddress();
        if (addr == null) {
            ui.post(new Runnable() { public void run() { toast("No printer chosen yet. Choose one, then print again."); pickPrinter(); } });
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                BluetoothSocket sock = null;
                try {
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    if (ad == null || !btAllowed()) throw new Exception("Bluetooth is not allowed");
                    // no cancelDiscovery(): we never scan (only paired printers), and it needs the extra Bluetooth-scan permission on Android 12+
                    BluetoothDevice dev = ad.getRemoteDevice(addr);
                    sock = dev.createRfcommSocketToServiceRecord(SPP);
                    sock.connect();
                    OutputStream os = sock.getOutputStream();
                    int off = 0;
                    while (off < data.length) {
                        // small chunks with a pause: the printer then prints slowly, which makes the slip dark like the test print
                        int n = Math.min(256, data.length - off);
                        os.write(data, off, n);
                        off += n;
                        os.flush();
                        Thread.sleep(25);
                    }
                    Thread.sleep(2500); // let the printer finish receiving before the link is closed
                    done(true, "");
                } catch (Exception e) {
                    done(false, e.getMessage() == null ? e.toString() : e.getMessage());
                } finally {
                    try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void fail(String msg) { done(false, msg); }

    private void done(final boolean ok, final String msg) {
        ui.post(new Runnable() {
            public void run() {
                if (!ok) toast("Print failed: " + msg + " (check the printer is on and in range)");
                String js = "window.__androidPrintDone&&window.__androidPrintDone(" + ok + ")";
                web.evaluateJavascript(js, null);
            }
        });
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
}
