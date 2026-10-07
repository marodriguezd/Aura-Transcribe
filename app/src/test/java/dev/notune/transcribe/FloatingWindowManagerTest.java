package dev.notune.transcribe;

import org.junit.Test;
import static org.junit.Assert.*;

public class FloatingWindowManagerTest {

    @Test
    public void testNearestEdgeSnappingLeft() {
        int targetX = FloatingWindowManager.calculateNearestEdgeX(100, 64, 1080);
        assertEquals(0, targetX);
    }

    @Test
    public void testNearestEdgeSnappingRight() {
        int targetX = FloatingWindowManager.calculateNearestEdgeX(800, 64, 1080);
        assertEquals(1080 - 64, targetX);
    }

    @Test
    public void testClampYWithinBounds() {
        int clampedY = FloatingWindowManager.clampY(500, 64, 2400, 80);
        assertEquals(500, clampedY);
    }

    @Test
    public void testClampYAboveStatusBar() {
        int clampedY = FloatingWindowManager.clampY(20, 64, 2400, 80);
        assertEquals(80, clampedY);
    }

    @Test
    public void testClampYBelowBottomEdge() {
        int clampedY = FloatingWindowManager.clampY(2500, 64, 2400, 80);
        assertEquals(2400 - 64, clampedY);
    }

    @Test
    public void testHoverDismissThreshold() {
        int screenWidth = 1080;
        int screenHeight = 2400;
        float bottomMargin = 120f;
        float threshold = 150f;

        // Bubble close to bottom center (540, 2280)
        boolean isHovering = FloatingWindowManager.isHoveringDismiss(
                540 - 32,
                2280 - 32,
                64,
                540f,
                2280f,
                screenWidth,
                screenHeight,
                bottomMargin,
                threshold
        );
        assertTrue(isHovering);

        // Bubble far away (100, 500)
        boolean farAway = FloatingWindowManager.isHoveringDismiss(
                100,
                500,
                64,
                100f,
                500f,
                screenWidth,
                screenHeight,
                bottomMargin,
                threshold
        );
        assertFalse(farAway);
    }

    @Test
    public void testPositionParsingAndFormatting() {
        String formatted = FloatingWindowManager.formatPosition(150, 450);
        assertEquals("150,450", formatted);

        int[] parsed = FloatingWindowManager.parsePosition(formatted, 20, 200, 1080, 2400, 64);
        assertEquals(150, parsed[0]);
        assertEquals(450, parsed[1]);

        int[] fallback = FloatingWindowManager.parsePosition("invalid_coords", 20, 200, 1080, 2400, 64);
        assertEquals(20, fallback[0]);
        assertEquals(200, fallback[1]);
    }
}
