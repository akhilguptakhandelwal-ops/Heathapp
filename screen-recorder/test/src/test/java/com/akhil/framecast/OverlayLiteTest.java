package com.akhil.framecast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PathMeasure;
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
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAccessibilityService;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;

/** Normal APK: no accessibility service, plain display-over-other-apps windows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class OverlayLiteTest {
    private ControlService svc;
    private OverlayController o;
    private ShadowAccessibilityService shadow;
    private long t;

    @Before
    public void setUp() {
        svc = Robolectric.setupService(ControlService.class);
        svc.onServiceConnected();
        shadow = shadowOf(svc);
        shadow.setCanDispatchGestures(true);
        o = new OverlayController(svc, null);
        o.show();
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

    private int shapes() {
        try {
            Field f = InkView.class.getDeclaredField("shapes");
            f.setAccessible(true);
            return ((List<?>) f.get(field("ink"))).size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean touchLayerTakesTouches() {
        WindowManager.LayoutParams lp = field("touchLp");
        return (lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
    }

    private void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void touch(int action, long at, float... xy) {
        int n = xy.length / 2;
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            pp[i] = new MotionEvent.PointerProperties();
            pp[i].id = i;
            pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new MotionEvent.PointerCoords();
            pc[i].x = xy[i * 2];
            pc[i].y = xy[i * 2 + 1];
            pc[i].pressure = 1;
            pc[i].size = 1;
        }
        MotionEvent e = MotionEvent.obtain(t, at, action, n, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0);
        View touch = field("touch");
        touch.dispatchTouchEvent(e);
        e.recycle();
    }

    private float[] start(GestureDescription g) {
        PathMeasure pm = new PathMeasure(g.getStroke(0).getPath(), false);
        float[] p = new float[2];
        if (pm.getLength() == 0) {
            // A tap is a single point.
            RectF r = new RectF();
            g.getStroke(0).getPath().computeBounds(r, true);
            return new float[]{r.left, r.top};
        }
        pm.getPosTan(0, p, null);
        return p;
    }

    private float[] end(GestureDescription g) {
        PathMeasure pm = new PathMeasure(g.getStroke(0).getPath(), false);
        float[] p = new float[2];
        pm.getPosTan(pm.getLength(), p, null);
        return p;
    }

    private int gestures() {
        return shadow.getGesturesDispatched().size();
    }

    private GestureDescription lastGesture() {
        List<ShadowAccessibilityService.GestureDispatch> l = shadow.getGesturesDispatched();
        return l.get(l.size() - 1).description();
    }

    private void click(String button) {
        View b = field(button);
        assertTrue(b.performClick());
        idle(10);
    }

    @Test
    public void windowsAreNormalOverlaysThatLetTapsThrough() {
        assertTrue(!touchLayerTakesTouches());
        WindowManager.LayoutParams lp = field("touchLp");
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, lp.type);
        assertTrue("must be <= 80% opaque so taps reach the app below", lp.alpha <= 0.8f);
        WindowManager.LayoutParams panelLp = field("panelLp");
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, panelLp.type);
    }

    @Test
    public void mouseMovesAndShowsClickButSendsNothing() {
        click("bMouse");
        assertTrue(touchLayerTakesTouches());
        LinearLayout mouseGroup = field("mouseGroup");
        assertEquals(View.GONE, mouseGroup.getVisibility());
        touch(MotionEvent.ACTION_DOWN, t, 300, 500);
        touch(MotionEvent.ACTION_UP, t + 90, 300, 500);
        touch(MotionEvent.ACTION_DOWN, t + 200, 100, 100);
        touch(MotionEvent.ACTION_MOVE, t + 240, 700, 1600);
        touch(MotionEvent.ACTION_UP, t + 300, 700, 1600);
        idle(2000);
        assertEquals(0, gestures());
        InkView ink = field("ink");
        assertEquals(700, ink.cursorX(), 1);
        assertEquals(1600, ink.cursorY(), 1);
        click("bMouse");
        assertTrue("tool off: phone works normally", !touchLayerTakesTouches());
    }

    @Test
    public void arrowAndSquareStillDraw() {
        click("bArrow");
        touch(MotionEvent.ACTION_DOWN, t, 200, 1500);
        touch(MotionEvent.ACTION_UP, t + 100, 700, 1100);
        click("bSquare");
        touch(MotionEvent.ACTION_DOWN, t + 200, 120, 300);
        touch(MotionEvent.ACTION_UP, t + 300, 900, 700);
        assertEquals(2, shapes());
    }

    @Test
    public void screenshotOfLiveScreenAndVideoFrame() {
        int w = 1080, h = 2400;
        // Draw an arrow, a box and park the cursor, like a real session.
        click("bArrow");
        touch(MotionEvent.ACTION_DOWN, t, 820, 1650);
        touch(MotionEvent.ACTION_UP, t + 80, 560, 1180);
        click("bSquare");
        touch(MotionEvent.ACTION_DOWN, t + 200, 90, 640);
        touch(MotionEvent.ACTION_UP, t + 280, 990, 1010);
        click("bMouse");
        touch(MotionEvent.ACTION_DOWN, t + 400, 420, 1320);
        touch(MotionEvent.ACTION_MOVE, t + 450, 430, 1330);   // holding: halo shows
        idle(16);

        Bitmap live = PreviewView.fakeScreen(w, h);
        Canvas c = new Canvas(live);
        View ink = field("ink");
        ink.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        ink.layout(0, 0, w, h);
        c.saveLayerAlpha(0, 0, w, h, 204);   // lite windows are 80% opaque
        ink.draw(c);
        c.restore();
        View panel = field("panel");
        panel.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        assertTrue(panel.getMeasuredWidth() > 100);
        int px = w - panel.getMeasuredWidth() - 18, py = 380;
        c.save();
        c.translate(px, py);
        panel.draw(c);
        c.restore();
        int[] panelRect = {px, py, px + panel.getMeasuredWidth(), py + panel.getMeasuredHeight()};
        Shots.save(live, "lite_live_screen.png");

        FrameRenderer fr = new FrameRenderer(1080, 1920, 0, 0);
        fr.layout(w / (float) h);
        // Same steps as RecorderService: capture -> hide panel -> frame + marks.
        Bitmap scaled = Bitmap.createScaledBitmap(live, fr.capW, fr.capH, true).copy(Bitmap.Config.ARGB_8888, true);
        float sx = fr.capW / (float) w, sy = fr.capH / (float) h;
        PanelMask.apply(scaled, fr.capW, fr.capH, panelRect, sx, sy, 9 * sx);
        Bitmap out = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        FrameRenderer.Marks marks = new FrameRenderer.Marks() {
            @Override
            public void draw(Canvas cc) {
                o.drawMarks(cc);
            }
        };
        fr.draw(new Canvas(out), scaled, fr.capW, fr.capH, marks, w, h);
        Shots.save(out, "lite_video_frame.png");

        RectF s = fr.screenRect();
        float k = s.width() / w;
        // Panel is gone: its amber "selected" button and red stop button are nowhere in its area.
        for (int y = panelRect[1]; y < panelRect[3]; y += 3) {
            for (int x = panelRect[0]; x < panelRect[2]; x += 3) {
                int p = out.getPixel((int) (s.left + x * k), (int) (s.top + y * k));
                assertTrue("panel visible at " + x + "," + y, Shots.dist(p, Ui.AMBER) > 60);
            }
        }
        // The box edge that was under the panel is still in the video, in full colour.
        int edge = out.getPixel((int) (s.left + 990 * k), (int) (s.top + 820 * k));
        assertTrue("box edge under the panel missing", Shots.dist(edge, Ui.INK[0]) < 60);
        touch(MotionEvent.ACTION_UP, t + 900, 430, 1330);
    }
}
