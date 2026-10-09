package dev.notune.transcribe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * Pulsing glow that reacts to the mic level (0..1). The glow color follows the
 * Material 3 theme (colorPrimary), so it matches the app, the keyboard, and the
 * recognizer popup in both light and dark mode.
 */
public class MicLevelView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float current = 0f;   // 0..1
    private float target = 0f;    // 0..1
    private int baseColor = Color.WHITE;
    private ValueAnimator animator;

    public MicLevelView(Context c) { super(c); init(); }
    public MicLevelView(Context c, AttributeSet a) { super(c, a); init(); }
    public MicLevelView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        // Resolve the theme's primary color from the (Material-themed) context.
        baseColor = MaterialColors.getColor(this,
                androidx.appcompat.R.attr.colorPrimary, Color.WHITE);
        paint.setColor(baseColor);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Override the glow color (defaults to the theme's colorPrimary). */
    public void setColor(int color) {
        baseColor = color;
        paint.setColor(color);
        invalidate();
    }

    /** level: 0..1 */
    public void setLevel(float level) {
        if (level < 0f) level = 0f;
        if (level > 1f) level = 1f;
        if (level == target && Math.abs(current - target) < 0.0001f) {
            return; // no effective change — skip the animation churn
        }
        target = level;

        // One reused animator instead of a fresh ValueAnimator per call (O4):
        // the mic level updates ~20 times per second, and cancel+recreate
        // churns allocations on the UI thread (visible as dropped frames).
        if (animator == null) {
            animator = new ValueAnimator();
            animator.setDuration(60); // fast, makes it feel "live"
            animator.addUpdateListener(a -> {
                current = (float) a.getAnimatedValue();
                invalidate();
            });
        } else {
            animator.cancel();
        }
        animator.setFloatValues(current, target);
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float min = Math.min(getWidth(), getHeight()) / 2f;

        // Radius: base size + level
        float base = min * 0.42f;
        float extra = min * 0.28f * current;
        float r = base + extra;

        // Inner brighter ring
        int alphaInner = (int) (45 + 90 * current);  // 45..135
        paint.setColor(baseColor);
        paint.setAlpha(alphaInner);
        canvas.drawCircle(cx, cy, r, paint);

        // Outer soft glow (larger, more transparent)
        int alphaOuter = (int) (16 + 40 * current);  // 16..56
        paint.setAlpha(alphaOuter);
        canvas.drawCircle(cx, cy, r * 1.25f, paint);
    }
}
