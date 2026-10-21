package com.akhil.framecast;

import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
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
public class LibraryActivityTest {

    private static boolean hasText(View v, String s) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(s)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasText(g.getChildAt(i), s)) return true;
        }
        return false;
    }

    @Test
    public void opensWithTabsAndEmptyState() throws Exception {
        LibraryActivity a = Robolectric.buildActivity(LibraryActivity.class).setup().get();
        View root = a.findViewById(android.R.id.content);
        for (int i = 0; i < 50 && !hasText(root, "No videos yet"); i++) {
            Thread.sleep(20);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
        }
        assertTrue(hasText(root, "My recordings"));
        assertTrue(hasText(root, "Videos"));
        assertTrue(hasText(root, "Screenshots"));
        assertTrue(hasText(root, "No videos yet"));
    }
}
