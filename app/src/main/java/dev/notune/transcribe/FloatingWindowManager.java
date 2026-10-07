package dev.notune.transcribe;

/**
 * Pure-math layout and gesture physics calculator for floating overlay windows.
 * Decouples snap-to-edge, screen clamping, and drag-to-dismiss threshold
 * calculations from Android WindowManager so they can be unit-tested on plain JVM.
 */
public final class FloatingWindowManager {

    private FloatingWindowManager() {}

    /**
     * Calculates the nearest horizontal edge X coordinate for snapping.
     * Snaps to X=0 if the bubble center is on the left half of the screen,
     * or to (screenWidth - bubbleWidth) if it is on the right half.
     */
    public static int calculateNearestEdgeX(int startX, int bubbleWidth, int screenWidth) {
        int centerX = startX + (bubbleWidth / 2);
        if (centerX < screenWidth / 2) {
            return 0;
        } else {
            return Math.max(0, screenWidth - bubbleWidth);
        }
    }

    /**
     * Clamps the Y position so the bubble stays inside visible screen boundaries,
     * below the status bar and above the bottom edge.
     */
    public static int clampY(int currentY, int bubbleHeight, int screenHeight, int statusBarHeight) {
        int minY = Math.max(0, statusBarHeight);
        int maxY = Math.max(minY, screenHeight - bubbleHeight);
        return Math.max(minY, Math.min(currentY, maxY));
    }

    /**
     * Checks if the floating bubble is hovering over the bottom-center dismiss target.
     *
     * @param bubbleX Current bubble X
     * @param bubbleY Current bubble Y
     * @param bubbleSizePx Bubble diameter in px
     * @param rawTouchX Raw touch event X
     * @param rawTouchY Raw touch event Y
     * @param screenWidth Screen width in px
     * @param screenHeight Screen height in px
     * @param dismissTargetBottomMarginPx Margin from screen bottom to dismiss circle center
     * @param thresholdPx Proximity threshold distance in px
     * @return true if either bubble center or touch point is within threshold of dismiss target center
     */
    public static boolean isHoveringDismiss(
            int bubbleX,
            int bubbleY,
            int bubbleSizePx,
            float rawTouchX,
            float rawTouchY,
            int screenWidth,
            int screenHeight,
            float dismissTargetBottomMarginPx,
            float thresholdPx
    ) {
        float targetCenterX = screenWidth / 2.0f;
        float targetCenterY = screenHeight - dismissTargetBottomMarginPx;

        float bubbleCenterX = bubbleX + (bubbleSizePx / 2.0f);
        float bubbleCenterY = bubbleY + (bubbleSizePx / 2.0f);

        double bubbleDist = Math.hypot(bubbleCenterX - targetCenterX, bubbleCenterY - targetCenterY);
        double touchDist = Math.hypot(rawTouchX - targetCenterX, rawTouchY - targetCenterY);

        return bubbleDist < thresholdPx || touchDist < thresholdPx;
    }

    /**
     * Formats bubble coordinate pair for atomic marker persistence.
     */
    public static String formatPosition(int x, int y) {
        return x + "," + y;
    }

    /**
     * Parses stored bubble coordinates with boundary safety fallback.
     */
    public static int[] parsePosition(String stored, int defaultX, int defaultY, int screenWidth, int screenHeight, int bubbleSizePx) {
        if (stored == null || stored.trim().isEmpty()) {
            return new int[]{defaultX, defaultY};
        }
        int comma = stored.indexOf(',');
        if (comma <= 0) {
            return new int[]{defaultX, defaultY};
        }
        try {
            int x = Integer.parseInt(stored.substring(0, comma).trim());
            int y = Integer.parseInt(stored.substring(comma + 1).trim());
            int maxX = Math.max(0, screenWidth - bubbleSizePx);
            int maxY = Math.max(0, screenHeight - bubbleSizePx);
            return new int[]{
                    Math.max(0, Math.min(x, maxX)),
                    Math.max(0, Math.min(y, maxY))
            };
        } catch (NumberFormatException e) {
            return new int[]{defaultX, defaultY};
        }
    }
}
