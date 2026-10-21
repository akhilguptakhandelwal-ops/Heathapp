package com.akhil.framecast;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The two overlay windows shown while recording:
 * <ol>
 *   <li>ink   – full screen; draws arrows, boxes and the cursor, and catches
 *       the finger only while a tool is selected</li>
 *   <li>panel – the button column on the right edge</li>
 * </ol>
 * With the accessibility service ({@code a11y != null}) mouse taps and scrolls
 * really reach the app below. Without it the windows are ordinary
 * "display over other apps" windows and the cursor is for showing only.
 */
final class OverlayController {
    static final int NONE = 0, ARROW = 1, SQUARE = 2, MOUSE = 3;

    private final Context svc;
    private final AccessibilityService a11y;
    private final int windowType;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float dp;

    private volatile InkView ink;
    private View touch;          // same window as ink
    private LinearLayout panel, tools, mouseGroup;
    private TextView timer;
    private Btn bArrow, bSquare, bMouse, bColor;
    private WindowManager.LayoutParams touchLp, panelLp;

    private boolean shown, collapsed, gestureBusy, touchable;
    private int mode = NONE;
    private int colorIdx;
    private final MouseGesture mouse;
    private final int[] loc = new int[2];

    OverlayController(Context s, AccessibilityService a11y) {
        svc = s;
        this.a11y = a11y;
        windowType = a11y != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        dp = Ui.dp(s, 1);
        mouse = new MouseGesture(ViewConfiguration.get(s).getScaledTouchSlop());
    }

    // ------------------------------------------------------------ windows

    private WindowManager.LayoutParams fullScreen(boolean canTouch) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | (canTouch ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        if (a11y == null && Build.VERSION.SDK_INT >= 31) {
            // Android 12+ only lets taps pass through another app's window when
            // it is at most 80% opaque.
            lp.alpha = 0.8f;
        }
        return lp;
    }

    void show() {
        if (shown) return;
        try {
            build();
            wm.addView(ink, touchLp);
            wm.addView(panel, panelLp);
            shown = true;
            h.post(trackPanel);
            touchable = false;
            setMode(NONE);
            DisplayMetrics m = svc.getResources().getDisplayMetrics();
            ink.placeCursorIfUnset(m.widthPixels / 2f, m.heightPixels / 2f);
        } catch (Throwable t) {
            hide();
        }
    }

    void hide() {
        h.removeCallbacksAndMessages(null);
        panelRect = null;
        removeQuietly(panel);
        removeQuietly(ink);
        panel = null;
        touch = null;
        ink = null;
        shown = false;
        gestureBusy = false;
        mode = NONE;
        collapsed = false;
    }

    private void removeQuietly(View v) {
        if (v == null) return;
        try {
            wm.removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------- recording helpers

    /** Panel position on screen {left, top, right, bottom}, or null. Read by the render thread. */
    private volatile int[] panelRect;

    int[] panelRect() {
        return panelRect;
    }

    /** Keeps {@link #panelRect} current (it moves when dragged, folded or the phone turns). */
    private final Runnable trackPanel = new Runnable() {
        @Override
        public void run() {
            LinearLayout p = panel;
            if (!shown || p == null) return;
            if (p.isAttachedToWindow() && p.getWidth() > 0) {
                int[] l = new int[2];
                p.getLocationOnScreen(l);
                panelRect = new int[]{l[0], l[1], l[0] + p.getWidth(), l[1] + p.getHeight()};
            }
            h.postDelayed(this, 60);
        }
    };

    /** Draws the marks and cursor (screen coordinates) onto a video frame. Any thread. */
    void drawMarks(Canvas c) {
        InkView i = ink;
        if (i != null) i.drawContent(c);
    }

    boolean canClick() {
        return a11y != null;
    }

    /** Ripple in the middle of the screen, so a screenshot is noticed. */
    void flash() {
        if (ink == null) return;
        ink.ripple(svc.getResources().getDisplayMetrics().widthPixels / 2f,
                svc.getResources().getDisplayMetrics().heightPixels / 2f);
    }

    void setTimer(String text, boolean live) {
        if (timer == null) return;
        timer.setText(text);
        timer.setTextColor(live ? Ui.TEXT : Ui.AMBER);
    }

    private void setTouchable(boolean on) {
        if (!shown || touch == null || touchable == on) return;
        touchable = on;
        if (on) touchLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else touchLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.updateViewLayout(touch, touchLp);
        } catch (Throwable ignored) {
        }
    }

    private void setMode(int m) {
        mode = m;
        if (!shown) return;
        bArrow.setOn(m == ARROW);
        bSquare.setOn(m == SQUARE);
        bMouse.setOn(m == MOUSE);
        mouseGroup.setVisibility(m == MOUSE && !collapsed && canClick() ? View.VISIBLE : View.GONE);
        if (m == MOUSE) {
            DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            ink.placeCursorIfUnset(dm.widthPixels / 2f, dm.heightPixels / 2f);
        }
        if (!gestureBusy) setTouchable(m != NONE);
    }

    private void toggle(int m) {
        setMode(mode == m ? NONE : m);
    }

    // -------------------------------------------------------------- build

    private void build() {
        Context c = svc;
        ink = new InkView(c);
        touch = ink;
        touch.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return onTouchLayer(e);
            }
        });
        touchLp = fullScreen(false);

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (6 * dp);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(Ui.round(0xEB1C1916, 26 * dp, (int) Math.max(1, dp), 0x33F1EADF));

        timer = new TextView(c);
        timer.setTypeface(Ui.font(c, 700));
        timer.setTextSize(12);
        timer.setTextColor(Ui.AMBER);
        timer.setGravity(Gravity.CENTER);
        timer.setText("3");
        timer.setCompoundDrawablePadding((int) (4 * dp));
        timer.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        timer.setMinWidth((int) (46 * dp));
        timer.setOnTouchListener(new PanelDrag());
        panel.addView(timer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tools = new LinearLayout(c);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(tools);

        bArrow = addBtn(tools, Icons.ARROW, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle(ARROW);
            }
        });
        bSquare = addBtn(tools, Icons.SQUARE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle(SQUARE);
            }
        });
        bMouse = addBtn(tools, Icons.MOUSE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle(MOUSE);
            }
        });

        mouseGroup = new LinearLayout(c);
        mouseGroup.setOrientation(LinearLayout.VERTICAL);
        mouseGroup.setGravity(Gravity.CENTER_HORIZONTAL);
        mouseGroup.setBackground(Ui.round(0x22D9A441, 20 * dp));
        tools.addView(mouseGroup);
        addBtn(mouseGroup, Icons.CLICK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clickAtCursor();
            }
        });
        addBtn(mouseGroup, Icons.UP, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollButton(true);
            }
        });
        addBtn(mouseGroup, Icons.DOWN, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollButton(false);
            }
        });

        addDivider(tools);
        addBtn(tools, Icons.CAMERA, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RecorderService r = RecorderService.instance;
                if (r != null) r.screenshot();
            }
        });
        bColor = addBtn(tools, Icons.COLOR, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                colorIdx = (colorIdx + 1) % Ui.INK.length;
                bColor.setExtra(Ui.INK[colorIdx]);
            }
        });
        bColor.setExtra(Ui.INK[colorIdx]);
        addBtn(tools, Icons.UNDO, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ink.undo();
            }
        });
        addBtn(tools, Icons.CLEAR, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ink.clear();
            }
        });
        addDivider(tools);
        Btn stop = addBtn(tools, Icons.STOP, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RecorderService r = RecorderService.instance;
                if (r != null) r.requestStop();
                else hide();
            }
        });
        stop.setTint(Ui.VERMILION);

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.END;
        panelLp.x = (int) (6 * dp);
        panelLp.y = (int) (svc.getResources().getDisplayMetrics().heightPixels * 0.16f);
    }

    private Btn addBtn(LinearLayout parent, int icon, View.OnClickListener l) {
        Btn b = new Btn(svc, icon);
        b.setOnClickListener(l);
        int s = (int) (44 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.topMargin = (int) (3 * dp);
        parent.addView(b, lp);
        return b;
    }

    private void addDivider(LinearLayout parent) {
        View v = new View(svc);
        v.setBackgroundColor(0x33F1EADF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (26 * dp), Math.max(1, (int) dp));
        lp.topMargin = (int) (5 * dp);
        lp.bottomMargin = (int) (2 * dp);
        parent.addView(v, lp);
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        tools.setVisibility(c ? View.GONE : View.VISIBLE);
        mouseGroup.setVisibility(mode == MOUSE && !c && canClick() ? View.VISIBLE : View.GONE);
    }

    /** Drag the timer to move the panel up / down; tap it to fold the panel. */
    private final class PanelDrag implements View.OnTouchListener {
        float startRawY;
        int startY;
        boolean dragged;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawY = e.getRawY();
                    startY = panelLp.y;
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float d = e.getRawY() - startRawY;
                    if (Math.abs(d) > 8 * dp) dragged = true;
                    if (dragged) {
                        int maxY = svc.getResources().getDisplayMetrics().heightPixels - panel.getHeight();
                        panelLp.y = Math.max(0, Math.min(Math.max(0, maxY), (int) (startY + d)));
                        try {
                            wm.updateViewLayout(panel, panelLp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged) setCollapsed(!collapsed);
                    return true;
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------ touches

    private boolean onTouchLayer(MotionEvent e) {
        if (!shown || mode == NONE) return false;
        touch.getLocationOnScreen(loc);
        int action = e.getActionMasked();
        float x = e.getX() + loc[0], y = e.getY() + loc[1];

        if (mode == ARROW || mode == SQUARE) {
            int type = mode == ARROW ? InkView.ARROW : InkView.BOX;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    ink.begin(type, Ui.INK[colorIdx], x, y);
                    break;
                case MotionEvent.ACTION_MOVE:
                    ink.drag(x, y);
                    break;
                case MotionEvent.ACTION_UP:
                    ink.drag(x, y);
                    ink.end();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    ink.cancel();
                    break;
                default:
                    break;
            }
            return true;
        }

        // Mouse mode.
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mouse.down(e.getEventTime(), x, y);
                ink.moveCursor(x, y);
                ink.setHolding(true);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    mouse.secondFinger(avgY(e) + loc[1]);
                    ink.setScrolling(true, 0);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mouse.isMulti()) {
                    if (e.getPointerCount() >= 2) mouse.twoFingerMove(avgY(e) + loc[1]);
                    ink.setScrolling(true, mouse.liveScroll());
                } else {
                    mouse.move(x, y);
                    ink.moveCursor(x, y);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (mouse.fingerLifted() == MouseGesture.SCROLL) {
                    swipe(ink.cursorX(), ink.cursorY(), mouse.scrollDy());
                }
                ink.setScrolling(false, 0);
                break;
            case MotionEvent.ACTION_UP:
                ink.setHolding(false);
                ink.setScrolling(false, 0);
                if (mouse.up(e.getEventTime(), x, y) == MouseGesture.CLICK) {
                    ink.moveCursor(x, y);
                    tap(x, y);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                ink.setHolding(false);
                ink.setScrolling(false, 0);
                break;
            default:
                break;
        }
        return true;
    }

    private static float avgY(MotionEvent e) {
        int n = Math.min(2, e.getPointerCount());
        float s = 0;
        for (int i = 0; i < n; i++) s += e.getY(i);
        return s / n;
    }

    // ----------------------------------------------------------- gestures

    private void clickAtCursor() {
        if (ink == null || ink.cursorX() < 0) return;
        tap(ink.cursorX(), ink.cursorY());
    }

    private void scrollButton(boolean up) {
        if (ink == null) return;
        DisplayMetrics m = svc.getResources().getDisplayMetrics();
        float x = ink.cursorX() >= 0 ? ink.cursorX() : m.widthPixels / 2f;
        float y = ink.cursorY() >= 0 ? ink.cursorY() : m.heightPixels / 2f;
        float d = m.heightPixels * 0.35f;
        // "Up" shows what is above: the finger moves down the screen.
        swipe(x, y, up ? d : -d);
    }

    private void tap(float x, float y) {
        if (gestureBusy) return;
        ink.ripple(x, y);
        if (a11y == null) return;   // no accessibility: the click is only shown
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        run(p, 60);
    }

    /** Finger swipe starting at (x, y) moving by {@code dy}. */
    private void swipe(float x, float y, float dy) {
        if (gestureBusy || a11y == null) return;
        DisplayMetrics m = svc.getResources().getDisplayMetrics();
        float h = Math.max(m.heightPixels, 1);
        float margin = 12 * dp;
        float y0 = Math.max(margin, Math.min(h - margin, y));
        float y1 = Math.max(margin, Math.min(h - margin, y0 + dy));
        if (Math.abs(y1 - y0) < 20 * dp) {
            // Too close to an edge: start from the middle instead.
            y0 = h / 2f;
            y1 = Math.max(margin, Math.min(h - margin, y0 + dy));
        }
        Path p = new Path();
        p.moveTo(Math.max(0, x), y0);
        p.lineTo(Math.max(0, x), y1);
        run(p, 280);
    }

    private void run(final Path p, final long duration) {
        gestureBusy = true;
        // Let the touch layer step aside so the injected touch reaches the app below.
        setTouchable(false);
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!shown) {
                    gestureBusy = false;
                    return;
                }
                boolean sent;
                try {
                    GestureDescription g = new GestureDescription.Builder()
                            .addStroke(new GestureDescription.StrokeDescription(p, 0, duration))
                            .build();
                    sent = a11y.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription d) {
                            gestureDone();
                        }

                        @Override
                        public void onCancelled(GestureDescription d) {
                            gestureDone();
                        }
                    }, h);
                } catch (Throwable t) {
                    sent = false;
                }
                if (!sent) gestureDone();
            }
        }, 70);
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                gestureDone();
            }
        }, duration + 1500);
    }

    private void gestureDone() {
        if (!gestureBusy) return;
        gestureBusy = false;
        setTouchable(mode != NONE);
    }

    // ------------------------------------------------------------ button

    /** Round panel button with a drawn icon. */
    static final class Btn extends View {
        private final int icon;
        private boolean on;
        private int extra, tint = Ui.TEXT;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);

        Btn(Context c, int icon) {
            super(c);
            this.icon = icon;
            setClickable(true);
        }

        void setOn(boolean v) {
            on = v;
            invalidate();
        }

        void setExtra(int v) {
            extra = v;
            invalidate();
        }

        void setTint(int v) {
            tint = v;
            invalidate();
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            float r = Math.min(w, hh) / 2f - 1;
            if (on) {
                bg.setColor(Ui.AMBER);
                c.drawCircle(w / 2, hh / 2, r, bg);
            } else if (isPressed()) {
                bg.setColor(0x33F1EADF);
                c.drawCircle(w / 2, hh / 2, r, bg);
            }
            int color = on ? Ui.INK_DARK : tint;
            Icons.draw(c, icon, w / 2, hh / 2, Math.min(w, hh) * 0.56f, color, extra);
        }
    }
}
