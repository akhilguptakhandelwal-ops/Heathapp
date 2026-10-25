package com.akhil.framecast;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {

    private static boolean hasText(View v, String s) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(s)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasText(g.getChildAt(i), s)) return true;
        }
        return false;
    }

    @Test
    public void screenRendersWithAllSections() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ScrollView root = (ScrollView) ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        View col = root.getChildAt(0);
        for (String s : new String[]{"FrameCast", "Start recording", "Tools over every screen", "Microphone", "Screenshot", "Draw", "Back to Live", "White / Black board", "Photo", "Blocked screen", "Open folder", "My recordings", "Recording style", "Phone frame", "Normal",
                "9:16 Shorts", "16:9 YouTube", "Graphite", "Terracotta", "HOW TO USE", "Live"}) {
            assertTrue("missing: " + s, hasText(col, s));
        }
        int w = 1080;
        col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        col.layout(0, 0, w, col.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(w, col.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Ui.BG);
        col.draw(c);
        Shots.save(b, "app_main_screen.png");
    }
}
