package com.akhil.framecast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;

/**
 * Full-screen drawing layer used in Freeze mode: shows the frozen screen
 * picture and the arrows / boxes / pen lines drawn on it. Everything is kept
 * in real screen coordinates.
 */
final class InkView extends View {
    static final int ARROW = 1, BOX = 2, PEN = 3;

    static final class Shape {
        int type, color;
        float x1, y1, x2, y2;
        Path path;
    }

    private final ArrayList<Shape> shapes = new ArrayList<>();
    private Shape current;

    private Bitmap frozen;
    private int boardColor;          // 0 = none, else a solid board (white / black)
    private int screenW, screenH;
    private final Rect frozenDst = new Rect();

    private final float dp;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
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
    }

    /** Solid board behind the marks (0 = none). */
    synchronized void setBoard(int color) {
        boardColor = color;
        invalidate();
    }

    /** The picture shown behind the marks (null = see-through). */
    synchronized void setFrozen(Bitmap b, int w, int h) {
        frozen = b;
        screenW = w;
        screenH = h;
        invalidate();
    }

    // ---- shapes

    synchronized void begin(int type, int color, float x, float y) {
        current = new Shape();
        current.type = type;
        current.color = color;
        current.x1 = current.x2 = x;
        current.y1 = current.y2 = y;
        if (type == PEN) {
            current.path = new Path();
            current.path.moveTo(x, y);
        }
        invalidate();
    }

    synchronized void drag(float x, float y) {
        if (current == null) return;
        if (current.type == PEN) {
            // Smooth the line through midpoints.
            current.path.quadTo(current.x2, current.y2, (current.x2 + x) / 2, (current.y2 + y) / 2);
        }
        current.x2 = x;
        current.y2 = y;
        invalidate();
    }

    synchronized void end() {
        if (current == null) return;
        if (current.type == PEN) {
            current.path.lineTo(current.x2, current.y2);
            shapes.add(current);
        } else {
            float dx = current.x2 - current.x1, dy = current.y2 - current.y1;
            if (Math.hypot(dx, dy) > 14 * dp) shapes.add(current);
        }
        current = null;
        invalidate();
    }

    synchronized void cancel() {
        current = null;
        invalidate();
    }

    synchronized void undo() {
        if (!shapes.isEmpty()) shapes.remove(shapes.size() - 1);
        invalidate();
    }

    synchronized void clear() {
        shapes.clear();
        current = null;
        invalidate();
    }

    synchronized int count() {
        return shapes.size();
    }

    // ---- drawing

    @Override
    protected void onDraw(Canvas c) {
        getLocationOnScreen(loc);
        c.save();
        c.translate(-loc[0], -loc[1]);
        synchronized (this) {
            if (boardColor != 0) c.drawColor(boardColor);
            else if (frozen != null && !frozen.isRecycled() && screenW > 0) {
                frozenDst.set(0, 0, screenW, screenH);
                c.drawBitmap(frozen, null, frozenDst, picture);
            }
        }
        drawMarks(c);
        c.restore();
    }

    /**
     * Draws the marks in screen coordinates. Also called from the recorder's
     * render thread to paint them straight into the video.
     */
    synchronized void drawMarks(Canvas c) {
        for (int i = 0; i < shapes.size(); i++) drawShape(c, shapes.get(i));
        if (current != null) drawShape(c, current);
    }

    private void drawShape(Canvas c, Shape s) {
        line.setColor(s.color);
        head.setColor(s.color);
        if (s.type == PEN) {
            c.drawPath(s.path, line);
            return;
        }
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
}
