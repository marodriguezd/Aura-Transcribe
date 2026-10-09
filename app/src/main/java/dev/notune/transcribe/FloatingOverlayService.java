package dev.notune.transcribe;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.materialswitch.MaterialSwitch;

import androidx.core.app.NotificationCompat;

import java.io.File;

/**
 * Foreground service managing the floating dictation bubble overlay (Whisperflow style).
 * Displays a draggable floating bubble icon on screen, which expands into a dictation control
 * panel for speech recognition, AI Fix post-processing toggle, real-time partial hypothesis display,
 * and direct insertion into active text fields via Accessibility.
 *
 * Crash-hardening (2026-08-12): the service must NEVER crash on startup. A crash here used to
 * produce a restart loop ("crash constantes"): with START_STICKY the system restarts a service
 * that crashed in onCreate, which crashed again. The two classic startup failures are
 * (a) startForeground() with the manifest's "microphone" FGS type while RECORD_AUDIO is not
 * granted (SecurityException), and (b) WindowManager.addView() while the overlay permission is
 * missing/revoked (BadTokenException/SecurityException). Both are now checked up-front and every
 * fallible setup step is wrapped, degrading to a graceful stopSelf() + guidance notification
 * instead of an uncaught exception.
 */
public class FloatingOverlayService extends Service {

    private static final String TAG = "FloatingOverlayService";
    private static final String CHANNEL_ID = "floating_dictation_channel";
    private static final int NOTIFICATION_ID = 404;
    private static final int SETUP_ERROR_NOTIFICATION_ID = 405;

    public static final String ACTION_START = "dev.notune.transcribe.action.START_FLOATING";
    public static final String ACTION_STOP = "dev.notune.transcribe.action.STOP_FLOATING";

    // Marker file in filesDir() holding the bubble position as "x,y", so the
    // bubble comes back where the user left it across service restarts.
    private static final String BUBBLE_POS_MARKER = "floating_bubble_pos";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (Throwable t) {
            try {
                Log.e(TAG, "Failed to load native libraries", t);
            } catch (Throwable ignored) {}
        }
    }

    private WindowManager mWindowManager;
    private View mOverlayView;
    private WindowManager.LayoutParams mParams;
    private boolean mViewIsNight;

    private View mBubbleRoot;
    private View mBubbleCircle;
    private MicLevelView mBubbleMicLevel;
    private View mPanelRoot;
    private View mRecordCircle;

    // Subtle "listening" pulse on the record circles while a session is
    // active (same feel as the IME's pulsing mic glow). One reused animator.
    private ValueAnimator mPulseAnimator;

    private TextView mStatusText;
    private ProgressBar mProgress;
    private MaterialSwitch mPpToggle;
    private Button mCancelButton;
    private ImageView mCloseButton;
    private ScrollView mPartialScroll;
    private TextView mPartialText;

    private View mRecordContainer;
    private MicLevelView mMicLevelView;
    private ImageView mBubbleIcon;
    private ImageView mMicIcon;
    private Button mInsertButton;
    private TextView mHintText;

    // Bubble position (window offset) kept while the panel is expanded, so the
    // bubble returns exactly where the user left it after collapsing.
    private int mBubbleX = 20;
    private int mBubbleY = 200;

    // Drag-to-dismiss target view overlay at bottom center of screen
    private View mDismissTargetView;
    private View mDismissCircle;
    private WindowManager.LayoutParams mDismissParams;
    private boolean mIsDismissTargetAttached = false;
    private boolean mIsHoveringDismiss = false;

    // Safety net that stops the service if the fade animation gets cancelled.
    private final Runnable mFadeStopFallback = this::stopSelf;

    private static final long INACTIVITY_DOCK_DELAY_MS = 2500L;
    private ValueAnimator mSnapAnimator;
    private ValueAnimator mDockAnimator;
    private boolean mIsDocked = false;
    private static volatile boolean sIsRunning = false;

    public static boolean isRunning() {
        return sIsRunning;
    }

    private final Runnable mInactivityRunnable = this::enterDockedState;
    private int mLastScreenWidth = 0;

    private Handler mMainHandler;
    private boolean mIsRecording = false;
    private boolean mResultPending = false;
    private volatile int mCurrentSessionId = 0;
    private String mLastStatus = "Ready";
    private String mLastRawTranscript = null;
    private String mTranscribedResult = null;
    private boolean mIsDestroyed = false;
    private final AudioRecordBridge mAudioRecordBridge = new AudioRecordBridge();

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(Looper.getMainLooper());
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // The manifest declares foregroundServiceType="microphone", so
        // startForeground() REQUIRES RECORD_AUDIO on API 31+. Without the
        // check below, startForeground() throws SecurityException, onCreate
        // crashes, and START_STICKY restarts the service into a crash loop.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; refusing to start floating service");
            notifySetupError(
                    getString(R.string.floating_service_name),
                    getString(R.string.floating_need_mic_body));
            stopSelf();
            return;
        }

        // addView() on a TYPE_APPLICATION_OVERLAY window throws
        // BadTokenException/SecurityException without the overlay permission.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; refusing to start floating service");
            notifySetupError(
                    getString(R.string.floating_overlay_permission_title),
                    getString(R.string.floating_overlay_permission_msg));
            stopSelf();
            return;
        }

        try {
            initNative(this);
        } catch (Throwable t) {
            // A dead native bridge means every later recording call would throw
            // UnsatisfiedLinkError mid-use. Fail fast and gracefully instead of
            // running a service that can only crash later.
            Log.e(TAG, "Error in initNative", t);
            notifySetupError(
                    getString(R.string.floating_service_name),
                    getString(R.string.floating_start_failed_body));
            stopSelf();
            return;
        }

        try {
            createNotificationChannel();
            // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE only exists from API 30
            // (R): on API 26-29 the two-arg startForeground() is the correct call
            // (the microphone FGS type is not defined on those releases). Passing
            // the type bit to an API-29 platform would not match the manifest's
            // declared type mask and can throw IllegalArgumentException.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable t) {
            // SecurityException (FGS type enforcement), MissingForegroundServiceTypeException,
            // or any other unexpected failure must never crash the process into a restart loop.
            Log.e(TAG, "Failed to start foreground service", t);
            notifySetupError(
                    getString(R.string.floating_service_name),
                    getString(R.string.floating_start_failed_body));
            stopSelf();
            return;
        }

        try {
            setupOverlayView();
            SettingsManager sm = new SettingsManager(this);
            AudioDeviceManager.prewarmMicrophone(this, sm.getMicMode());
            sIsRunning = true;
        } catch (Throwable t) {
            // WindowManager.addView() failing (e.g. permission revoked between the check and
            // the add) is a setup failure, not a crash.
            Log.e(TAG, "Failed to attach overlay view", t);
            notifySetupError(
                    getString(R.string.floating_service_name),
                    getString(R.string.floating_start_failed_body));
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_service_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.floating_service_description));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        // "Stop" action lets the user dismiss the floating bubble from the
        // notification shade without opening the app.
        Intent stopIntent = new Intent(this, FloatingOverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.floating_service_name))
                .setContentText(getString(R.string.floating_service_description))
                .setSmallIcon(R.drawable.ic_mic)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                // App-owned glyph instead of a framework drawable: Android's
                // framework icons are not themed, are not guaranteed to exist
                // across OEM builds, and make the notification look like a
                // system message rather than a product one.
                .addAction(R.drawable.ic_close,
                        getString(R.string.floating_notification_stop), stopPi)
                .build();
    }

    /**
     * Shows a non-foreground guidance notification when the service cannot start
     * (missing permission, overlay attach failure). Tapping it reopens the main
     * activity so the user can grant what is missing. Never throws.
     */
    /**
     * Posts the "this service could not start" notification.
     *
     * <p>The title is passed in rather than hard-coded to the service name: the
     * overlay-permission failure is a case where the *reason* is the actionable
     * part, and a bare service name gives the user nothing to act on.
     */
    private void notifySetupError(String title, String body) {
        try {
            createNotificationChannel();
            Intent contentIntent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    0,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_mic)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(SETUP_ERROR_NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to show setup error notification", t);
        }
    }

    private void setupOverlayView() {
        Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
        mViewIsNight = ThemePrefs.isNight(night);
        Context themed = new android.view.ContextThemeWrapper(night, R.style.AppTheme);
        // Root = null on purpose (lint's InflateParams suggestion does not apply):
        // this view is attached by WindowManager, which is not a ViewGroup. Its
        // WindowManager.LayoutParams are built explicitly below, so the root
        // layout's own layout_* attributes are intentionally ignored.
        mOverlayView = LayoutInflater.from(themed).inflate(R.layout.overlay_floating_dictation, null);

        mBubbleRoot = mOverlayView.findViewById(R.id.floating_bubble_root);
        mBubbleCircle = mOverlayView.findViewById(R.id.floating_bubble_circle);
        mBubbleMicLevel = mOverlayView.findViewById(R.id.floating_bubble_mic_level);
        mPanelRoot = mOverlayView.findViewById(R.id.floating_panel_root);

        mStatusText = mOverlayView.findViewById(R.id.floating_status_text);
        mProgress = mOverlayView.findViewById(R.id.floating_progress);
        mPpToggle = mOverlayView.findViewById(R.id.floating_pp_toggle);
        mCancelButton = mOverlayView.findViewById(R.id.floating_cancel_button);
        mCloseButton = mOverlayView.findViewById(R.id.floating_close_button);
        mPartialScroll = mOverlayView.findViewById(R.id.floating_partial_scroll);
        mPartialText = mOverlayView.findViewById(R.id.floating_partial_text);

        mRecordContainer = mOverlayView.findViewById(R.id.floating_record_container);
        mMicLevelView = mOverlayView.findViewById(R.id.floating_mic_level);
        mRecordCircle = mOverlayView.findViewById(R.id.floating_record_circle);
        mBubbleIcon = mOverlayView.findViewById(R.id.floating_bubble_icon);
        mMicIcon = mOverlayView.findViewById(R.id.floating_mic_icon);
        mInsertButton = mOverlayView.findViewById(R.id.floating_insert_button);
        mHintText = mOverlayView.findViewById(R.id.floating_hint);

        int layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        mParams.gravity = Gravity.TOP | Gravity.START;
        loadBubblePosition();
        mParams.x = mBubbleX;
        mParams.y = mBubbleY;

        setupGestureAndListeners();
        mWindowManager.addView(mOverlayView, mParams);
        mLastScreenWidth = getRealScreenWidth();
        scheduleInactivityTimer();
    }

    private void setupGestureAndListeners() {
        // Drag bubble vs tap to toggle panel.
        // Dragging displays a dismiss target ("X") at the bottom of the screen.
        // Releasing the bubble over the "X" target closes the overlay entirely.
        // Holding/long-pressing allows moving the bubble around without closing.
        mBubbleRoot.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // The service may be torn down while a drag gesture is in flight;
                // never touch the window manager after destruction.
                if (mIsDestroyed) {
                    return true;
                }
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelInactivityTimer();
                        cancelAnimators();
                        undockAndRestoreOpacity(false);
                        initialX = mParams.x;
                        initialY = mParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        showDismissTarget();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isClick = false;
                        }
                        mParams.x = initialX + dx;
                        mParams.y = initialY + dy;
                        // Keep the bubble position in sync for the panel expand/collapse
                        // window resize (dragging only happens on the bubble itself).
                        mBubbleX = mParams.x;
                        mBubbleY = mParams.y;
                        if (mWindowManager != null && mOverlayView != null) {
                            try {
                                mWindowManager.updateViewLayout(mOverlayView, mParams);
                            } catch (Throwable t) {
                                // View may already be detached (service stopping); ignore.
                                Log.w(TAG, "updateViewLayout failed during drag", t);
                            }
                        }
                        updateDismissTargetHover(event.getRawX(), event.getRawY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        // Report the tap as a click: this custom onTouch handler
                        // replaces the framework's click dispatch, so without
                        // this an accessibility service receives no click event
                        // from the bubble (ClickableViewAccessibility).
                        v.performClick();
                        boolean dismissRequested = mIsHoveringDismiss;
                        hideDismissTarget();
                        if (dismissRequested) {
                            fadeOutAndStop();
                        } else if (isClick) {
                            togglePanel();
                        } else {
                            snapToNearestEdge(null);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        hideDismissTarget();
                        snapToNearestEdge(null);
                        return true;
                }
                return false;
            }
        });

        mCloseButton.setOnClickListener(v -> collapsePanel());

        if (mPpToggle != null) {
            // Starts disabled and off: whether AI fix is on *and usable* comes from
            // an ordered credential read on the lane (syncPostProcessToggle), never
            // from storage or AndroidKeyStore I/O on this (UI) thread.
            mPpToggle.setEnabled(false);
            mPpToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (mSuppressPpToggle) return;
                final SettingsManager sm = new SettingsManager(FloatingOverlayService.this);
                if (!isChecked) {
                    sm.setPostProcessEnabled(false);
                    if (!mIsRecording && mStatusText != null
                            && getString(R.string.ime_refining).equals(mStatusText.getText().toString())) {
                        PostProcessor.cancelAllFor(FloatingOverlayService.this);
                        if (mLastRawTranscript != null && !mLastRawTranscript.trim().isEmpty()) {
                            deliverFinalText(mLastRawTranscript);
                        }
                    }
                    return;
                }
                // Turning it on needs a usable credential: ask the lane, then
                // persist only if the answer is yes.
                sm.readPostProcessEnabled(mMainHandler::post, usable -> {
                    if (mIsDestroyed) return;
                    if (!usable) {
                        mSuppressPpToggle = true;
                        try {
                            buttonView.setChecked(false);
                        } finally {
                            mSuppressPpToggle = false;
                        }
                        sm.setPostProcessEnabled(false);
                        Toast.makeText(FloatingOverlayService.this,
                                R.string.pp_not_configured_prompt, Toast.LENGTH_LONG).show();
                        return;
                    }
                    sm.setPostProcessEnabled(true);
                });
            });
            syncPostProcessToggle();
        }

        mCancelButton.setOnClickListener(v -> cancelCurrentTranscription());

        mRecordContainer.setOnClickListener(v -> {
            if (mIsRecording) {
                stopRecordingSession();
            } else {
                startRecordingSession();
            }
        });

        mInsertButton.setOnClickListener(v -> {
            if (mTranscribedResult != null && !mTranscribedResult.trim().isEmpty()) {
                FloatingDictationAccessibilityService.pasteText(this, mTranscribedResult);
                mTranscribedResult = null;
                mInsertButton.setVisibility(View.GONE);
                collapsePanel();
            }
        });
    }

    private void setupDismissTargetView() {
        Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
        Context themed = new android.view.ContextThemeWrapper(night, R.style.AppTheme);
        // Root = null on purpose; see setupOverlayView().
        mDismissTargetView = LayoutInflater.from(themed).inflate(R.layout.overlay_dismiss_target, null);
        mDismissCircle = mDismissTargetView.findViewById(R.id.dismiss_target_circle);

        float density = getResources().getDisplayMetrics().density;
        int layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        mDismissParams = new WindowManager.LayoutParams(
                (int) (72 * density),
                (int) (72 * density),
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        mDismissParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mDismissParams.y = systemBarInsets()[1] + (int) (36 * density);
    }

    private void showDismissTarget() {
        if (mWindowManager == null || mIsDestroyed) return;
        if (mDismissTargetView == null) {
            setupDismissTargetView();
        }
        if (mDismissTargetView != null && !mIsDismissTargetAttached) {
            try {
                mDismissTargetView.setAlpha(0f);
                mDismissTargetView.setScaleX(0.6f);
                mDismissTargetView.setScaleY(0.6f);
                mWindowManager.addView(mDismissTargetView, mDismissParams);
                mIsDismissTargetAttached = true;
                mDismissTargetView.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .start();
            } catch (Throwable t) {
                Log.w(TAG, "Failed to show dismiss target overlay", t);
            }
        }
        setDismissHoverState(false);
    }

    private void updateDismissTargetHover(float rawX, float rawY) {
        if (!mIsDismissTargetAttached || mDismissTargetView == null || mDismissParams == null) return;
        float density = getResources().getDisplayMetrics().density;

        int screenWidth = getRealScreenWidth();
        int screenHeight = getRealScreenHeight();

        float targetCenterX = screenWidth / 2f;
        float targetCenterY = screenHeight - mDismissParams.y - (36 * density);

        float bubbleCenterX = mParams.x + (32 * density);
        float bubbleCenterY = mParams.y + (32 * density);

        double bubbleDist = Math.hypot(bubbleCenterX - targetCenterX, bubbleCenterY - targetCenterY);
        double touchDist = Math.hypot(rawX - targetCenterX, rawY - targetCenterY);

        float threshold = 100 * density;
        boolean hovering = bubbleDist < threshold || touchDist < threshold;

        if (hovering != mIsHoveringDismiss) {
            setDismissHoverState(hovering);
            if (hovering && mBubbleRoot != null) {
                try {
                    mBubbleRoot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void setDismissHoverState(boolean hovering) {
        mIsHoveringDismiss = hovering;
        if (mDismissCircle != null) {
            if (hovering) {
                mDismissCircle.setBackgroundResource(R.drawable.bg_dismiss_circle_hover);
                mDismissCircle.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start();
            } else {
                mDismissCircle.setBackgroundResource(R.drawable.bg_dismiss_circle);
                mDismissCircle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        }
    }

    private void hideDismissTarget() {
        if (mWindowManager != null && mDismissTargetView != null && mIsDismissTargetAttached) {
            mDismissTargetView.animate()
                    .alpha(0f)
                    .scaleX(0.6f)
                    .scaleY(0.6f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        if (mIsDismissTargetAttached && mWindowManager != null && mDismissTargetView != null) {
                            try {
                                mWindowManager.removeView(mDismissTargetView);
                            } catch (Throwable t) {
                                Log.w(TAG, "Failed to remove dismiss target view", t);
                            }
                            mIsDismissTargetAttached = false;
                        }
                    })
                    .start();
        }
        mIsHoveringDismiss = false;
    }

    private void togglePanel() {
        if (mPanelRoot.getVisibility() == View.VISIBLE) {
            collapsePanel();
        } else {
            expandPanel();
        }
    }

    private void expandPanel() {
        cancelInactivityTimer();
        undockAndRestoreOpacity(false);
        mBubbleRoot.setVisibility(View.GONE);
        mPanelRoot.setVisibility(View.VISIBLE);
        if (mPpToggle != null) {
            syncPostProcessToggle();
        }
        // Full-width panel, IME style: stretch the window to near-screen width
        // and anchor it below the status bar, centered horizontally.
        resizeWindow(true);
    }

    private void collapsePanel() {
        mPanelRoot.setVisibility(View.GONE);
        mBubbleRoot.setVisibility(View.VISIBLE);
        undockAndRestoreOpacity(false);
        // Back to the small draggable bubble at the saved position.
        resizeWindow(false);
        scheduleInactivityTimer();
    }

    /**
     * Switches the overlay window between the small bubble (wrap content at the
     * dragged position) and the full-width dictation panel (near-screen width,
     * top-anchored). Only the width/gravity/offset change; height stays wrap.
     */
    private void resizeWindow(boolean expanded) {
        if (mWindowManager == null || mOverlayView == null || mParams == null) {
            return;
        }
        try {
            if (expanded) {
                float density = getResources().getDisplayMetrics().density;
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int margin = (int) (16 * density);
                int[] bars = systemBarInsets();
                int statusBar = bars[0];
                int navBar = bars[1];

                int panelWidth = screenWidth - margin * 2;
                int panelHeight = measurePanelHeight(panelWidth);

                // Open the panel as close as possible to where the bubble is
                // instead of always pinning it under the status bar: the panel's
                // vertical center lines up with the bubble's center. The result
                // is clamped so the whole panel stays on screen.
                int screenHeight = getRealScreenHeight();
                int bubbleHeight = mBubbleRoot != null && mBubbleRoot.getHeight() > 0
                        ? mBubbleRoot.getHeight() : (int) (64 * density);
                int bubbleCenterY = mBubbleY + bubbleHeight / 2;
                int minTop = statusBar + margin;
                int maxTop = screenHeight - navBar - panelHeight - margin;
                int desiredTop = bubbleCenterY - panelHeight / 2;
                int top = Math.max(minTop, Math.min(desiredTop, Math.max(minTop, maxTop)));

                mParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                mParams.width = panelWidth;
                mParams.x = 0;
                mParams.y = top;
            } else {
                mParams.gravity = Gravity.TOP | Gravity.START;
                mParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
                mParams.x = mBubbleX;
                mParams.y = mBubbleY;
            }
            mWindowManager.updateViewLayout(mOverlayView, mParams);
        } catch (Throwable t) {
            // Never crash on a cosmetic resize failure; the panel still shows
            // with its previous size.
            Log.w(TAG, "resizeWindow failed", t);
        }
    }

    /**
     * Full-screen height in overlay coordinates. The overlay window uses
     * FLAG_LAYOUT_IN_SCREEN, so this includes status bar and nav bar areas.
     */
    private int getRealScreenHeight() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return mWindowManager.getCurrentWindowMetrics().getBounds().height();
            }
            Point size = new Point();
            mWindowManager.getDefaultDisplay().getRealSize(size);
            return size.y;
        } catch (Throwable t) {
            return getResources().getDisplayMetrics().heightPixels;
        }
    }

    /**
     * Measures the expanded panel at the target width so its height is known
     * before clamping the panel position on screen. Falls back to a safe
     * estimate if the view cannot be measured.
     */
    private int measurePanelHeight(int panelWidth) {
        try {
            int widthSpec = View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            mPanelRoot.measure(widthSpec, heightSpec);
            return mPanelRoot.getMeasuredHeight();
        } catch (Throwable t) {
            return (int) (280 * getResources().getDisplayMetrics().density);
        }
    }

    /**
     * Restores the bubble position saved in the marker file, clamped to the
     * current screen so a position saved on a different screen size (e.g.
     * after rotation) can never leave the bubble off-screen.
     */
    private void loadBubblePosition() {
        String saved = MarkerFileHelper.readString(this, BUBBLE_POS_MARKER, null);
        if (saved == null || saved.isEmpty()) return;
        int comma = saved.indexOf(',');
        if (comma <= 0) return;
        try {
            int x = Integer.parseInt(saved.substring(0, comma).trim());
            int y = Integer.parseInt(saved.substring(comma + 1).trim());
            float density = getResources().getDisplayMetrics().density;
            int bubbleSize = (int) (64 * density);
            int maxX = Math.max(0, getRealScreenWidth() - bubbleSize);
            int maxY = Math.max(0, getRealScreenHeight() - bubbleSize);
            mBubbleX = Math.max(0, Math.min(x, maxX));
            mBubbleY = Math.max(0, Math.min(y, maxY));
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid saved bubble position", e);
        }
    }

    private void saveBubblePosition() {
        MarkerFileHelper.writeString(this, BUBBLE_POS_MARKER, mBubbleX + "," + mBubbleY);
    }

    /**
     * The status-bar (index 0) and navigation-bar (index 1) insets, in overlay
     * coordinates.
     *
     * <p>These come from real window insets, not from the platform's internal
     * {@code @android:dimen/status_bar_height} and
     * {@code @android:dimen/navigation_bar_height}. That approach was wrong in
     * three ways: those ids are not public API (absent on some OEM builds, which
     * is why the old code carried a {@code > 0} guard), resolving them is a
     * resource-reflection lookup that neither resource shrinking nor R8 can
     * verify, and on gesture navigation the reported navigation-bar height does
     * not match the inset the gesture area actually reserves — so the bubble and
     * the dismiss target could sit under the gesture pill on Android 11+.
     *
     * <p>API 30+ uses {@link WindowManager#getCurrentWindowMetrics()}, which is
     * the supported source and is already how this class resolves the real
     * screen size. API 26-29 reads the insets of the window the overlay is
     * attached to. Returning {@code {0, 0}} is the safe degradation: the overlay
     * then simply ignores the system bars, which is the pre-existing behaviour
     * when the internal resource was missing.
     */
    private int[] systemBarInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mWindowManager != null) {
            try {
                WindowInsets insets = mWindowManager.getCurrentWindowMetrics().getWindowInsets();
                Insets bars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                return new int[]{bars.top, bars.bottom};
            } catch (Throwable t) {
                Log.w(TAG, "WindowMetrics insets unavailable; falling back", t);
            }
        }
        try {
            View anchor = mOverlayView != null ? mOverlayView : mDismissTargetView;
            if (anchor != null) {
                WindowInsets insets = anchor.getRootWindowInsets();
                if (insets != null) {
                    return new int[]{
                            insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetBottom()};
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Root window insets unavailable", t);
        }
        return new int[]{0, 0};
    }

    /**
     * Full-screen width in overlay coordinates (mirror of
     * {@link #getRealScreenHeight()}).
     */
    private int getRealScreenWidth() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return mWindowManager.getCurrentWindowMetrics().getBounds().width();
            }
            Point size = new Point();
            mWindowManager.getDefaultDisplay().getRealSize(size);
            return size.x;
        } catch (Throwable t) {
            return getResources().getDisplayMetrics().widthPixels;
        }
    }

    private void startRecordingSession() {
        cancelInactivityTimer();
        undockAndRestoreOpacity(false);
        mIsRecording = true;
        mResultPending = false;
        mLastRawTranscript = null;
        mTranscribedResult = null;
        mInsertButton.setVisibility(View.GONE);
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mHintText != null) mHintText.setText(R.string.ime_tap_to_stop);
        mStatusText.setText(getString(R.string.floating_status_listening));
        mCancelButton.setVisibility(View.VISIBLE);
        startPulseAnimation();
        SettingsManager sm = new SettingsManager(this);
        AudioDeviceManager.acquireMicrophone(this, sm.getMicMode());
        startRecording(new File(getFilesDir(), "auto_stop").exists(), ++mCurrentSessionId);
        int sid = mCurrentSessionId;
        boolean started = mAudioRecordBridge.start(this, sm.getMicMode(), new AudioRecordBridge.Callback() {
            @Override
            public void onAudioChunk(java.nio.ByteBuffer directBuffer, int bytesRead) {
                pushAudioDirect(directBuffer, bytesRead, sid);
            }
            @Override
            public void onAudioLevel(float level) {}
            @Override
            public void onError(String message) {
                Log.e(TAG, "AudioRecord error: " + message);
            }
        });
        if (!started) {
            cancelCurrentTranscription();
            return;
        }
        boolean isBt = AudioDeviceManager.shouldShowHeadsetIcon(this);
        if (mBubbleIcon != null) mBubbleIcon.setImageResource(isBt ? R.drawable.ic_headset : R.drawable.ic_mic);
        if (mMicIcon != null) mMicIcon.setImageResource(isBt ? R.drawable.ic_headset : R.drawable.ic_mic);
    }

    private void stopRecordingSession() {
        cancelInactivityTimer();
        undockAndRestoreOpacity(false);
        mAudioRecordBridge.stop();
        mIsRecording = false;
        mResultPending = true;
        mStatusText.setText(getString(R.string.floating_status_transcribing));
        if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
        if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
        stopPulseAnimation();
        AudioDeviceManager.releaseMicrophone(this);
        stopRecording();
    }

    private void cancelCurrentTranscription() {
        mCurrentSessionId++;
        mAudioRecordBridge.stop();
        mIsRecording = false;
        mResultPending = false;
        mLastRawTranscript = null;
        mTranscribedResult = null;
        try { cancelRecording(); } catch (Throwable ignored) { }
        AudioDeviceManager.releaseMicrophone(this);
        PostProcessor.cancelAllFor(this);
        stopPulseAnimation();
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
        mStatusText.setText(getString(R.string.floating_status_ready));
        mCancelButton.setVisibility(View.GONE);
        mInsertButton.setVisibility(View.GONE);
        clearPartialText();
        scheduleInactivityTimer();
    }

    /**
     * Breathing scale pulse on the mic circles while listening, matching the
     * IME's live feel. Stopped on stop/cancel/error/destroy; always resets the
     * scale so a later session restarts from a clean state.
     */
    private void startPulseAnimation() {
        stopPulseAnimation();
        mPulseAnimator = ValueAnimator.ofFloat(1f, 1.08f);
        mPulseAnimator.setDuration(600);
        mPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mPulseAnimator.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            if (mRecordCircle != null) {
                mRecordCircle.setScaleX(s);
                mRecordCircle.setScaleY(s);
            }
            if (mBubbleCircle != null) {
                mBubbleCircle.setScaleX(s);
                mBubbleCircle.setScaleY(s);
            }
        });
        mPulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
            mPulseAnimator = null;
        }
        if (mRecordCircle != null) {
            mRecordCircle.setScaleX(1f);
            mRecordCircle.setScaleY(1f);
        }
        if (mBubbleCircle != null) {
            mBubbleCircle.setScaleX(1f);
            mBubbleCircle.setScaleY(1f);
        }
    }

    /**
     * Feedback when the user long-presses the bubble to stop it: fade the
     * overlay out before stopping so the dismissal feels deliberate (the app
     * is usually in the background here, where a toast may be suppressed on
     * Android 12+, so the animation is the reliable cue). A best-effort toast
     * is attempted too — it shows on older devices or when the app happens to
     * be in the foreground.
     */
    private void fadeOutAndStop() {
        // On Android 12+ a background app's toast is reduced to a bare app icon
        // (no text), which looks like a glitch — the fade animation is the real
        // cue there, so only attempt the toast on older versions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                Toast.makeText(this, R.string.floating_stopped, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
        if (mOverlayView != null && !mIsDestroyed) {
            mOverlayView.animate().alpha(0f).setDuration(220)
                    .withEndAction(this::stopSelf)
                    .start();
            // Safety net: if the animation is cancelled (e.g. view detached
            // first), still stop shortly after. stopSelf is idempotent.
            mMainHandler.postDelayed(mFadeStopFallback, 500);
        } else {
            stopSelf();
        }
    }

    private void clearPartialText() {
        if (mPartialText != null) mPartialText.setText("");
        if (mPartialScroll != null) mPartialScroll.setVisibility(View.GONE);
    }

    private void deliverFinalText(String text) {
        mResultPending = false;
        mLastRawTranscript = null;
        mTranscribedResult = text;
        if (mProgress != null) mProgress.setVisibility(View.GONE);
        if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
        mStatusText.setText(getString(R.string.floating_status_ready));
        mCancelButton.setVisibility(View.GONE);
        clearPartialText();

        // Attempt direct insertion via AccessibilityService first
        boolean pasted = FloatingDictationAccessibilityService.pasteText(this, text);
        if (!pasted) {
            // Accessibility not enabled or insertion unavailable: show Insert button
            mInsertButton.setVisibility(View.VISIBLE);
            scheduleInactivityTimer();
        } else {
            mInsertButton.setVisibility(View.GONE);
            collapsePanel();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        checkReapplyTheme();
        return START_STICKY;
    }

    private void checkReapplyTheme() {
        if (mIsDestroyed || mOverlayView == null) return;
        Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
        boolean currentNight = ThemePrefs.isNight(night);
        if (currentNight != mViewIsNight) {
            reapplyTheme();
        }
    }

    private void reapplyTheme() {
        if (mIsDestroyed || mOverlayView == null || mWindowManager == null) return;

        boolean isPanelVisible = (mPanelRoot != null && mPanelRoot.getVisibility() == View.VISIBLE);
        CharSequence statusText = (mStatusText != null) ? mStatusText.getText() : null;
        int progressVis = (mProgress != null) ? mProgress.getVisibility() : View.GONE;
        boolean ppChecked = (mPpToggle != null) ? mPpToggle.isChecked() : false;
        CharSequence partialText = (mPartialText != null) ? mPartialText.getText() : null;
        int partialScrollVis = (mPartialScroll != null) ? mPartialScroll.getVisibility() : View.GONE;
        boolean isRecordCircleVis = (mRecordCircle != null && mRecordCircle.getVisibility() == View.VISIBLE);
        boolean isInsertVis = (mInsertButton != null && mInsertButton.getVisibility() == View.VISIBLE);
        float currentAlpha = mOverlayView.getAlpha();
        int currentX = mParams.x;
        int currentY = mParams.y;
        boolean wasDocked = mIsDocked;

        cancelInactivityTimer();
        cancelAnimators();

        try {
            if (mOverlayView.isAttachedToWindow()) {
                mWindowManager.removeView(mOverlayView);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to remove view during reapplyTheme", t);
        }

        Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
        mViewIsNight = ThemePrefs.isNight(night);
        Context themed = new android.view.ContextThemeWrapper(night, R.style.AppTheme);
        // Root = null on purpose; see setupOverlayView().
        mOverlayView = LayoutInflater.from(themed).inflate(R.layout.overlay_floating_dictation, null);

        mBubbleRoot = mOverlayView.findViewById(R.id.floating_bubble_root);
        mBubbleCircle = mOverlayView.findViewById(R.id.floating_bubble_circle);
        mBubbleMicLevel = mOverlayView.findViewById(R.id.floating_bubble_mic_level);
        mPanelRoot = mOverlayView.findViewById(R.id.floating_panel_root);

        mStatusText = mOverlayView.findViewById(R.id.floating_status_text);
        mProgress = mOverlayView.findViewById(R.id.floating_progress);
        mPpToggle = mOverlayView.findViewById(R.id.floating_pp_toggle);
        mCancelButton = mOverlayView.findViewById(R.id.floating_cancel_button);
        mCloseButton = mOverlayView.findViewById(R.id.floating_close_button);
        mPartialScroll = mOverlayView.findViewById(R.id.floating_partial_scroll);
        mPartialText = mOverlayView.findViewById(R.id.floating_partial_text);

        mRecordContainer = mOverlayView.findViewById(R.id.floating_record_container);
        mMicLevelView = mOverlayView.findViewById(R.id.floating_mic_level);
        mRecordCircle = mOverlayView.findViewById(R.id.floating_record_circle);
        mInsertButton = mOverlayView.findViewById(R.id.floating_insert_button);
        mHintText = mOverlayView.findViewById(R.id.floating_hint);

        if (mPanelRoot != null) {
            mPanelRoot.setVisibility(isPanelVisible ? View.VISIBLE : View.GONE);
        }
        if (mStatusText != null && statusText != null) {
            mStatusText.setText(statusText);
        }
        if (mProgress != null) {
            mProgress.setVisibility(progressVis);
        }
        if (mPpToggle != null) {
            mPpToggle.setChecked(ppChecked);
        }
        if (mPartialText != null && partialText != null) {
            mPartialText.setText(partialText);
        }
        if (mPartialScroll != null) {
            mPartialScroll.setVisibility(partialScrollVis);
        }
        if (mRecordCircle != null) {
            mRecordCircle.setVisibility(isRecordCircleVis ? View.VISIBLE : View.GONE);
        }
        if (mInsertButton != null) {
            mInsertButton.setVisibility(isInsertVis ? View.VISIBLE : View.GONE);
        }
        mOverlayView.setAlpha(currentAlpha);
        mParams.x = currentX;
        mParams.y = currentY;
        mIsDocked = wasDocked;

        setupGestureAndListeners();

        try {
            mWindowManager.addView(mOverlayView, mParams);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to add view during reapplyTheme", t);
        }

        scheduleInactivityTimer();
    }

    @Override
    public void onDestroy() {
        sIsRunning = false;
        mIsDestroyed = true;
        mCurrentSessionId++;
        cancelInactivityTimer();
        cancelAnimators();
        if (mBubbleRoot != null) {
            mBubbleRoot.animate().cancel();
            mBubbleRoot.removeCallbacks(null);
        }
        stopPulseAnimation();
        mMainHandler.removeCallbacksAndMessages(null);
        // Best-effort: persist the last position even if the service dies
        // mid-gesture (covers the long-press stop path too). Only when the
        // overlay was actually set up: a failed startup (permission missing)
        // must never clobber a saved position with the defaults.
        if (mOverlayView != null) {
            saveBubblePosition();
        }
        mAudioRecordBridge.stop();
        try { cancelRecording(); } catch (Throwable ignored) { }
        AudioDeviceManager.releaseMicrophone(this);
        try { cleanupNative(); } catch (Throwable ignored) { }
        PostProcessor.cancelAllFor(this);
        if (mIsDismissTargetAttached && mWindowManager != null && mDismissTargetView != null) {
            try {
                if (mDismissTargetView.isAttachedToWindow()) {
                    mWindowManager.removeView(mDismissTargetView);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to remove dismiss target view on destroy", t);
            }
            mIsDismissTargetAttached = false;
        }
        if (mOverlayView != null && mWindowManager != null) {
            try {
                // removeView() on a detached view throws IllegalArgumentException
                // (e.g. addView() never succeeded in a failed setup); the
                // isAttachedToWindow() check + try/catch keeps teardown crash-free.
                if (mOverlayView.isAttachedToWindow()) {
                    mWindowManager.removeView(mOverlayView);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to remove overlay view on destroy", t);
            }
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mIsDestroyed || mOverlayView == null || mParams == null) return;
        final int oldScreenWidth = mLastScreenWidth > 0 ? mLastScreenWidth : getRealScreenWidth();
        mMainHandler.post(() -> {
            if (mIsDestroyed || mOverlayView == null || mParams == null) return;
            checkReapplyTheme();
            int newScreenWidth = getRealScreenWidth();
            mLastScreenWidth = newScreenWidth;
            if (mPanelRoot != null && mPanelRoot.getVisibility() == View.VISIBLE) {
                resizeWindow(true);
            } else {
                undockAndRestoreOpacity(false);
                if (oldScreenWidth > 0 && oldScreenWidth != newScreenWidth) {
                    mParams.x = (int) ((long) mParams.x * newScreenWidth / oldScreenWidth);
                }
                snapToNearestEdge(null);
            }
        });
    }

    static int calculateNearestEdgeX(int currentX, int bubbleWidth, int screenWidth) {
        int centerX = currentX + bubbleWidth / 2;
        if (centerX < screenWidth / 2) {
            return 0;
        } else {
            return Math.max(0, screenWidth - bubbleWidth);
        }
    }

    static int clampY(int y, int bubbleHeight, int screenHeight, int statusBarHeight) {
        int maxY = Math.max(0, screenHeight - bubbleHeight);
        int effectiveStatusBar = Math.min(statusBarHeight, maxY);
        return Math.max(effectiveStatusBar, Math.min(y, maxY));
    }

    static int calculateDockedX(boolean isLeft, int bubbleWidth, int screenWidth, float peekRatio) {
        int peekOffset = (int) (bubbleWidth * peekRatio);
        return isLeft ? -peekOffset : (screenWidth - bubbleWidth + peekOffset);
    }

    private void scheduleInactivityTimer() {
        cancelInactivityTimer();
        if (mIsDestroyed || mIsRecording || mResultPending) return;
        if (mPanelRoot != null && mPanelRoot.getVisibility() == View.VISIBLE) return;
        if (mMainHandler != null) {
            mMainHandler.postDelayed(mInactivityRunnable, INACTIVITY_DOCK_DELAY_MS);
        }
    }

    private void cancelInactivityTimer() {
        if (mMainHandler != null && mInactivityRunnable != null) {
            mMainHandler.removeCallbacks(mInactivityRunnable);
        }
    }

    private void cancelAnimators() {
        if (mSnapAnimator != null) {
            mSnapAnimator.cancel();
            mSnapAnimator = null;
        }
        if (mDockAnimator != null) {
            mDockAnimator.cancel();
            mDockAnimator = null;
        }
    }

    private void snapToNearestEdge(Runnable onEnd) {
        if (mIsDestroyed || mWindowManager == null || mOverlayView == null || mParams == null) {
            return;
        }
        cancelAnimators();

        float density = getResources().getDisplayMetrics().density;
        int bubbleWidth = (mBubbleRoot != null && mBubbleRoot.getWidth() > 0)
                ? mBubbleRoot.getWidth() : (int) (64 * density);
        int bubbleHeight = (mBubbleRoot != null && mBubbleRoot.getHeight() > 0)
                ? mBubbleRoot.getHeight() : (int) (64 * density);

        int screenWidth = getRealScreenWidth();
        int screenHeight = getRealScreenHeight();
        int statusBar = systemBarInsets()[0];

        int startX = mParams.x;
        int targetX = calculateNearestEdgeX(startX, bubbleWidth, screenWidth);

        int startY = mParams.y;
        int targetY = clampY(startY, bubbleHeight, screenHeight, statusBar);

        mParams.y = targetY;
        mBubbleY = targetY;

        if (startX == targetX) {
            mParams.x = targetX;
            mBubbleX = targetX;
            saveBubblePosition();
            try {
                mWindowManager.updateViewLayout(mOverlayView, mParams);
            } catch (Throwable t) {
                Log.w(TAG, "updateViewLayout failed in snapToNearestEdge", t);
            }
            if (onEnd != null) onEnd.run();
            scheduleInactivityTimer();
            return;
        }

        mSnapAnimator = ValueAnimator.ofInt(startX, targetX);
        mSnapAnimator.setDuration(250);
        mSnapAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        mSnapAnimator.addUpdateListener(animation -> {
            if (mIsDestroyed || mWindowManager == null || mOverlayView == null || mParams == null) {
                return;
            }
            int val = (int) animation.getAnimatedValue();
            mParams.x = val;
            mBubbleX = val;
            try {
                mWindowManager.updateViewLayout(mOverlayView, mParams);
            } catch (Throwable t) {
                Log.w(TAG, "updateViewLayout failed during snap animation", t);
            }
        });
        mSnapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCanceled = false;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mIsDestroyed || mCanceled) return;
                mParams.x = targetX;
                mBubbleX = targetX;
                saveBubblePosition();
                if (onEnd != null) onEnd.run();
                scheduleInactivityTimer();
            }
        });
        mSnapAnimator.start();
    }

    private void enterDockedState() {
        if (mIsDestroyed || mIsRecording || mResultPending || mIsDocked) return;
        if (mPanelRoot != null && mPanelRoot.getVisibility() == View.VISIBLE) return;
        if (mBubbleRoot == null || mOverlayView == null || mWindowManager == null || mParams == null) return;

        mIsDocked = true;

        float density = getResources().getDisplayMetrics().density;
        int bubbleWidth = mBubbleRoot.getWidth() > 0 ? mBubbleRoot.getWidth() : (int) (64 * density);
        int screenWidth = getRealScreenWidth();

        boolean isLeft = (mParams.x + bubbleWidth / 2) < (screenWidth / 2);
        int dockedTargetX = calculateDockedX(isLeft, bubbleWidth, screenWidth, 0.45f);

        cancelAnimators();

        int startX = mParams.x;
        mDockAnimator = ValueAnimator.ofInt(startX, dockedTargetX);
        mDockAnimator.setDuration(300);
        mDockAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        mDockAnimator.addUpdateListener(anim -> {
            if (mIsDestroyed || mWindowManager == null || mOverlayView == null || mParams == null) return;
            int xVal = (int) anim.getAnimatedValue();
            mParams.x = xVal;
            try {
                mWindowManager.updateViewLayout(mOverlayView, mParams);
            } catch (Throwable ignored) {}
        });
        mDockAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCanceled = false;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mIsDestroyed || mCanceled) return;
                mParams.x = dockedTargetX;
                try {
                    mWindowManager.updateViewLayout(mOverlayView, mParams);
                } catch (Throwable ignored) {}
            }
        });
        mDockAnimator.start();

        if (mBubbleRoot != null) {
            mBubbleRoot.animate().alpha(0.5f).setDuration(300).start();
        }
    }

    private void undockAndRestoreOpacity(boolean animateX) {
        cancelInactivityTimer();
        if (mBubbleRoot != null) {
            mBubbleRoot.animate().cancel();
            mBubbleRoot.setAlpha(1.0f);
        }
        if (!mIsDocked) return;
        mIsDocked = false;

        cancelAnimators();

        if (mIsDestroyed || mWindowManager == null || mOverlayView == null || mParams == null) return;

        float density = getResources().getDisplayMetrics().density;
        int bubbleWidth = (mBubbleRoot != null && mBubbleRoot.getWidth() > 0)
                ? mBubbleRoot.getWidth() : (int) (64 * density);
        int screenWidth = getRealScreenWidth();

        int targetX = calculateNearestEdgeX(mParams.x, bubbleWidth, screenWidth);
        if (animateX && mParams.x != targetX) {
            int startX = mParams.x;
            mDockAnimator = ValueAnimator.ofInt(startX, targetX);
            mDockAnimator.setDuration(200);
            mDockAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            mDockAnimator.addUpdateListener(anim -> {
                if (mIsDestroyed || mWindowManager == null || mOverlayView == null || mParams == null) return;
                int xVal = (int) anim.getAnimatedValue();
                mParams.x = xVal;
                mBubbleX = xVal;
                try {
                    mWindowManager.updateViewLayout(mOverlayView, mParams);
                } catch (Throwable ignored) {}
            });
            mDockAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean mCanceled = false;

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    mCanceled = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (mIsDestroyed || mCanceled) return;
                    mParams.x = targetX;
                    mBubbleX = targetX;
                    saveBubblePosition();
                }
            });
            mDockAnimator.start();
        } else {
            mParams.x = targetX;
            mBubbleX = targetX;
            saveBubblePosition();
            try {
                mWindowManager.updateViewLayout(mOverlayView, mParams);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "Foreground service timeout triggered (type=" + fgsType + ", startId=" + startId + ")");
        stopSelf(startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Native JNI methods
    private native void initNative(FloatingOverlayService service);
    private native void cleanupNative();
    private native void startRecording(boolean autoStop, int sessionId);
    private native void stopRecording();
    private native void cancelRecording();
    private native void pushAudioDirect(java.nio.ByteBuffer buffer, int byteCount, int sessionId);

    // Callbacks from Rust
    public void onAutoStop(int sessionId) {
        if (sessionId != mCurrentSessionId || mIsDestroyed) return;
        mMainHandler.post(() -> {
            if (sessionId != mCurrentSessionId || mIsDestroyed) return;
            if (mIsRecording) {
                stopRecordingSession();
            }
        });
    }

    public void onStatusUpdate(String status) {
        if (mIsDestroyed) return;
        onStatusUpdate(status, mCurrentSessionId);
    }

    public void onStatusUpdate(String status, int sessionId) {
        if (sessionId != mCurrentSessionId || mIsDestroyed) return;
        mMainHandler.post(() -> {
            if (sessionId != mCurrentSessionId || mIsDestroyed) return;
            mLastStatus = status != null ? status : "";
            if (mLastStatus.startsWith("Error")) {
                mResultPending = false;
                if (mIsRecording) {
                    mIsRecording = false;
                    mAudioRecordBridge.stop();
                    AudioDeviceManager.releaseMicrophone(FloatingOverlayService.this);
                }
                stopPulseAnimation();
                if (mProgress != null) mProgress.setVisibility(View.GONE);
                if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
                if (mStatusText != null) mStatusText.setText(mLastStatus);
                scheduleInactivityTimer();
            }
        });
    }

    public void onAudioLevel(float level, int sessionId) {
        if (sessionId != mCurrentSessionId || mIsDestroyed) return;
        mMainHandler.post(() -> {
            if (sessionId == mCurrentSessionId && !mIsDestroyed) {
                if (mBubbleMicLevel != null) mBubbleMicLevel.setLevel(level);
                if (mMicLevelView != null) mMicLevelView.setLevel(level);
            }
        });
    }

    public void onPartialText(String text, int sessionId) {
        if (sessionId != mCurrentSessionId || mIsDestroyed) return;
        mMainHandler.post(() -> {
            if (sessionId != mCurrentSessionId || mIsDestroyed) return;
            if (mIsRecording && mPartialText != null && mPartialScroll != null
                    && text != null && !text.trim().isEmpty()) {
                mPartialText.setText(text);
                mPartialScroll.setVisibility(View.VISIBLE);
                mPartialScroll.post(() -> mPartialScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /**
     * True while the AI-fix switch is being set programmatically, so its listener
     * does not mistake that for a user action.
     */
    private boolean mSuppressPpToggle;

    /**
     * Reflects the AI-fix state on the overlay toggle from an ordered credential
     * read on the credential lane: no credential I/O on this (UI) thread, and an
     * answer that already accounts for a start-up legacy import still queued. A
     * stale answer is dropped by the identity check on the view.
     */
    private void syncPostProcessToggle() {
        final android.widget.CompoundButton toggle = mPpToggle;
        if (toggle == null) return;
        final SettingsManager sm = new SettingsManager(this);
        sm.readPostProcessEnabled(mMainHandler::post, enabled -> {
            if (toggle != mPpToggle || mIsDestroyed) return;
            mSuppressPpToggle = true;
            try {
                toggle.setChecked(enabled);
            } finally {
                mSuppressPpToggle = false;
            }
            toggle.setEnabled(true);
        });
    }

    public void onTextTranscribed(String text, int sessionId) {
        if (sessionId != mCurrentSessionId || mIsDestroyed) return;
        mMainHandler.post(() -> {
            if (sessionId != mCurrentSessionId || mIsDestroyed) return;
            if (text == null || text.trim().isEmpty()) {
                mResultPending = false;
                mLastRawTranscript = null;
                if (mProgress != null) mProgress.setVisibility(View.GONE);
                if (mHintText != null) mHintText.setText(R.string.ime_tap_to_record);
                if (mStatusText != null) mStatusText.setText(getString(R.string.floating_status_ready));
                scheduleInactivityTimer();
                return;
            }

            mLastRawTranscript = text;
            SettingsManager settings = new SettingsManager(this);
            // Cheap switch check (no credential I/O on this thread); PostProcessor
            // performs the ordered credential read and falls back to the raw
            // transcript, exactly once, when there is no usable credential.
            if (settings.isPostProcessSwitchedOn()) {
                mLastStatus = "Processing...";
                if (mStatusText != null) mStatusText.setText(getString(R.string.ime_refining));
                if (mProgress != null) mProgress.setVisibility(View.VISIBLE);
                if (mCancelButton != null) mCancelButton.setVisibility(View.VISIBLE);

                final int processingSessionId = sessionId;
                new PostProcessor(settings, mMainHandler,
                        () -> processingSessionId == mCurrentSessionId && !mIsDestroyed,
                        this)
                        .process(text, new PostProcessor.PostProcessCallback() {
                            @Override
                            public void onSuccess(String refinedText) {
                                if (processingSessionId != mCurrentSessionId) return;
                                String result = refinedText != null && !refinedText.trim().isEmpty()
                                        ? refinedText : text;
                                deliverFinalText(result);
                            }

                            @Override
                            public void onError(String error) {
                                if (processingSessionId != mCurrentSessionId) return;
                                deliverFinalText(text);
                            }
                        });
            } else {
                deliverFinalText(text);
            }
        });
    }
}
