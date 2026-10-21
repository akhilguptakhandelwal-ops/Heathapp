package com.akhil.framecast;

import android.graphics.Bitmap;

/**
 * Hides the tool panel in a captured screen image: the panel's area is filled
 * by stretching the screen pixels right next to it (the panel sits at a screen
 * edge, where apps usually show plain background).
 */
final class PanelMask {
    private PanelMask() {
    }

    /**
     * @param shot   captured screen, {@code w} x {@code h} used
     * @param panel  panel bounds in real screen pixels {left, top, right, bottom}
     * @param sx     capture pixels per screen pixel, horizontally
     * @param sy     same, vertically
     * @param pad    extra pixels around the panel (its shadow / border), capture pixels
     */
    static void apply(Bitmap shot, int w, int h, int[] panel, float sx, float sy, float pad) {
        if (shot == null || panel == null || w <= 8 || h <= 8) return;
        int l = (int) Math.floor(panel[0] * sx - pad);
        int t = (int) Math.floor(panel[1] * sy - pad);
        int rgt = (int) Math.ceil(panel[2] * sx + pad);
        int bot = (int) Math.ceil(panel[3] * sy + pad);
        l = Math.max(0, l);
        t = Math.max(0, t);
        rgt = Math.min(w, rgt);
        bot = Math.min(h, bot);
        if (rgt - l < 2 || bot - t < 2) return;
        // Close the small gap between the panel and the screen edge too.
        int edgeGap = (int) (w * 0.04f);
        boolean fromLeft = l > w - rgt;
        if (fromLeft && w - rgt <= edgeGap) rgt = w;
        if (!fromLeft && l <= edgeGap) l = 0;
        int width = rgt - l, height = bot - t;
        if (width <= 0 || height <= 0) return;
        int src = fromLeft ? l - 1 : rgt;        // column just outside the panel
        if (src < 0 || src >= w) return;
        // Sample 5 columns spread over ~28 px and take the middle value per channel,
        // so thin things (text, an arrow line) next to the panel don't get stretched.
        int step = Math.max(1, Math.round(7 * sx));
        int[] xs = new int[5];
        int n = 0;
        for (int i = 0; i < 5; i++) {
            int x = fromLeft ? src - i * step : src + i * step;
            if (x < 0 || x >= w) break;
            xs[n++] = x;
        }
        int[][] cols = new int[n][height];
        for (int i = 0; i < n; i++) shot.getPixels(cols[i], 0, 1, xs[i], t, 1, height);
        int[] row = new int[width];
        int[] a = new int[n], r = new int[n], g = new int[n], b = new int[n];
        for (int y = 0; y < height; y++) {
            for (int i = 0; i < n; i++) {
                int p = cols[i][y];
                a[i] = p >>> 24;
                r[i] = (p >> 16) & 255;
                g[i] = (p >> 8) & 255;
                b[i] = p & 255;
            }
            int c = (median(a, n) << 24) | (median(r, n) << 16) | (median(g, n) << 8) | median(b, n);
            java.util.Arrays.fill(row, c);
            shot.setPixels(row, 0, width, l, t + y, width, 1);
        }
    }

    private static int median(int[] v, int n) {
        int[] c = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(c);
        return c[n / 2];
    }
}
