package com.akhil.framecast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/** Shows how the video will look with the chosen phone and background. */
final class PreviewView extends View {
    private final Prefs prefs;
    private Bitmap frame;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    PreviewView(Context c, Prefs p) {
        super(c);
        prefs = p;
    }

    void refresh() {
        int[] out = prefs.outputSize(true);
        int w = out[0] / 2, h = out[1] / 2;   // half of 720p is plenty for a preview
        FrameRenderer fr = new FrameRenderer(w, h, prefs.phone, prefs.background);
        Point real = screenSize();
        fr.layout(real.x / (float) real.y);
        Bitmap shot = fakeScreen(fr.capW, fr.capH);
        if (frame != null) frame.recycle();
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        fr.draw(new Canvas(frame), shot, fr.capW, fr.capH);
        fr.release();
        shot.recycle();
        invalidate();
    }

    private Point screenSize() {
        Point p = new Point();
        try {
            Display d = ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            d.getRealSize(p);
        } catch (Throwable ignored) {
        }
        if (p.x <= 0 || p.y <= 0) p.set(1080, 2400);
        if (p.x > p.y) p.set(p.y, p.x);
        return p;
    }

    /** A pretend app screen so the preview is not empty. */
    static Bitmap fakeScreen(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFFF6EFE4, 0xFFEADFCF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        float u = w / 20f;
        p.setColor(0xFF2E2823);
        c.drawRoundRect(new RectF(u, u * 2.6f, u * 9, u * 3.6f), u / 2, u / 2, p);
        p.setColor(0xFFB9AC99);
        c.drawRoundRect(new RectF(u, u * 4.3f, u * 14, u * 4.9f), u / 3, u / 3, p);
        int[] cards = {0xFFD9A441, 0xFF8FAE7E, 0xFFC27460};
        float top = u * 6.2f;
        for (int i = 0; i < 3; i++) {
            RectF r = new RectF(u, top, w - u, top + u * 5.2f);
            p.setColor(0xFFFFFBF4);
            c.drawRoundRect(r, u, u, p);
            p.setColor(cards[i]);
            c.drawRoundRect(new RectF(r.left + u * 0.7f, r.top + u * 0.7f, r.left + u * 4.6f, r.bottom - u * 0.7f), u * 0.7f, u * 0.7f, p);
            p.setColor(0xFF3A332D);
            c.drawRoundRect(new RectF(r.left + u * 5.6f, r.top + u * 1.2f, r.right - u * 2, r.top + u * 1.9f), u / 3, u / 3, p);
            p.setColor(0xFFB9AC99);
            c.drawRoundRect(new RectF(r.left + u * 5.6f, r.top + u * 2.6f, r.right - u * 4, r.top + u * 3.2f), u / 3, u / 3, p);
            top += u * 6;
            if (top > h - u * 6) break;
        }
        // Sample arrow + cursor, like a real recording.
        Paint a = new Paint(Paint.ANTI_ALIAS_FLAG);
        a.setColor(Ui.INK[0]);
        a.setStrokeWidth(u * 0.45f);
        a.setStrokeCap(Paint.Cap.ROUND);
        float ax1 = u * 15, ay1 = u * 21, ax2 = u * 10.5f, ay2 = u * 15.6f;
        c.drawLine(ax1, ay1, ax2 + u * 0.9f, ay2 + u * 1.1f, a);
        Path head = new Path();
        head.moveTo(ax2, ay2);
        head.lineTo(ax2 + u * 0.6f, ay2 + u * 2.3f);
        head.lineTo(ax2 + u * 2.2f, ay2 + u * 0.9f);
        head.close();
        c.drawPath(head, a);
        Path cur = new Path();
        Icons.cursorPath(cur, u * 8.2f, u * 13.6f, u * 0.14f);
        Paint cf = new Paint(Paint.ANTI_ALIAS_FLAG);
        cf.setColor(0xFFF8F4EC);
        c.drawPath(cur, cf);
        cf.setStyle(Paint.Style.STROKE);
        cf.setStrokeWidth(u * 0.12f);
        cf.setColor(Ui.INK_DARK);
        c.drawPath(cur, cf);
        return b;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (frame == null) return;
        float vw = getWidth(), vh = getHeight();
        float s = Math.min(vw / frame.getWidth(), vh / frame.getHeight());
        float w = frame.getWidth() * s, h = frame.getHeight() * s;
        dst.set((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2);
        c.save();
        Path clip = new Path();
        clip.addRoundRect(dst, 14 * getResources().getDisplayMetrics().density,
                14 * getResources().getDisplayMetrics().density, Path.Direction.CW);
        c.clipPath(clip);
        c.drawBitmap(frame, null, dst, paint);
        c.restore();
    }
}
