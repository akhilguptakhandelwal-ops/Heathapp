package com.akhil.framecast;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Plain JVM test of the tap / hold / drag / scroll rules. */
public class MouseGestureTest {
    private final MouseGesture g = new MouseGesture(16);

    @Test
    public void quickTapClicks() {
        g.down(1000, 100, 100);
        assertEquals(MouseGesture.CLICK, g.up(1120, 103, 101));
    }

    @Test
    public void holdDoesNotClick() {
        g.down(1000, 100, 100);
        assertEquals(MouseGesture.NONE, g.up(1600, 100, 100));
    }

    @Test
    public void dragDoesNotClick() {
        g.down(1000, 100, 100);
        g.move(300, 400);
        assertEquals(MouseGesture.NONE, g.up(1100, 300, 400));
    }

    @Test
    public void dragBackToStartStillNoClick() {
        g.down(1000, 100, 100);
        g.move(300, 100);
        g.move(100, 100);
        assertEquals(MouseGesture.NONE, g.up(1150, 100, 100));
    }

    @Test
    public void twoFingersScroll() {
        g.down(1000, 100, 100);
        g.secondFinger(500);
        g.twoFingerMove(800);
        assertEquals(MouseGesture.SCROLL, g.fingerLifted());
        assertEquals(300f, g.scrollDy(), 0.01f);
        assertEquals(MouseGesture.NONE, g.up(1200, 100, 100));
    }

    @Test
    public void twoFingersTinyMoveIsNotScroll() {
        g.down(1000, 100, 100);
        g.secondFinger(500);
        g.twoFingerMove(505);
        assertEquals(MouseGesture.NONE, g.fingerLifted());
        assertEquals(MouseGesture.NONE, g.up(1100, 100, 100));
    }
}
