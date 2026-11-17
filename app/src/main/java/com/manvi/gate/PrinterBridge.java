package com.manvi.gate;

import android.webkit.JavascriptInterface;

/** The functions the kiosk page can call. */
public class PrinterBridge {
    private final MainActivity act;

    PrinterBridge(MainActivity a) { act = a; }

    @JavascriptInterface
    public String version() { return "2"; }

    @JavascriptInterface
    public boolean hasPrinter() { return act.getPrinterAddress() != null; }

    /** base64 of a PNG image of the slip, 576 pixels wide. */
    @JavascriptInterface
    public void printPng(final String base64) { act.printPngAsync(base64); }

    /** JSON list of text lines, printed with the printer's own Font A (see EscPos.fromText). */
    @JavascriptInterface
    public void printText(final String json) { act.printTextAsync(json); }

    @JavascriptInterface
    public void choosePrinter() {
        act.runOnUiThread(new Runnable() { public void run() { act.pickPrinter(); } });
    }
}
