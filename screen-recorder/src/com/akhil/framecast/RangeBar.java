package com.akhil.framecast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Track with a start and an end handle, for choosing the part to keep. */
final class RangeBar extends View {
    interface Listener {
        /** {@code which}: 0 = start moved, 1 = end moved. */
        void changed(float start, float end, int which);
    }

    private float start = 0f, end = 1f;      // 0..1
    private float minGap = 0.02f;
    private int dragging = -1;
    private Listener listener;
    private final float dp;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kept = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    RangeBar(Context c) {
        super(c);
        dp = Ui.dp(c, 1);
        track.setColor(0xFF3A332D);
        kept.setColor(0x66D9A441);
        handle.setColor(Ui.AMBER);
        grip.setColor(Ui.INK_DARK);
        grip.setStrokeWidth(2 * dp);
        grip.setStrokeCap(Paint.Cap.ROUND);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Smallest part you can keep, as a fraction of the video. */
    void setMinGap(float g) {
        minGap = Math.max(0.001f, Math.min(0.5f, g));
    }

    void set(float s, float e) {
        start = s;
        end = e;
        invalidate();
    }

    float start() {
        return start;
    }

    float end() {
        return end;
    }

    private float pad() {
        return 14 * dp;
    }

    private float xOf(float f) {
        return pad() + f * (getWidth() - 2 * pad());
    }

    private float fOf(float x) {
        return Math.max(0f, Math.min(1f, (x - pad()) / Math.max(1f, getWidth() - 2 * pad())));
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (56 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), top = h * 0.2f, bot = h * 0.8f;
        r.set(pad(), top, getWidth() - pad(), bot);
        c.drawRoundRect(r, 8 * dp, 8 * dp, track);
        r.set(xOf(start), top, xOf(end), bot);
        c.drawRect(r, kept);
        drawHandle(c, xOf(start), top, bot);
        drawHandle(c, xOf(end), top, bot);
    }

    private void drawHandle(Canvas c, float x, float top, float bot) {
        r.set(x - 9 * dp, top - 6 * dp, x + 9 * dp, bot + 6 * dp);
        c.drawRoundRect(r, 6 * dp, 6 * dp, handle);
        c.drawLine(x - 2.5f * dp, top + 6 * dp, x - 2.5f * dp, bot - 6 * dp, grip);
        c.drawLine(x + 2.5f * dp, top + 6 * dp, x + 2.5f * dp, bot - 6 * dp, grip);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float f = fOf(e.getX());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = Math.abs(f - start) <= Math.abs(f - end) ? 0 : 1;
                if (start == end) dragging = f < start ? 0 : 1;
                getParent().requestDisallowInterceptTouchEvent(true);
                move(f);
                return true;
            case MotionEvent.ACTION_MOVE:
                move(f);
                return true;
            default:
                dragging = -1;
                return true;
        }
    }

    private void move(float f) {
        if (dragging == 0) start = Math.max(0f, Math.min(f, end - minGap));
        else if (dragging == 1) end = Math.min(1f, Math.max(f, start + minGap));
        else return;
        invalidate();
        if (listener != null) listener.changed(start, end, dragging);
    }
}
