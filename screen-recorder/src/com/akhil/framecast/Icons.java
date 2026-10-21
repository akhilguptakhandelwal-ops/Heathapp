package com.akhil.framecast;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Small hand-drawn icons for the tool panel (no icon font, no emoji). */
final class Icons {
    static final int ARROW = 1, SQUARE = 2, MOUSE = 3, CLICK = 4, UP = 5, DOWN = 6,
            UNDO = 7, CLEAR = 8, STOP = 9, COLOR = 10, HIDE = 11, CAMERA = 12;

    private static final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();
    private static final RectF r = new RectF();

    static {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    /** Draws icon {@code type} centred at (cx, cy) inside a box of side {@code s}. */
    static void draw(Canvas c, int type, float cx, float cy, float s, int color, int extra) {
        float u = s / 24f;           // icons are designed on a 24 unit grid
        float x0 = cx - 12 * u, y0 = cy - 12 * u;
        stroke.setColor(color);
        stroke.setStrokeWidth(2.1f * u);
        fill.setColor(color);
        path.reset();
        switch (type) {
            case ARROW:
                c.drawLine(x0 + 5 * u, y0 + 19 * u, x0 + 17 * u, y0 + 7 * u, stroke);
                path.moveTo(x0 + 19.5f * u, y0 + 4.5f * u);
                path.lineTo(x0 + 18.6f * u, y0 + 12.6f * u);
                path.lineTo(x0 + 11.4f * u, y0 + 5.4f * u);
                path.close();
                c.drawPath(path, fill);
                break;
            case SQUARE:
                r.set(x0 + 4.5f * u, y0 + 5.5f * u, x0 + 19.5f * u, y0 + 18.5f * u);
                c.drawRoundRect(r, 2.2f * u, 2.2f * u, stroke);
                break;
            case MOUSE:
                cursorPath(path, x0 + 7 * u, y0 + 3.5f * u, 0.9f * u);
                c.drawPath(path, fill);
                break;
            case CLICK:
                c.drawCircle(cx, cy, 3 * u, fill);
                stroke.setStrokeWidth(1.6f * u);
                c.drawCircle(cx, cy, 7 * u, stroke);
                stroke.setAlpha(110);
                c.drawCircle(cx, cy, 10.5f * u, stroke);
                stroke.setAlpha(255);
                break;
            case UP:
                path.moveTo(x0 + 6 * u, y0 + 15 * u);
                path.lineTo(cx, y0 + 8.5f * u);
                path.lineTo(x0 + 18 * u, y0 + 15 * u);
                c.drawPath(path, stroke);
                break;
            case DOWN:
                path.moveTo(x0 + 6 * u, y0 + 9 * u);
                path.lineTo(cx, y0 + 15.5f * u);
                path.lineTo(x0 + 18 * u, y0 + 9 * u);
                c.drawPath(path, stroke);
                break;
            case UNDO:
                r.set(x0 + 6 * u, y0 + 7 * u, x0 + 19 * u, y0 + 19 * u);
                path.addArc(r, 200, 250);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(x0 + 4 * u, y0 + 6.5f * u);
                path.lineTo(x0 + 6.6f * u, y0 + 12.4f * u);
                path.lineTo(x0 + 11.6f * u, y0 + 9.2f * u);
                path.close();
                c.drawPath(path, fill);
                break;
            case CLEAR:
                c.drawLine(x0 + 5 * u, y0 + 7 * u, x0 + 19 * u, y0 + 7 * u, stroke);
                c.drawLine(x0 + 10 * u, y0 + 4.5f * u, x0 + 14 * u, y0 + 4.5f * u, stroke);
                path.moveTo(x0 + 6.8f * u, y0 + 7 * u);
                path.lineTo(x0 + 8 * u, y0 + 19.5f * u);
                path.lineTo(x0 + 16 * u, y0 + 19.5f * u);
                path.lineTo(x0 + 17.2f * u, y0 + 7 * u);
                c.drawPath(path, stroke);
                break;
            case STOP:
                r.set(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u);
                c.drawRoundRect(r, 2 * u, 2 * u, fill);
                break;
            case COLOR:
                fill.setColor(extra);
                c.drawCircle(cx, cy, 7 * u, fill);
                stroke.setStrokeWidth(1.6f * u);
                c.drawCircle(cx, cy, 9.5f * u, stroke);
                break;
            case CAMERA:
                r.set(x0 + 3.5f * u, y0 + 7 * u, x0 + 20.5f * u, y0 + 19 * u);
                c.drawRoundRect(r, 2.5f * u, 2.5f * u, stroke);
                path.moveTo(x0 + 8.5f * u, y0 + 7 * u);
                path.lineTo(x0 + 10 * u, y0 + 4.5f * u);
                path.lineTo(x0 + 14 * u, y0 + 4.5f * u);
                path.lineTo(x0 + 15.5f * u, y0 + 7 * u);
                c.drawPath(path, stroke);
                c.drawCircle(cx, y0 + 13 * u, 3.4f * u, stroke);
                break;
            case HIDE:
                path.moveTo(x0 + 9 * u, y0 + 6 * u);
                path.lineTo(x0 + 15 * u, cy);
                path.lineTo(x0 + 9 * u, y0 + 18 * u);
                c.drawPath(path, stroke);
                break;
            default:
                break;
        }
    }

    /** Classic mouse pointer with its tip at (x, y). One unit = {@code k} px. */
    static void cursorPath(Path p, float x, float y, float k) {
        p.reset();
        p.moveTo(x, y);
        p.lineTo(x, y + 17 * k);
        p.lineTo(x + 4.1f * k, y + 13.3f * k);
        p.lineTo(x + 6.9f * k, y + 19.6f * k);
        p.lineTo(x + 9.7f * k, y + 18.4f * k);
        p.lineTo(x + 6.9f * k, y + 12.2f * k);
        p.lineTo(x + 12.3f * k, y + 12.2f * k);
        p.close();
    }
}
