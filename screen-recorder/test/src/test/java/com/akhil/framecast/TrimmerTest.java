package com.akhil.framecast;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/** Plain JVM test of which parts are kept after cuts. */
public class TrimmerTest {
    private static float[][] kept(float[]... cuts) {
        return Trimmer.keptParts(new ArrayList<>(Arrays.asList(cuts)));
    }

    @Test
    public void noCutsKeepsAll() {
        float[][] k = kept();
        assertEquals(1, k.length);
        assertEquals(0f, k[0][0], 0);
        assertEquals(1f, k[0][1], 0);
    }

    @Test
    public void cutsFromStartMiddleAndEnd() {
        float[][] k = kept(new float[]{0.5f, 0.6f}, new float[]{0f, 0.1f}, new float[]{0.9f, 1f});
        assertEquals(2, k.length);
        assertEquals(0.1f, k[0][0], 1e-6);
        assertEquals(0.5f, k[0][1], 1e-6);
        assertEquals(0.6f, k[1][0], 1e-6);
        assertEquals(0.9f, k[1][1], 1e-6);
    }

    @Test
    public void overlappingCutsMerge() {
        float[][] k = kept(new float[]{0.2f, 0.5f}, new float[]{0.4f, 0.7f}, new float[]{0.65f, 0.3f});
        assertEquals(2, k.length);
        assertEquals(0.2f, k[0][1], 1e-6);
        assertEquals(0.7f, k[1][0], 1e-6);
    }
}
