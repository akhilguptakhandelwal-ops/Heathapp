package com.akhil.framecast;

/**
 * Decides what a finger did in mouse mode (plain Java so it can be unit tested).
 *
 * <ul>
 *   <li>Quick tap (short, no movement) = click where the finger was.</li>
 *   <li>Drag, or press-and-hold = only move the cursor, never click.</li>
 *   <li>Two fingers up / down = scroll.</li>
 * </ul>
 */
final class MouseGesture {
    static final int NONE = 0, CLICK = 1, SCROLL = 2;
    static final long TAP_MS = 280;

    private final float slop;
    private float downX, downY, startY, lastY;
    private long downT;
    private boolean moved, multi, done;
    private float dy;

    MouseGesture(float slopPx) {
        slop = slopPx;
    }

    void down(long t, float x, float y) {
        downT = t;
        downX = x;
        downY = y;
        moved = multi = done = false;
        dy = 0;
    }

    void move(float x, float y) {
        if (!multi && Math.hypot(x - downX, y - downY) > slop) moved = true;
    }

    /** A second finger touched; {@code avgY} is the middle of both fingers. */
    void secondFinger(float avgY) {
        if (done) return;
        multi = true;
        startY = lastY = avgY;
    }

    void twoFingerMove(float avgY) {
        if (multi && !done) lastY = avgY;
    }

    boolean isMulti() {
        return multi;
    }

    float liveScroll() {
        return multi && !done ? lastY - startY : 0;
    }

    /** One of two fingers lifted. Returns SCROLL once if they travelled far enough. */
    int fingerLifted() {
        if (!multi || done) return NONE;
        done = true;
        dy = lastY - startY;
        return Math.abs(dy) > slop ? SCROLL : NONE;
    }

    /** Last finger lifted. */
    int up(long t, float x, float y) {
        move(x, y);
        if (multi || done) return NONE;
        if (!moved && t - downT <= TAP_MS) return CLICK;
        return NONE;
    }

    /** Finger travel of the finished two-finger scroll (positive = fingers moved down). */
    float scrollDy() {
        return dy;
    }
}
