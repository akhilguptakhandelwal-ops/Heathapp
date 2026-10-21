package com.akhil.framecast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;

/**
 * Full-screen, touch-through layer that shows arrows, boxes, the cursor and
 * click ripples. Everything is kept in screen coordinates.
 */
final class InkView extends View {
    static final int ARROW = 1, BOX = 2;

    static final class Shape {
        int type, color;
        float x1, y1, x2, y2;
    }

    private static final class Ripple {
        float x, y;
        long start;
    }

    private final ArrayList<Shape> shapes = new ArrayList<>();
    private Shape current;
    private final ArrayList<Ripple> ripples = new ArrayList<>();

    private float cx = -1, cy = -1;
    private boolean holding, scrolling;
    private float scrollDy;

    private final float dp;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];

    InkView(Context c) {
        super(c);
        dp = Ui.dp(c, 1);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(4.5f * dp);
        line.setShadowLayer(3 * dp, 0, 1 * dp, 0x66000000);
        head.setStyle(Paint.Style.FILL);
        head.setShadowLayer(3 * dp, 0, 1 * dp, 0x66000000);
        cursorFill.setColor(0xFFF8F4EC);
        cursorFill.setShadowLayer(3 * dp, 0, 1.5f * dp, 0x80000000);
        cursorEdge.setStyle(Paint.Style.STROKE);
        cursorEdge.setStrokeJoin(Paint.Join.ROUND);
        cursorEdge.setStrokeWidth(1.6f * dp);
        cursorEdge.setColor(Ui.INK_DARK);
        halo.setColor(0x40D9A441);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * dp);
        ring.setColor(Ui.AMBER);
    }

    // ---- cursor

    void placeCursorIfUnset(float x, float y) {
        if (cx < 0) moveCursor(x, y);
    }

    void moveCursor(float x, float y) {
        cx = x;
        cy = y;
        invalidate();
    }

    float cursorX() {
        return cx;
    }

    float cursorY() {
        return cy;
    }

    /** Finger is down in mouse mode: show a soft halo, no click happens. */
    void setHolding(boolean h) {
        holding = h;
        invalidate();
    }

    void setScrolling(boolean s, float dy) {
        scrolling = s;
        scrollDy = dy;
        invalidate();
    }

    void ripple(float x, float y) {
        Ripple r = new Ripple();
        r.x = x;
        r.y = y;
        r.start = SystemClock.uptimeMillis();
        ripples.add(r);
        invalidate();
    }

    // ---- shapes

    void begin(int type, int color, float x, float y) {
        current = new Shape();
        current.type = type;
        current.color = color;
        current.x1 = current.x2 = x;
        current.y1 = current.y2 = y;
        invalidate();
    }

    void drag(float x, float y) {
        if (current == null) return;
        current.x2 = x;
        current.y2 = y;
        invalidate();
    }

    void end() {
        if (current == null) return;
        float dx = current.x2 - current.x1, dy = current.y2 - current.y1;
        if (Math.hypot(dx, dy) > 14 * dp) shapes.add(current);
        current = null;
        invalidate();
    }

    void cancel() {
        current = null;
        invalidate();
    }

    void undo() {
        if (!shapes.isEmpty()) shapes.remove(shapes.size() - 1);
        invalidate();
    }

    void clear() {
        shapes.clear();
        current = null;
        invalidate();
    }

    void reset() {
        clear();
        ripples.clear();
        cx = cy = -1;
        holding = scrolling = false;
    }

    // ---- drawing

    @Override
    protected void onDraw(Canvas c) {
        getLocationOnScreen(loc);
        c.save();
        c.translate(-loc[0], -loc[1]);
        for (int i = 0; i < shapes.size(); i++) drawShape(c, shapes.get(i));
        if (current != null) drawShape(c, current);
        boolean animating = drawRipples(c);
        drawCursor(c);
        c.restore();
        if (animating) postInvalidateOnAnimation();
    }

    private void drawShape(Canvas c, Shape s) {
        line.setColor(s.color);
        head.setColor(s.color);
        if (s.type == BOX) {
            rect.set(Math.min(s.x1, s.x2), Math.min(s.y1, s.y2), Math.max(s.x1, s.x2), Math.max(s.y1, s.y2));
            c.drawRoundRect(rect, 7 * dp, 7 * dp, line);
            return;
        }
        float dx = s.x2 - s.x1, dy = s.y2 - s.y1;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1) return;
        float ux = dx / len, uy = dy / len;
        float hl = Math.min(len * 0.6f, 20 * dp), hw = hl * 0.62f;
        float bx = s.x2 - ux * hl, by = s.y2 - uy * hl;
        c.drawLine(s.x1, s.y1, bx + ux * 2 * dp, by + uy * 2 * dp, line);
        path.reset();
        path.moveTo(s.x2, s.y2);
        path.lineTo(bx - uy * hw, by + ux * hw);
        path.lineTo(bx + uy * hw, by - ux * hw);
        path.close();
        c.drawPath(path, head);
    }

    private boolean drawRipples(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean any = false;
        for (int i = ripples.size() - 1; i >= 0; i--) {
            Ripple r = ripples.get(i);
            float t = (now - r.start) / 480f;
            if (t >= 1) {
                ripples.remove(i);
                continue;
            }
            any = true;
            ring.setAlpha((int) (255 * (1 - t)));
            c.drawCircle(r.x, r.y, (8 + 30 * t) * dp, ring);
            halo.setAlpha((int) (110 * (1 - t)));
            c.drawCircle(r.x, r.y, (6 + 14 * t) * dp, halo);
        }
        ring.setAlpha(255);
        halo.setAlpha(64);
        return any;
    }

    private void drawCursor(Canvas c) {
        if (cx < 0) return;
        if (holding || scrolling) {
            halo.setAlpha(64);
            c.drawCircle(cx, cy, 22 * dp, halo);
            ring.setPathEffect(new DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));
            ring.setAlpha(200);
            c.drawCircle(cx, cy, 22 * dp, ring);
            ring.setPathEffect(null);
            ring.setAlpha(255);
            if (scrolling) {
                float dir = scrollDy == 0 ? 0 : (scrollDy > 0 ? 1 : -1);
                if (dir != 0) {
                    head.setColor(Ui.AMBER);
                    path.reset();
                    float ty = cy + dir * 34 * dp;
                    path.moveTo(cx, ty + dir * 8 * dp);
                    path.lineTo(cx - 8 * dp, ty - dir * 2 * dp);
                    path.lineTo(cx + 8 * dp, ty - dir * 2 * dp);
                    path.close();
                    c.drawPath(path, head);
                }
            }
        }
        Icons.cursorPath(path, cx, cy, 1.45f * dp);
        c.drawPath(path, cursorFill);
        c.drawPath(path, cursorEdge);
    }
}
