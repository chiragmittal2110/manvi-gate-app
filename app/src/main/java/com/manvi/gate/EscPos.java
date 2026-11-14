package com.manvi.gate;

import android.graphics.Bitmap;

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
                            if (lum < 140) v |= (0x80 >> bit);
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
}
