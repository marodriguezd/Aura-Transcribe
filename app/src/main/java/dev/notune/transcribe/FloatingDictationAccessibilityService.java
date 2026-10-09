package dev.notune.transcribe;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import androidx.annotation.StringRes;

/**
 * Optional Accessibility service that inserts already-recognised dictation into
 * whichever text field the user was last editing in another app.
 *
 * <h3>Scope (deliberately minimal)</h3>
 * The service does exactly one thing: it remembers which <em>editable</em> node
 * currently holds input focus, and later writes the finished transcript into it.
 * It reads no other content, stores nothing (the node reference lives only for
 * the duration of the current session and is released on window change, unbind
 * and destroy), and never performs an action the user did not initiate through
 * the floating bubble. The narrow behaviour is reflected in
 * {@code res/xml/accessibility_service_config.xml}: only
 * {@code typeViewFocused} and {@code typeWindowStateChanged} events are
 * subscribed to, and interactive-window enumeration is not requested.
 *
 * <p>Users are told all of this before they are sent to Android's Accessibility
 * settings — see {@link AccessibilityDisclosureActivity}. Accessibility access is
 * never required: when it is unavailable, {@link #pasteText} falls back to
 * copying the transcript to the clipboard so the user can paste it themselves.
 */
public class FloatingDictationAccessibilityService extends AccessibilityService {

    private static final String TAG = "FloatingAccessibility";
    private static final String CLIP_LABEL = "transcribed_text";

    private static volatile FloatingDictationAccessibilityService sInstance = null;

    /**
     * The editable node that most recently held input focus in a window that is
     * not ours. Needed because once the floating panel takes focus,
     * {@code getRootInActiveWindow()} resolves to the overlay rather than the
     * user's field, so the target has to be remembered from before that.
     */
    private AccessibilityNodeInfo mLastFocusedNode = null;

    public static FloatingDictationAccessibilityService getInstance() {
        return sInstance;
    }

    public static boolean isEnabled() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "FloatingDictationAccessibilityService connected");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (sInstance == this) {
            sInstance = null;
        }
        clearLastFocusedNode();
        Log.i(TAG, "FloatingDictationAccessibilityService unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        clearLastFocusedNode();
        super.onDestroy();
        Log.i(TAG, "FloatingDictationAccessibilityService destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The event stream comes from every window on screen and can race with
        // windows closing. A single uncaught exception here crashes the whole
        // accessibility service, which the system then shows as "keeps stopping"
        // and repeatedly restarts — perceived as constant app crashes. Never let
        // an event take the service down.
        try {
            if (event == null) {
                return;
            }
            int eventType = event.getEventType();

            // The focused window changed: the previously remembered node belongs
            // to a window that may already be gone. Release it so a later
            // performInsert() cannot target a stale/destroyed node.
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                clearLastFocusedNode();
                return;
            }

            if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                AccessibilityNodeInfo source = event.getSource();
                if (source == null) return;
                if (source.isEditable() && source.isEnabled()) {
                    clearLastFocusedNode();
                    mLastFocusedNode = source;
                } else {
                    release(source);
                }
            }
        } catch (RuntimeException t) {
            // Never crash the accessibility service from a single bad event.
            Log.w(TAG, "Error handling accessibility event", t);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "FloatingDictationAccessibilityService interrupted");
    }

    private synchronized void clearLastFocusedNode() {
        if (mLastFocusedNode != null) {
            release(mLastFocusedNode);
            mLastFocusedNode = null;
        }
    }

    /**
     * Releases a node reference. {@code AccessibilityNodeInfo.recycle()} is a
     * no-op (and deprecated) from API 33, where these objects are ordinary
     * garbage-collected references; below that it is required to return the
     * pooled instance.
     */
    private static void release(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                node.recycle();
            } catch (RuntimeException e) {
                // Already recycled — nothing to do.
            }
        }
    }

    /**
     * Copies a node reference so the cached node can be handed to the insertion
     * path without either side recycling the other's instance. Below API 33 this
     * must be a real {@code obtain()}; from API 33 the reference is simply shared.
     */
    private static AccessibilityNodeInfo copyOf(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                return AccessibilityNodeInfo.obtain(node);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return node;
    }

    /**
     * Inserts {@code text} into the active focused text field, falling back to
     * the clipboard when that is not possible.
     *
     * @return true if the text was inserted <em>or</em> copied to the clipboard.
     */
    public static boolean pasteText(Context context, CharSequence text) {
        FloatingDictationAccessibilityService service = sInstance;
        if (service != null && service.performInsert(text)) {
            return true;
        }
        return copyToClipboardFallback(context, text);
    }

    /**
     * Tiered text insertion into the currently focused window node:
     * <ol>
     *   <li>{@code ACTION_PASTE} via the clipboard (respects the cursor and the
     *       current selection, and notifies the field's own text watchers), then</li>
     *   <li>{@code ACTION_SET_TEXT} with an explicit inserted-at-cursor string and
     *       an explicit resulting selection, then</li>
     *   <li>clipboard write + toast.</li>
     * </ol>
     */
    public boolean performInsert(CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }

        AccessibilityNodeInfo rootNode = null;
        AccessibilityNodeInfo targetNode = null;
        boolean targetIsOwned = false;

        try {
            rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                // findFocus() hands back a new instance that we own and must
                // release; the cached node is only borrowed.
                targetNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (targetNode == null) {
                    targetNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                }
                targetIsOwned = targetNode != null;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error finding focus node in active window", e);
        } finally {
            release(rootNode);
        }

        if (targetNode == null) {
            synchronized (this) {
                if (mLastFocusedNode != null) {
                    targetNode = copyOf(mLastFocusedNode);
                    targetIsOwned = targetNode != null;
                }
            }
        }

        try {
            if (targetNode != null && targetNode.isEditable() && targetNode.isEnabled()) {
                // Priority 1: ACTION_PASTE inserts at the cursor and replaces the
                // selection, exactly like a user pressing Paste.
                if (setClipboardSafely(this, text) && targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    Log.i(TAG, "Inserted text via ACTION_PASTE");
                    return true;
                }

                // Priority 2: ACTION_SET_TEXT. The previous implementation
                // concatenated `existing + " " + text`, which ignored the cursor
                // and the selection entirely: dictating into the middle of a
                // sentence appended to the end, and dictating over a selection
                // destroyed the field's content order. Insert at the real
                // selection instead, and leave the caret after the inserted text.
                int selStart = targetNode.getTextSelectionStart();
                int selEnd = targetNode.getTextSelectionEnd();
                CharSequence existing = targetNode.getText();
                String base = existing == null ? "" : existing.toString();

                CharSequence combined = insertAtSelection(base, selStart, selEnd, text.toString());
                int caret = caretAfterInsert(base, selStart, selEnd, text.length());

                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined);
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
                if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    Log.i(TAG, "Inserted text via ACTION_SET_TEXT at cursor");
                    return true;
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error performing accessibility action", e);
        } finally {
            if (targetIsOwned) {
                release(targetNode);
            }
        }

        // Priority 3: fallback clipboard write.
        return copyToClipboardFallback(this, text);
    }

    /**
     * Splices {@code insert} into {@code existing} at the current selection,
     * replacing the selected range. Pure and side-effect free so the insertion
     * contract is unit-testable without a device.
     *
     * <p>An unknown or out-of-range selection (some fields report -1) degrades to
     * appending at the end, which is the previous behaviour for the cases where
     * the selection genuinely is not knowable.
     */
    static String insertAtSelection(String existing, int selStart, int selEnd, String insert) {
        String base = existing == null ? "" : existing;
        String addition = insert == null ? "" : insert;
        int start = selStart;
        int end = selEnd;
        if (start < 0 || end < 0 || start > base.length() || end > base.length() || start > end) {
            start = base.length();
            end = base.length();
        }
        return base.substring(0, start) + addition + base.substring(end);
    }

    /** Caret offset after {@link #insertAtSelection} has been applied. */
    static int caretAfterInsert(String existing, int selStart, int selEnd, int insertLength) {
        String base = existing == null ? "" : existing;
        int start = selStart;
        int end = selEnd;
        if (start < 0 || end < 0 || start > base.length() || end > base.length() || start > end) {
            start = base.length();
        }
        return start + Math.max(insertLength, 0);
    }

    /**
     * Writes to the clipboard without ever throwing. On Android 10+ a background
     * app cannot read the clipboard, but writing it is permitted, which is all
     * the paste path needs.
     */
    private static boolean setClipboardSafely(Context context, CharSequence text) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not write to the clipboard", e);
            return false;
        }
    }

    /**
     * Last-resort fallback: copy the transcript and tell the user, so the text is
     * never lost when direct insertion is impossible (no focused field, a field
     * that refuses the action, or a locked-down app).
     */
    public static boolean copyToClipboardFallback(Context context, CharSequence text) {
        if (context == null || text == null || text.length() == 0) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        if (!setClipboardSafely(appContext, text)) {
            // Both targets failed: direct insertion was refused and the clipboard
            // write threw. There is no third mechanism, and returning false
            // silently dropped the transcript — the caller only learns that
            // nothing happened. Say so, so the user can read it off the overlay
            // panel and copy it by hand.
            Log.e(TAG, "Neither accessibility insertion nor the clipboard write succeeded");
            toast(appContext, R.string.accessibility_paste_failed);
            return false;
        }
        toast(appContext, R.string.a11y_copied_to_clipboard);
        return true;
    }

    /** Shows a toast from the main looper, never throwing (a background app can be
     *  refused the right to show it, which must not break the caller). */
    private static void toast(Context appContext, @StringRes int resId) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not show the toast", e);
            }
        });
    }
}
