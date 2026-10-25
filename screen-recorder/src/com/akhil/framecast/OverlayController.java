package com.akhil.framecast;

import android.content.Context;
import android.content.Intent;
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
 *   <li><b>Draw</b>: a clear full-screen layer catches your finger, so the app
 *       below stays still, and you draw arrows, boxes and pen lines on top.
 *       The real screen stays visible at full quality (no copied picture).</li>
 *   <li><b>White / Black board</b>: same, on a plain white or black page.</li>
 *   <li><b>Photo</b>: any picture from the phone, full screen, to draw on.
 *       It always records, even when the app behind blocks recording.</li>
 *   <li>Back to <b>Live</b>: all marks are removed and the phone works again.</li>
 * </ul>
 * The panel can be dragged anywhere, even mostly off the screen.
 */
final class OverlayController {
    static final int ARROW = InkView.ARROW, SQUARE = InkView.BOX, PEN = InkView.PEN;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float dp;

    private volatile InkView ink;
    private LinearLayout panel, drawTools, boards;
    private TextView timer, freezeLabel;
    private Btn bFreeze, bArrow, bSquare, bPen, bColor;
    private WindowManager.LayoutParams inkLp, panelLp;

    private boolean shown, collapsed, frozen, freezing, inkAttached;
    private int board;     // 0 = draw on the live screen, else the board colour
    private int tool = ARROW;
    private int colorIdx;
    private final int[] loc = new int[2];

    OverlayController(Context c) {
        ctx = c;
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
        if (picture != null) picture.recycle();
        picture = null;
        freezing = false;
        collapsed = false;
    }

    private void removeQuietly(View v) {
        if (v == null) return;
        try {
            wm.removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
    }

    /** Recording time; when the screen is coming through black, says so in red. */
    void setStatus(String time, boolean blocked) {
        if (timer == null) return;
        timer.setText(blocked ? time + "\nBlocked" : time);
        timer.setTextColor(blocked ? Ui.VERMILION : Ui.TEXT);
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

    /** Live -> Draw (screen freezes), Draw / board -> Live (marks removed). */
    void toggleFreeze() {
        if (!shown || freezing) return;
        if (frozen) {
            goLive();
            return;
        }
        board = 0;
        enterFreeze();
    }

    /** Opens a plain white or black page to write on. */
    static final int PICTURE_BG = 0xFF000000;
    private Bitmap picture;

    /** Shows a picture full screen (fit, on black) to draw on. Takes ownership of {@code b}. */
    void openPicture(Bitmap b) {
        if (!shown || b == null) {
            if (b != null) b.recycle();
            return;
        }
        if (frozen) goLive();
        picture = b;
        ink.setPicture(b);
        board = PICTURE_BG;
        enterFreeze();
    }

    void openBoard(int color) {
        if (!shown || freezing || frozen) return;
        board = color;
        enterFreeze();
    }

    private void enterFreeze() {
        freezing = false;
        if (!shown || frozen) return;
        frozen = true;
        ink.clear();
        ink.setBoard(board);
        try {
            wm.addView(ink, inkLp);
            inkAttached = true;
            // Keep the panel above the drawing layer.
            wm.removeViewImmediate(panel);
            wm.addView(panel, panelLp);
        } catch (Throwable ignored) {
        }
        if (board == Ui.WHITE_BOARD) {
            tool = PEN;
            colorIdx = Ui.DARK_INK;
        } else if (board == Ui.BLACK_BOARD) {
            tool = PEN;
            colorIdx = Ui.CHALK;
        } else {
            tool = ARROW;
            if (colorIdx == Ui.DARK_INK || colorIdx == Ui.CHALK) colorIdx = 0;
        }
        bColor.setExtra(Ui.INK[colorIdx]);
        updateButtons();
    }

    private void goLive() {
        frozen = false;
        if (ink != null) {
            ink.clear();
            ink.setBoard(0);
            ink.setPicture(null);
        }
        removeInk();
        if (picture != null) picture.recycle();
        picture = null;
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
        boards.setVisibility(!frozen && !collapsed ? View.VISIBLE : View.GONE);
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

        panel = new DragPanel(c);
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
        timer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCollapsed(!collapsed);
            }
        });
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

        boards = new LinearLayout(c);
        boards.setOrientation(LinearLayout.VERTICAL);
        boards.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(boards);
        addBtn(boards, Icons.WHITEBOARD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBoard(Ui.WHITE_BOARD);
            }
        });
        addLabel(boards, "White");
        addBtn(boards, Icons.BLACKBOARD, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBoard(Ui.BLACK_BOARD);
            }
        });
        addLabel(boards, "Black");
        addBtn(boards, Icons.PHOTO, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ctx.startActivity(new Intent(ctx, PickPictureActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                } catch (Throwable ignored) {
                }
            }
        });
        addLabel(boards, "Photo");

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
        addBtn(drawTools, Icons.CLEAR, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ink != null) ink.clear();
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.LEFT;
        Point scr = screenSize();
        panelLp.x = scr.x - (int) (64 * dp);
        panelLp.y = (int) (scr.y * 0.14f);
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

    private void addLabel(LinearLayout parent, String text) {
        TextView t = new TextView(ctx);
        t.setTypeface(Ui.font(ctx, 700));
        t.setTextSize(10);
        t.setTextColor(Ui.MUTED);
        t.setGravity(Gravity.CENTER);
        t.setText(text);
        parent.addView(t);
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

    /**
     * Moves the panel when it is dragged from anywhere (a button too: a short
     * tap still clicks it). It can go anywhere, up to 95% off the screen.
     */
    final class DragPanel extends LinearLayout {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;
        private final float slop = 10 * dp;

        DragPanel(Context c) {
            super(c);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start(e);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) dragging = true;
                    return dragging;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) dragging = true;
                    if (dragging) moveTo(startX + (int) (e.getRawX() - downX), startY + (int) (e.getRawY() - downY));
                    return true;
                default:
                    dragging = false;
                    return true;
            }
        }

        private void start(MotionEvent e) {
            downX = e.getRawX();
            downY = e.getRawY();
            startX = panelLp.x;
            startY = panelLp.y;
            dragging = false;
        }
    }

    /** Places the panel; at least 5% of it always stays on screen. */
    void moveTo(int x, int y) {
        if (panel == null) return;
        Point scr = screenSize();
        int w = Math.max(1, panel.getWidth()), hgt = Math.max(1, panel.getHeight());
        int keepX = Math.max(1, (int) (w * 0.05f)), keepY = Math.max(1, (int) (hgt * 0.05f));
        panelLp.x = Math.max(keepX - w, Math.min(scr.x - keepX, x));
        panelLp.y = Math.max(keepY - hgt, Math.min(scr.y - keepY, y));
        try {
            wm.updateViewLayout(panel, panelLp);
        } catch (Throwable ignored) {
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
