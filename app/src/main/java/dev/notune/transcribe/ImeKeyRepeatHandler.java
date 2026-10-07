package dev.notune.transcribe;

import android.os.Handler;
import android.os.Looper;

/**
 * Encapsulates the key-repeat timer logic (initial delay + continuous interval)
 * for backspace, space, and custom buttons in InputMethodService.
 * Decouples timer scheduling from the IME service class.
 */
public class ImeKeyRepeatHandler {

    private final Handler handler;
    private final long initialDelayMs;
    private final long repeatIntervalMs;
    private final Runnable action;
    private boolean isRepeating = false;

    private final Runnable repeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRepeating) return;
            action.run();
            handler.postDelayed(this, repeatIntervalMs);
        }
    };

    public ImeKeyRepeatHandler(Handler handler, long initialDelayMs, long repeatIntervalMs, Runnable action) {
        this.handler = handler != null ? handler : new Handler(Looper.getMainLooper());
        this.initialDelayMs = initialDelayMs;
        this.repeatIntervalMs = repeatIntervalMs;
        this.action = action;
    }

    public void start() {
        stop();
        isRepeating = true;
        action.run();
        handler.postDelayed(repeatRunnable, initialDelayMs);
    }

    public void stop() {
        isRepeating = false;
        handler.removeCallbacks(repeatRunnable);
    }

    public boolean isRepeating() {
        return isRepeating;
    }
}
