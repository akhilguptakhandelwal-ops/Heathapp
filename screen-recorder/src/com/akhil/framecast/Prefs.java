package com.akhil.framecast;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices for the recording, kept in SharedPreferences. */
public final class Prefs {
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

    public int shape, quality, phone, background;
    public boolean mic;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("framecast", Context.MODE_PRIVATE);
        shape = sp.getInt("shape", 0);
        quality = sp.getInt("quality", 1);
        phone = sp.getInt("phone", 0);
        background = sp.getInt("background", 0);
        mic = sp.getBoolean("mic", true);
    }

    public void save() {
        sp.edit().putInt("shape", shape).putInt("quality", quality).putInt("phone", phone)
                .putInt("background", background).putBoolean("mic", mic).apply();
    }

    /** Output video size as {width, height}. */
    public int[] outputSize(boolean small) {
        boolean hd = quality == 1 && !small;
        int a = hd ? 1920 : 1280, b = hd ? 1080 : 720;
        return shape == 0 ? new int[]{b, a} : new int[]{a, b};
    }

    public static void setLast(Context c, String uri, String name, long ms) {
        c.getSharedPreferences("framecast", Context.MODE_PRIVATE).edit()
                .putString("last_uri", uri).putString("last_name", name).putLong("last_ms", ms).apply();
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
