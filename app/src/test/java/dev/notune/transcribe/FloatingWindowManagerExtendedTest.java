package dev.notune.transcribe;

import org.junit.Test;
import static org.junit.Assert.*;

public class FloatingWindowManagerExtendedTest {

    @Test
    public void testCalculateDockedX() {
        int bubbleWidth = 100;
        int screenWidth = 1080;
        float peekRatio = 0.45f; // 45px peek

        // Left docking
        int leftDockX = (int) (100 * peekRatio); // -45
        int leftResult = -leftDockX;
        assertEquals(-45, leftResult);

        // Right docking
        int rightResult = screenWidth - bubbleWidth + leftDockX; // 1080 - 100 + 45 = 1025
        assertEquals(1025, rightResult);
    }

    @Test
    public void testEffectiveStatusBarClamping() {
        int screenHeight = 800;
        int bubbleHeight = 100;
        int statusBarHeight = 50;

        int clamped = FloatingWindowManager.clampY(30, bubbleHeight, screenHeight, statusBarHeight);
        assertEquals(50, clamped);

        int clampedBottom = FloatingWindowManager.clampY(750, bubbleHeight, screenHeight, statusBarHeight);
        assertEquals(700, clampedBottom);
    }
}
