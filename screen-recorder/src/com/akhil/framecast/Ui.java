package com.akhil.framecast;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

/** Shared colours and the Manrope font. */
public final class Ui {
    // Warm, muted palette (no stock blues).
    public static final int BG = 0xFF15120F;
    public static final int CARD = 0xFF221E1A;
    public static final int CARD_LINE = 0xFF342E28;
    public static final int TEXT = 0xFFF1EADF;
    public static final int MUTED = 0xFFA89D8E;
    public static final int AMBER = 0xFFD9A441;
    public static final int VERMILION = 0xFFE4572E;
    public static final int SAGE = 0xFF8FAE7E;
    public static final int INK_DARK = 0xFF1A1714;

    /** Colours for arrows, boxes and pen. */
    public static final int[] INK = {0xFFE4572E, 0xFFF2B33D, 0xFF8FCB9B, 0xFFF4EDE1, 0xFF22262B};
    public static final int CHALK = 3;      // index of the off-white ink (black board)
    public static final int DARK_INK = 4;   // index of the dark ink (white board)
    public static final int WHITE_BOARD = 0xFFFBFAF6;
    public static final int BLACK_BOARD = 0xFF1F2420;

    private static Typeface regular, bold, heavy;

    public static synchronized Typeface font(Context c, int weight) {
        if (regular == null) {
            regular = load(c, 450);
            bold = load(c, 700);
            heavy = load(c, 800);
        }
        return weight >= 800 ? heavy : weight >= 600 ? bold : regular;
    }

    private static Typeface load(Context c, int wght) {
        try {
            return new Typeface.Builder(c.getAssets(), "fonts/Manrope.ttf")
                    .setFontVariationSettings("'wght' " + wght).build();
        } catch (Exception e) {
            return wght >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        }
    }

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    public static GradientDrawable round(int color, float radius, int stroke, int strokeColor) {
        GradientDrawable g = round(color, radius);
        g.setStroke(stroke, strokeColor);
        return g;
    }
}
