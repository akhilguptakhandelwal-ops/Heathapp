package com.akhil.framecast;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PrefsTest {
    @Test
    public void normalStyleFollowsPhoneShapeWithFallbacks() {
        Prefs p = new Prefs(RuntimeEnvironment.getApplication());
        p.style = 1;
        p.quality = 1;
        int[][] c = p.outputCandidates(1080f / 2400f);
        assertArrayEquals(new int[]{1080, 2400}, c[0]);
        assertArrayEquals(new int[]{864, 1920}, c[1]);      // for encoders that stop at 1920
        for (int[] s : c) {
            assertEquals(0, s[0] % 2);
            assertEquals(0, s[1] % 16);
            assertTrue(s[1] > s[0]);
        }
        // Phone turned sideways: video turns too.
        int[][] l = p.outputCandidates(2400f / 1080f);
        assertTrue(l[0][0] > l[0][1]);
    }

    @Test
    public void phoneFrameUsesChosenShape() {
        Prefs p = new Prefs(RuntimeEnvironment.getApplication());
        p.style = 0;
        p.shape = 1;
        p.quality = 1;
        assertArrayEquals(new int[]{1920, 1080}, p.outputCandidates(0.45f)[0]);
    }
}
