package com.akhil.framecast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.time.Duration;

/** Live <-> Draw (freeze) behaviour of the tool panel. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class OverlayTest {
    private static final int W = 1080, H = 2400;
    private OverlayController o;
    private Bitmap screen;          // what the "capture" returns when freezing
    private long t;

    @Before
    public void setUp() {
        Context c = RuntimeEnvironment.getApplication();
        screen = PreviewView.fakeScreen(W, H);
        o = new OverlayController(c, new OverlayController.Snapshotter() {
            @Override
            public void snapshot(OverlayController.SnapshotDone done) {
                done.done(screen.copy(Bitmap.Config.ARGB_8888, false));
            }
        });
        o.show();
        idle(100);
        t = SystemClock.uptimeMillis();
    }

    @After
    public void tearDown() {
        o.hide();
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        try {
            Field f = OverlayController.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void touch(int action, long at, float x, float y) {
        MotionEvent e = MotionEvent.obtain(t, at, action, x, y, 0);
        InkView ink = field("ink");
        ink.dispatchTouchEvent(e);
        e.recycle();
    }

    private void click(String button) {
        View b = field(button);
        assertTrue(b.performClick());
        idle(20);
    }

    private InkView ink() {
        return field("ink");
    }

    @Test
    public void liveModeLeavesTheScreenFree() {
        // Only the small panel is on screen: nothing covers the phone.
        assertFalse(o.isFrozen());
        assertFalse((Boolean) field("inkAttached"));
        assertFalse(ink().isAttachedToWindow());
        WindowManager.LayoutParams p = field("panelLp");
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, p.type);
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, p.width);
        LinearLayout tools = field("drawTools");
        assertEquals(View.GONE, tools.getVisibility());
        // Touches on the drawing layer do nothing in Live mode.
        touch(MotionEvent.ACTION_DOWN, t, 100, 100);
        touch(MotionEvent.ACTION_UP, t + 50, 600, 600);
        assertEquals(0, ink().count());
    }

    @Test
    public void drawFreezesThenLiveClearsEverything() {
        click("bFreeze");
        assertTrue(o.isFrozen());
        assertTrue((Boolean) field("inkAttached"));
        assertNotNull(field("frozenPicture"));
        LinearLayout tools = field("drawTools");
        assertEquals(View.VISIBLE, tools.getVisibility());

        // Arrow (default tool)
        touch(MotionEvent.ACTION_DOWN, t, 200, 1500);
        touch(MotionEvent.ACTION_MOVE, t + 40, 400, 1300);
        touch(MotionEvent.ACTION_UP, t + 80, 700, 1100);
        // Square
        click("bSquare");
        touch(MotionEvent.ACTION_DOWN, t + 200, 120, 300);
        touch(MotionEvent.ACTION_UP, t + 260, 900, 700);
        // Pen
        click("bPen");
        touch(MotionEvent.ACTION_DOWN, t + 300, 100, 2000);
        touch(MotionEvent.ACTION_MOVE, t + 320, 200, 1950);
        touch(MotionEvent.ACTION_MOVE, t + 340, 300, 2050);
        touch(MotionEvent.ACTION_UP, t + 360, 400, 2000);
        assertEquals(3, ink().count());

        ink().undo();
        assertEquals(2, ink().count());

        // Back to live: marks gone, nothing covers the screen.
        click("bFreeze");
        assertFalse(o.isFrozen());
        assertFalse((Boolean) field("inkAttached"));
        assertEquals(0, ink().count());
        assertEquals(View.GONE, tools.getVisibility());

        // And again: a fresh freeze starts clean.
        click("bFreeze");
        assertTrue(o.isFrozen());
        assertEquals(0, ink().count());
    }

    @Test
    public void freezeWorksEvenWithoutAPicture() {
        o.hide();
        o = new OverlayController(RuntimeEnvironment.getApplication(), new OverlayController.Snapshotter() {
            @Override
            public void snapshot(OverlayController.SnapshotDone done) {
                done.done(null);   // recording not started yet
            }
        });
        o.show();
        click("bFreeze");
        assertTrue(o.isFrozen());
        touch(MotionEvent.ACTION_DOWN, t, 200, 200);
        touch(MotionEvent.ACTION_UP, t + 50, 800, 900);
        assertEquals(1, ink().count());
    }

    @Test
    public void videoFrameHasFrozenScreenAndMarksButNoPanel() {
        click("bFreeze");
        touch(MotionEvent.ACTION_DOWN, t, 820, 1650);
        touch(MotionEvent.ACTION_UP, t + 80, 560, 1180);
        click("bSquare");
        touch(MotionEvent.ACTION_DOWN, t + 200, 90, 640);
        touch(MotionEvent.ACTION_UP, t + 280, 990, 1010);
        click("bPen");
        touch(MotionEvent.ACTION_DOWN, t + 300, 150, 1900);
        for (int i = 1; i <= 12; i++) {
            touch(MotionEvent.ACTION_MOVE, t + 300 + i * 10, 150 + i * 40, 1900 + (float) Math.sin(i / 2.0) * 80);
        }
        touch(MotionEvent.ACTION_UP, t + 450, 630, 1950);

        // What the phone shows: frozen picture + marks (ink window) + panel on top.
        Bitmap live = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(live);
        View ink = ink();
        ink.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        ink.layout(0, 0, W, H);
        ink.draw(c);
        View panel = field("panel");
        panel.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        int px = W - panel.getMeasuredWidth() - 18, py = 380;
        c.save();
        c.translate(px, py);
        panel.draw(c);
        c.restore();
        int[] panelRect = {px, py, px + panel.getMeasuredWidth(), py + panel.getMeasuredHeight()};
        Shots.save(live, "phone_screen_draw_mode.png");

        // Same steps as RecorderService: capture -> hide panel -> frame + marks.
        FrameRenderer fr = new FrameRenderer(1080, 1920, 0, 0);
        fr.layout(W / (float) H);
        Bitmap cap = Bitmap.createScaledBitmap(live, fr.capW, fr.capH, true).copy(Bitmap.Config.ARGB_8888, true);
        float sx = fr.capW / (float) W, sy = fr.capH / (float) H;
        PanelMask.apply(cap, fr.capW, fr.capH, panelRect, sx, sy, 9 * sx);
        Bitmap out = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        fr.draw(new Canvas(out), cap, fr.capW, fr.capH, new FrameRenderer.Marks() {
            @Override
            public void draw(Canvas cc) {
                o.drawMarks(cc);
            }
        }, W, H);
        Shots.save(out, "video_frame_draw_mode.png");

        RectF s = fr.screenRect();
        float k = s.width() / W;
        for (int y = panelRect[1]; y < panelRect[3]; y += 3) {
            for (int x = panelRect[0]; x < panelRect[2]; x += 3) {
                int p = out.getPixel((int) (s.left + x * k), (int) (s.top + y * k));
                assertTrue("panel visible at " + x + "," + y, Shots.dist(p, Ui.AMBER) > 60);
            }
        }
        int edge = out.getPixel((int) (s.left + 990 * k), (int) (s.top + 820 * k));
        assertTrue("box edge under the panel missing", Shots.dist(edge, Ui.INK[0]) < 60);
        // The frozen picture is what shows on the screen (a card colour from the fake screen).
        int card = out.getPixel((int) (s.left + 160 * k), (int) (s.top + 360 * k));
        assertTrue("frozen screen missing", Shots.dist(card, screen.getPixel(160, 360)) < 40);
    }
}
