package com.manvi.gate;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/** Turns a bitmap into ESC/POS raster commands for an 80 mm receipt printer. */
final class EscPos {
    static final int WIDTH_DOTS = 576; // 80 mm paper, 72 mm printable at 203 dpi

    private EscPos() {}

    static byte[] fromBitmap(Bitmap src) {
        Bitmap b = src;
        if (src.getWidth() != WIDTH_DOTS) {
            int h = Math.max(1, Math.round(src.getHeight() * (float) WIDTH_DOTS / src.getWidth()));
            b = Bitmap.createScaledBitmap(src, WIDTH_DOTS, h, true);
        }
        int w = b.getWidth();
        int h = b.getHeight();
        int bytesPerRow = (w + 7) / 8;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B); out.write(0x40);               // ESC @  initialise

        final int band = 200;
        for (int y0 = 0; y0 < h; y0 += band) {
            int rows = Math.min(band, h - y0);
            out.write(0x1D); out.write(0x76); out.write(0x30); out.write(0x00); // GS v 0
            out.write(bytesPerRow & 0xFF); out.write((bytesPerRow >> 8) & 0xFF);
            out.write(rows & 0xFF); out.write((rows >> 8) & 0xFF);
            for (int r = 0; r < rows; r++) {
                int base = (y0 + r) * w;
                for (int bx = 0; bx < bytesPerRow; bx++) {
                    int v = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = bx * 8 + bit;
                        if (x < w) {
                            int c = px[base + x];
                            int lum = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
                            if (lum < 190) v |= (0x80 >> bit); // generous: thin / grey text still comes out solid black
                        }
                    }
                    out.write(v);
                }
            }
        }
        out.write(0x1B); out.write(0x64); out.write(0x04);                // ESC d 4  feed 4 lines
        out.write(0x1D); out.write(0x56); out.write(0x42); out.write(0x00); // GS V 66 0  feed and cut
        return out.toByteArray();
    }

    /** Prints plain text lines with the printer's own built-in Font A (12x24), the same font as its self-test page.
     *  ops: [{"s":"text","b":0|1,"z":0|1|17}]  b = bold, z = GS ! size byte (0 normal, 1 double height, 17 double width+height). */
    static byte[] fromText(JSONArray ops) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B); out.write(0x40);                 // ESC @  initialise
        out.write(0x1B); out.write(0x74); out.write(0x00); // ESC t 0  code page CP437
        out.write(0x1B); out.write(0x4D); out.write(0x00); // ESC M 0  Font A (12x24)
        out.write(0x1B); out.write(0x32);                 // ESC 2  default line spacing
        for (int i = 0; i < ops.length(); i++) {
            JSONObject o = ops.getJSONObject(i);
            String s = o.optString("s", "");
            int b = o.optInt("b", 0), z = o.optInt("z", 0);
            out.write(0x1B); out.write(0x45); out.write(b);   // ESC E  bold
            out.write(0x1D); out.write(0x21); out.write(z);   // GS !   size
            byte[] bytes = s.getBytes("ISO-8859-1");
            for (int k = 0; k < bytes.length; k++) { int c = bytes[k] & 0xFF; out.write(c >= 0x20 && c <= 0x7E ? c : '?'); }
            out.write(0x0A);
        }
        out.write(0x1B); out.write(0x45); out.write(0x00);
        out.write(0x1D); out.write(0x21); out.write(0x00);
        out.write(0x1B); out.write(0x64); out.write(0x04);                // ESC d 4  feed 4 lines
        out.write(0x1D); out.write(0x56); out.write(0x42); out.write(0x00); // GS V 66 0  feed and cut
        return out.toByteArray();
    }
}
