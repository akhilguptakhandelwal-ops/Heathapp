package com.akhil.framecast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The tool panel shown while recording, and Freeze mode.
 *
 * <ul>
 *   <li><b>Live</b> (default): only the small panel is on screen, so the phone
 *       works completely normally.</li>
 *   <li><b>Draw</b>: the screen picture is frozen under a full-screen layer
 *       and you draw arrows, boxes and pen lines on it.</li>
 *   <li>Back to <b>Live</b>: all marks are removed and the phone works again.</li>
 * </ul>
 */
final class OverlayController {
    static final int ARROW = InkView.ARROW, SQUARE = InkView.BOX, PEN = InkView.PEN;

    /** Gets the current screen picture (or null) for Freeze mode. */
    interface Snapshotter {
        void snapshot(SnapshotDone done);
    }

    interface SnapshotDone {
        void done(Bitmap b);
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float dp;
    private final Snapshotter snapshotter;

    private volatile InkView ink;
    private LinearLayout panel, drawTools;
    private TextView timer, freezeLabel;
    private Btn bFreeze, bArrow, bSquare, bPen, bColor;
    private WindowManager.LayoutParams inkLp, panelLp;

    private boolean shown, collapsed, frozen, freezing, inkAttached;
    private int tool = ARROW;
    private int colorIdx;
    private Bitmap frozenPicture;
    private final int[] loc = new int[2];

    OverlayController(Context c, Snapshotter s) {
        ctx = c;
        snapshotter = s;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        dp = Ui.dp(c, 1);
    }

    // ------------------------------------------------------------ windows

    void show() {
        if (shown) return;
        try {
            build();
            wm.addView(panel, panelLp);
            shown = true;
            frozen = false;
            updateButtons();
            h.post(trackPanel);
        } catch (Throwable t) {
            hide();
        }
    }

    void hide() {
        h.removeCallbacksAndMessages(null);
        panelRect = null;
        removeInk();
        removeQuietly(panel);
        panel = null;
        shown = false;
        frozen = false;
        freezing = false;
        collapsed = false;
        if (frozenPicture != null) frozenPicture.recycle();
        frozenPicture = null;
    }

    private void removeQuietly(View v) {
        if (v == null) return;
        try {
            wm.removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
    }

    void setTimer(String text, boolean live) {
        if (timer == null) return;
        timer.setText(text);
        timer.setTextColor(live ? Ui.TEXT : Ui.AMBER);
    }

    boolean isFrozen() {
        return frozen;
    }

    // -------------------------------------------------------------- freeze

    /** Live -> Draw (screen freezes), Draw -> Live (marks removed). */
    void toggleFreeze() {
        if (!shown || freezing) return;
        if (frozen) {
            goLive();
            return;
        }
        freezing = true;
        if (snapshotter == null) {
            enterFreeze(null);
            return;
        }
        snapshotter.snapshot(new SnapshotDone() {
            @Override
            public void done(Bitmap b) {
                enterFreeze(b);
            }
        });
    }

    private void enterFreeze(Bitmap picture) {
        freezing = false;
        if (!shown || frozen) {
            if (picture != null) picture.recycle();
            return;
        }
        frozen = true;
        frozenPicture = picture;
        Point s = screenSize();
        ink.clear();
        ink.setFrozen(picture, s.x, s.y);
        try {
            wm.addView(ink, inkLp);
            inkAttached = true;
            // Keep the panel above the drawing layer.
            wm.removeViewImmediate(panel);
            wm.addView(panel, panelLp);
        } catch (Throwable ignored) {
        }
        tool = ARROW;
        updateButtons();
    }

    private void goLive() {
        frozen = false;
        if (ink != null) {
            ink.clear();
            ink.setFrozen(null, 0, 0);
        }
        removeInk();
        if (frozenPicture != null) frozenPicture.recycle();
        frozenPicture = null;
        updateButtons();
    }

    private void removeInk() {
        if (inkAttached) removeQuietly(ink);
        inkAttached = false;
    }

    private Point screenSize() {
        Point p = new Point();
        try {
            Display d = wm.getDefaultDisplay();
            d.getRealSize(p);
        } catch (Throwable ignored) {
        }
        if (p.x <= 0 || p.y <= 0) {
            p.set(ctx.getResources().getDisplayMetrics().widthPixels, ctx.getResources().getDisplayMetrics().heightPixels);
        }
        return p;
    }

    private void updateButtons() {
        if (panel == null) return;
        bFreeze.setIcon(frozen ? Icons.PLAY : Icons.PEN);
        bFreeze.setOn(frozen);
        freezeLabel.setText(frozen ? "Live" : "Draw");
        drawTools.setVisibility(frozen && !collapsed ? View.VISIBLE : View.GONE);
        bArrow.setOn(tool == ARROW);
        bSquare.setOn(tool == SQUARE);
        bPen.setOn(tool == PEN);
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

    /** Draws the marks (screen coordinates) onto a video frame. Any thread. */
    void drawMarks(Canvas c) {
        InkView i = ink;
        if (i != null && frozen) i.drawMarks(c);
    }

    // -------------------------------------------------------------- build

    private void build() {
        Context c = ctx;
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        ink = new InkView(c);
        ink.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return onDraw(e);
            }
        });
        inkLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        inkLp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            inkLp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) inkLp.setFitInsetsTypes(0);

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
        timer.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        timer.setMinWidth((int) (46 * dp));
        timer.setOnTouchListener(new PanelDrag());
        panel.addView(timer);

        bFreeze = addBtn(panel, Icons.PEN, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFreeze();
            }
        });
        freezeLabel = new TextView(c);
        freezeLabel.setTypeface(Ui.font(c, 700));
        freezeLabel.setTextSize(10);
        freezeLabel.setTextColor(Ui.TEXT);
        freezeLabel.setGravity(Gravity.CENTER);
        freezeLabel.setText("Draw");
        panel.addView(freezeLabel);

        drawTools = new LinearLayout(c);
        drawTools.setOrientation(LinearLayout.VERTICAL);
        drawTools.setGravity(Gravity.CENTER_HORIZONTAL);
        drawTools.setBackground(Ui.round(0x22D9A441, 20 * dp));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) (4 * dp);
        panel.addView(drawTools, dl);
        bArrow = addBtn(drawTools, Icons.ARROW, pickTool(ARROW));
        bSquare = addBtn(drawTools, Icons.SQUARE, pickTool(SQUARE));
        bPen = addBtn(drawTools, Icons.PENCIL, pickTool(PEN));
        bColor = addBtn(drawTools, Icons.COLOR, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                colorIdx = (colorIdx + 1) % Ui.INK.length;
                bColor.setExtra(Ui.INK[colorIdx]);
            }
        });
        bColor.setExtra(Ui.INK[colorIdx]);
        addBtn(drawTools, Icons.UNDO, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ink != null) ink.undo();
            }
        });

        addDivider(panel);
        addBtn(panel, Icons.CAMERA, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RecorderService r = RecorderService.instance;
                if (r != null) r.screenshot();
            }
        });
        Btn stop = addBtn(panel, Icons.STOP, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RecorderService r = RecorderService.instance;
                if (r != null) r.requestStop();
                else hide();
            }
        });
        stop.setTint(Ui.VERMILION);

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.END;
        panelLp.x = (int) (6 * dp);
        panelLp.y = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.16f);
    }

    private View.OnClickListener pickTool(final int t) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tool = t;
                updateButtons();
            }
        };
    }

    private Btn addBtn(LinearLayout parent, int icon, View.OnClickListener l) {
        Btn b = new Btn(ctx, icon);
        b.setOnClickListener(l);
        int s = (int) (44 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.topMargin = (int) (3 * dp);
        parent.addView(b, lp);
        return b;
    }

    private void addDivider(LinearLayout parent) {
        View v = new View(ctx);
        v.setBackgroundColor(0x33F1EADF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (26 * dp), Math.max(1, (int) dp));
        lp.topMargin = (int) (6 * dp);
        lp.bottomMargin = (int) (2 * dp);
        parent.addView(v, lp);
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        for (int i = 1; i < panel.getChildCount(); i++) panel.getChildAt(i).setVisibility(c ? View.GONE : View.VISIBLE);
        updateButtons();
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
                        int maxY = ctx.getResources().getDisplayMetrics().heightPixels - panel.getHeight();
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

    // ------------------------------------------------------------ drawing

    private boolean onDraw(MotionEvent e) {
        if (!frozen || ink == null) return false;
        ink.getLocationOnScreen(loc);
        float x = e.getX() + loc[0], y = e.getY() + loc[1];
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ink.begin(tool, Ui.INK[colorIdx], x, y);
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

    // ------------------------------------------------------------ button

    /** Round panel button with a drawn icon. */
    static final class Btn extends View {
        private int icon;
        private boolean on;
        private int extra, tint = Ui.TEXT;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);

        Btn(Context c, int icon) {
            super(c);
            this.icon = icon;
            setClickable(true);
        }

        void setIcon(int v) {
            icon = v;
            invalidate();
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
