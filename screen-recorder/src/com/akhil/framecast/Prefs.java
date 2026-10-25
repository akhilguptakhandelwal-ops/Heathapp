package com.akhil.framecast;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices for the recording, kept in SharedPreferences. */
public final class Prefs {
    public static final String[] STYLES = {"Phone frame", "Normal"};
    public static final String[] SHAPES = {"9:16 Shorts", "16:9 YouTube"};
    public static final String[] QUALITIES = {"720p", "1080p"};
    public static final String[] PHONES = {"Graphite", "Sand", "Olive", "Pearl"};
    public static final String[] BACKGROUNDS = {"Charcoal", "Cream", "Sage", "Terracotta", "Plum"};

    /** Phone rim colours: light edge, dark edge. */
    static final int[][] PHONE_COLORS = {
            {0xFF6E6C6A, 0xFF2B2927},
            {0xFFE6D6B8, 0xFF9A8460},
            {0xFF8C9170, 0xFF484C36},
            {0xFFF3EFE8, 0xFFB7AFA3},
    };
    /** Background gradients: top, bottom. */
    static final int[][] BG_COLORS = {
            {0xFF2C2622, 0xFF0F0D0B},
            {0xFFF4ECDF, 0xFFD8C8AE},
            {0xFF718068, 0xFF2F392C},
            {0xFFC27460, 0xFF642E21},
            {0xFF4C3548, 0xFF1C131B},
    };

    public int style, shape, quality, phone, background;
    public boolean mic;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("framecast", Context.MODE_PRIVATE);
        style = sp.getInt("style", 0);
        shape = sp.getInt("shape", 0);
        quality = sp.getInt("quality", 1);
        phone = sp.getInt("phone", 0);
        background = sp.getInt("background", 0);
        mic = sp.getBoolean("mic", true);
    }

    public void save() {
        sp.edit().putInt("style", style).putInt("shape", shape).putInt("quality", quality).putInt("phone", phone)
                .putInt("background", background).putBoolean("mic", mic).apply();
    }

    /** Output video size as {width, height}. */
    public int[] outputSize(boolean small) {
        boolean hd = quality == 1 && !small;
        int a = hd ? 1920 : 1280, b = hd ? 1080 : 720;
        return shape == 0 ? new int[]{b, a} : new int[]{a, b};
    }

    public boolean plain() {
        return style == 1;
    }

    /**
     * Video sizes to try, best first. {@code aspect} is screen width / height.
     * Normal style follows the phone's own shape; some encoders refuse a long
     * side over 1920, so capped and smaller sizes follow.
     */
    public int[][] outputCandidates(float aspect) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        if (!plain()) {
            out.add(outputSize(false));
            out.add(outputSize(true));
        } else {
            boolean portrait = aspect <= 1f;
            float r = portrait ? aspect : 1f / aspect;          // short / long
            int[] shorts = quality == 1 ? new int[]{1080, 720, 540} : new int[]{720, 540};
            for (int sh : shorts) {
                int lg = round16(sh / r);
                add(out, sh, lg, portrait);
                if (lg > 1920) add(out, round16(1920 * r), 1920, portrait);
            }
        }
        return out.toArray(new int[0][]);
    }

    private static void add(java.util.ArrayList<int[]> list, int sh, int lg, boolean portrait) {
        int[] v = portrait ? new int[]{sh, lg} : new int[]{lg, sh};
        for (int[] e : list) if (e[0] == v[0] && e[1] == v[1]) return;
        list.add(v);
    }

    private static int round16(float v) {
        return Math.max(16, Math.round(v / 16f) * 16);
    }

    public static void setLast(Context c, String uri, String name, long ms) {
        c.getSharedPreferences("framecast", Context.MODE_PRIVATE).edit()
                .putString("last_uri", uri).putString("last_name", name).putLong("last_ms", ms).apply();
    }

    /** A finished recording waiting in app storage for Save / Trim / Discard. */
    public static void setPending(Context c, String path, long ms) {
        c.getSharedPreferences("framecast", Context.MODE_PRIVATE).edit()
                .putString("pending_path", path).putLong("pending_ms", ms).commit();
    }

    public static String pendingPath(Context c) {
        return c.getSharedPreferences("framecast", Context.MODE_PRIVATE).getString("pending_path", null);
    }

    public static long pendingMs(Context c) {
        return c.getSharedPreferences("framecast", Context.MODE_PRIVATE).getLong("pending_ms", 0);
    }

    public static String lastUri(Context c) {
        return c.getSharedPreferences("framecast", Context.MODE_PRIVATE).getString("last_uri", null);
    }

    public static String lastName(Context c) {
        return c.getSharedPreferences("framecast", Context.MODE_PRIVATE).getString("last_name", null);
    }

    public static long lastMs(Context c) {
        return c.getSharedPreferences("framecast", Context.MODE_PRIVATE).getLong("last_ms", 0);
    }
}
