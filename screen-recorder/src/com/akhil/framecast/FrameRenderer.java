package com.akhil.framecast;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Draws one video frame: background, a phone body, and the captured screen
 * inside the phone. The static parts are painted once into {@link #base}.
 */
public final class FrameRenderer {
    public final int outW, outH;
    private final int[] rim, bg;

    /** Size the screen should be captured at (fits the phone screen exactly). */
    public int capW, capH;
    private final RectF screen = new RectF();
    private float screenRadius;
    private float camX, camY, camR;
    private Bitmap base;

    private final Paint shotPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final android.graphics.Rect srcRect = new android.graphics.Rect();
    private final android.graphics.Path screenClip = new android.graphics.Path();

    /** Things drawn on top of the screen image, in real screen pixels. */
    public interface Marks {
        void draw(Canvas c);
    }

    public FrameRenderer(int outW, int outH, int phone, int background) {
        this.outW = outW;
        this.outH = outH;
        this.rim = Prefs.PHONE_COLORS[clamp(phone, Prefs.PHONE_COLORS.length)];
        this.bg = Prefs.BG_COLORS[clamp(background, Prefs.BG_COLORS.length)];
        black.setColor(0xFF050505);
        lens.setColor(0xFF1B2230);
        lensRing.setStyle(Paint.Style.STROKE);
        lensRing.setColor(0xFF2A2A2C);
    }

    private static int clamp(int i, int n) {
        return i < 0 || i >= n ? 0 : i;
    }

    /** Lays the phone out for a screen with the given width / height ratio. */
    public void layout(float aspect) {
        final float k = 0.045f;                 // bezel, relative to the short screen side
        float mn = Math.min(aspect, 1f);
        float availW = outW * 0.86f, availH = outH * 0.88f;
        float t = Math.min(availW / (aspect + 2 * k * mn), availH / (1 + 2 * k * mn));
        capW = even(aspect * t);
        capH = even(t);
        float sW = capW, sH = capH;
        float b = k * Math.min(sW, sH);
        float cx = outW / 2f, cy = outH / 2f;
        screen.set(cx - sW / 2, cy - sH / 2, cx + sW / 2, cy + sH / 2);
        RectF body = new RectF(screen.left - b, screen.top - b, screen.right + b, screen.bottom + b);
        float bodyRadius = 0.135f * Math.min(body.width(), body.height());
        screenRadius = Math.max(4, bodyRadius - b * 0.95f);
        screenClip.reset();
        screenClip.addRoundRect(screen, screenRadius, screenRadius, android.graphics.Path.Direction.CW);
        boolean portrait = sH >= sW;
        camR = 0.022f * Math.min(sW, sH);
        if (portrait) {
            camX = cx;
            camY = screen.top + camR * 2.6f;
        } else {
            camX = screen.left + camR * 2.6f;
            camY = cy;
        }
        lensRing.setStrokeWidth(Math.max(1f, camR * 0.35f));
        if (base != null) base.recycle();
        base = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        paintBase(new Canvas(base), body, bodyRadius, b, portrait);
    }

    private static int even(float v) {
        int i = Math.round(v);
        return Math.max(2, i - (i & 1));
    }

    private void paintBase(Canvas c, RectF body, float radius, float b, boolean portrait) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Background with a soft light spot behind the phone.
        p.setShader(new LinearGradient(0, 0, 0, outH, bg[0], bg[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, outW, outH, p);
        p.setShader(new RadialGradient(outW / 2f, outH * 0.42f, Math.max(outW, outH) * 0.55f,
                0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, outW, outH, p);
        p.setShader(null);

        // Shadow.
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x8C000000);
        sh.setMaskFilter(new BlurMaskFilter(b * 2.4f, BlurMaskFilter.Blur.NORMAL));
        RectF sr = new RectF(body);
        sr.offset(0, b * 1.1f);
        c.drawRoundRect(sr, radius, radius, sh);

        // Side buttons (drawn first, the body covers their inner half).
        Paint btn = new Paint(Paint.ANTI_ALIAS_FLAG);
        btn.setColor(rim[1]);
        float bw = b * 0.30f, br = bw;
        if (portrait) {
            float h = body.height();
            c.drawRoundRect(new RectF(body.right - bw, body.top + h * 0.27f, body.right + bw, body.top + h * 0.37f), br, br, btn);
            c.drawRoundRect(new RectF(body.left - bw, body.top + h * 0.20f, body.left + bw, body.top + h * 0.27f), br, br, btn);
            c.drawRoundRect(new RectF(body.left - bw, body.top + h * 0.29f, body.left + bw, body.top + h * 0.36f), br, br, btn);
        } else {
            float w = body.width();
            c.drawRoundRect(new RectF(body.left + w * 0.63f, body.top - bw, body.left + w * 0.73f, body.top + bw), br, br, btn);
            c.drawRoundRect(new RectF(body.left + w * 0.73f, body.bottom - bw, body.left + w * 0.80f, body.bottom + bw), br, br, btn);
            c.drawRoundRect(new RectF(body.left + w * 0.64f, body.bottom - bw, body.left + w * 0.71f, body.bottom + bw), br, br, btn);
        }

        // Metal rim.
        p.setShader(new LinearGradient(body.left, body.top, body.right, body.bottom,
                new int[]{rim[0], rim[1], rim[0], rim[1]}, new float[]{0f, 0.45f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRoundRect(body, radius, radius, p);
        p.setShader(null);

        // Glass bezel.
        RectF glass = new RectF(body);
        float inset = b * 0.17f;
        glass.inset(inset, inset);
        p.setColor(0xFF0A0A0B);
        c.drawRoundRect(glass, radius - inset, radius - inset, p);

        // A faint reflection on the glass edge.
        Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
        hi.setStyle(Paint.Style.STROKE);
        hi.setStrokeWidth(Math.max(1f, b * 0.06f));
        hi.setColor(0x26FFFFFF);
        c.drawRoundRect(glass, radius - inset, radius - inset, hi);

        // Cut a see-through hole where the screen goes; the screen picture is
        // drawn underneath, so the frame's rounded corners stay smooth.
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        c.drawRoundRect(screen, screenRadius, screenRadius, hole);
    }

    /**
     * Draws the frame. {@code shot} may be wider than {@link #capW} (row padding);
     * only its top-left capW x capH part is shown.
     */
    public void draw(Canvas c, Bitmap shot, int shotW, int shotH) {
        draw(c, shot, shotW, shotH, null, 0, 0);
    }

    /**
     * Same, then {@code marks} (arrows, boxes, pen lines) on top of the screen.
     * {@code screenW} x {@code screenH} is the real screen size the marks use.
     */
    public void draw(Canvas c, Bitmap shot, int shotW, int shotH, Marks marks, float screenW, float screenH) {
        // 1. black screen, 2. the captured picture, 3. the frame on top (it has a hole for the screen).
        c.drawRect(screen, black);
        if (shot != null && !shot.isRecycled() && shotW > 0 && shotH > 0) {
            srcRect.set(0, 0, Math.min(shotW, shot.getWidth()), Math.min(shotH, shot.getHeight()));
            c.drawBitmap(shot, srcRect, screen, shotPaint);
        }
        c.drawBitmap(base, 0, 0, null);
        if (marks != null && screenW > 0 && screenH > 0) {
            c.save();
            c.clipPath(screenClip);
            c.translate(screen.left, screen.top);
            c.scale(screen.width() / screenW, screen.height() / screenH);
            marks.draw(c);
            c.restore();
        }
        // Punch-hole camera.
        c.drawCircle(camX, camY, camR, black);
        c.drawCircle(camX, camY, camR * 0.55f, lens);
        c.drawCircle(camX, camY, camR, lensRing);
    }

    public RectF screenRect() {
        return screen;
    }

    public void release() {
        if (base != null) base.recycle();
        base = null;
    }
}
