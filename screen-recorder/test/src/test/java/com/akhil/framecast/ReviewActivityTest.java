package com.akhil.framecast;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReviewActivityTest {

    private static boolean hasText(View v, String s) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(s)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasText(g.getChildAt(i), s)) return true;
        }
        return false;
    }

    @Test
    public void showsSaveTrimDiscard() throws Exception {
        File f = new File(RuntimeEnvironment.getApplication().getCacheDir(), "rec_test.mp4");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[1024]);
        }
        Prefs.setPending(RuntimeEnvironment.getApplication(), f.getAbsolutePath(), 65000);
        ActivityController<ReviewActivity> c = Robolectric.buildActivity(ReviewActivity.class).setup();
        View root = c.get().findViewById(android.R.id.content);
        assertTrue(hasText(root, "Your recording"));
        assertTrue(hasText(root, "TRIM"));
        assertTrue(hasText(root, "Save"));
        assertTrue(hasText(root, "Discard"));
        assertTrue(hasText(root, "Keeps 1:05"));
        assertFalse(c.get().isFinishing());
    }

    @Test
    public void closesWhenNothingIsWaiting() {
        Prefs.setPending(RuntimeEnvironment.getApplication(), null, 0);
        ReviewActivity a = Robolectric.buildActivity(ReviewActivity.class).setup().get();
        assertTrue(a.isFinishing());
        assertNull(Prefs.pendingPath(RuntimeEnvironment.getApplication()));
    }
}
