package com.akhil.framecast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FrameRendererTest {

    private Bitmap render(int w, int h, int phone, int bg, float aspect, int padCols, String name) {
        FrameRenderer fr = new FrameRenderer(w, h, phone, bg);
        fr.layout(aspect);
        assertEquals(0, fr.capW % 2);
        assertEquals(0, fr.capH % 2);
        assertEquals(aspect, fr.capW / (float) fr.capH, 0.01f);

        // The screen grab can be wider than the screen (row padding) - fill that with magenta.
        Bitmap content = PreviewView.fakeScreen(fr.capW, fr.capH);
        Bitmap shot = Bitmap.createBitmap(fr.capW + padCols, fr.capH, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(shot);
        sc.drawColor(Color.MAGENTA);
        sc.drawBitmap(content, 0, 0, null);

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        fr.draw(new Canvas(out), shot, fr.capW, fr.capH);

        RectF s = fr.screenRect();
        assertTrue("phone must fit", s.left > 0 && s.top > 0 && s.right < w && s.bottom < h);
        // Middle of the screen shows the captured content.
        int cx = (int) s.centerX(), cy = (int) (s.top + s.height() * 0.62f);
        int want = content.getPixel(fr.capW / 2, (int) (fr.capH * 0.62f));
        assertTrue("screen content visible", Shots.dist(out.getPixel(cx, cy), want) < 30);
        // Corners are background, not phone.
        int bgTop = Prefs.BG_COLORS[bg][0];
        assertTrue("background in corner", Shots.dist(out.getPixel(2, 2), bgTop) < 60);
        // No padding columns leak into the video.
        for (int y = (int) s.top + 40; y < s.bottom - 40; y += 7) {
            for (int x = (int) s.right - 30; x < s.right + 30; x++) {
                int p = out.getPixel(x, y);
                assertTrue("padding leaked at " + x + "," + y, Shots.dist(p, Color.MAGENTA) > 90);
            }
        }
        Shots.save(out, name);
        fr.release();
        return out;
    }

    @Test
    public void portraitVideoAllPhones() {
        for (int i = 0; i < Prefs.PHONES.length; i++) {
            render(1080, 1920, i, i % Prefs.BACKGROUNDS.length, 1080f / 2400f, 16,
                    "frame_9x16_" + Prefs.PHONES[i] + "_" + Prefs.BACKGROUNDS[i % 5] + ".png");
        }
        render(1080, 1920, 0, 4, 1080f / 2400f, 0, "frame_9x16_Graphite_Plum.png");
    }

    @Test
    public void landscapeVideo() {
        render(1920, 1080, 1, 0, 1080f / 2400f, 32, "frame_16x9_Sand_Charcoal.png");
        render(1280, 720, 0, 2, 1080f / 2340f, 8, "frame_16x9_720p.png");
    }

    @Test
    public void phoneTurnedSideways() {
        render(1920, 1080, 0, 3, 2400f / 1080f, 16, "frame_16x9_phone_landscape.png");
        render(1080, 1920, 3, 1, 2400f / 1080f, 16, "frame_9x16_phone_landscape.png");
    }

    @Test
    public void drawsWithoutAnyFrameYet() {
        FrameRenderer fr = new FrameRenderer(720, 1280, 0, 0);
        fr.layout(0.45f);
        Bitmap out = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
        fr.draw(new Canvas(out), null, 0, 0);
        RectF s = fr.screenRect();
        int p = out.getPixel((int) s.centerX(), (int) s.centerY());
        assertTrue("black screen before first frame", Shots.dist(p, Color.BLACK) < 30);
    }
}
