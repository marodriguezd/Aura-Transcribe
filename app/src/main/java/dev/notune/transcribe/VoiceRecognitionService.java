package dev.notune.transcribe;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import dev.notune.transcribe.BuildConfig;

import java.util.ArrayList;

/**
 * Exposes the offline transcriber as a system speech-to-text provider via
 * {@link android.speech.RecognitionService}. This is the API that keyboards
 * (Microsoft SwiftKey, Gboard, …) use through {@link SpeechRecognizer} to find
 * and drive an on-device recognizer.
 *
 * <p>Because the service is declared in the manifest, it is discoverable at all
 * times — even when the app process is not running or has been force-stopped by
 * the OS — so {@code SpeechRecognizer.isRecognitionAvailable()} stays true and
 * keyboards no longer report that "Google Speech Services aren't installed".
 *
 * <p>The heavy lifting (capture, silence endpointing, model inference) happens in
 * native code ({@code src/recog_service.rs}); this class only bridges the
 * {@link RecognitionService.Callback} to it.
 */
public class VoiceRecognitionService extends RecognitionService {

    private static final String TAG = "OfflineVoiceInput";

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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback mCallback;
    // Incremented whenever a recognition session starts, is cancelled, or the
    // service is destroyed. Late post-processing callbacks compare against the
    // captured id so they cannot deliver results to a stale or new session.
    private int currentSessionId = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            initNative(this);
        } catch (Throwable t) {
            Log.e(TAG, "initNative failed", t);
        }
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        currentSessionId++;
        mCallback = callback;

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — open the app to grant it");
            safeError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        try {
            SettingsManager sm = new SettingsManager(this);
            AudioDeviceManager.acquireMicrophone(this, sm.getMicMode());
            startListening(this, currentSessionId);
        } catch (Throwable t) {
            Log.e(TAG, "startListening failed", t);
            AudioDeviceManager.releaseMicrophone(this);
            safeError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    @Override
    protected void onStopListening(Callback callback) {
        AudioDeviceManager.releaseMicrophone(this);
        try {
            stopListening();
        } catch (Throwable t) {
            Log.e(TAG, "stopListening failed", t);
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        // Cancel this service's in-flight post-processing call when the
        // recognition session is cancelled — never another surface's (P0.1).
        AudioDeviceManager.releaseMicrophone(this);
        PostProcessor.cancelAllFor(this);
        // Invalidate any post-processor callback that is still pending for
        // this session so it cannot deliver results after cancellation.
        currentSessionId++;
        try {
            cancelNative();
        } catch (Throwable t) {
            Log.e(TAG, "cancel failed", t);
        }
    }

    @Override
    public void onDestroy() {
        // Cancel this service's in-flight post-processing call when the
        // recognition service is destroyed (owner-scoped, P0.1).
        AudioDeviceManager.releaseMicrophone(this);
        PostProcessor.cancelAllFor(this);
        // Invalidate any post-processor callback that is still pending so it
        // cannot deliver results to a released binder.
        currentSessionId++;
        try {
            destroyNative();
        } catch (Throwable t) {
            Log.e(TAG, "destroyNative failed", t);
        }
        super.onDestroy();
    }

    // --- Callbacks invoked from native code (any thread) ---------------------

    public void onReadyForSpeech(int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.readyForSpeech(new Bundle()); } catch (RemoteException ignored) {}
        });
    }

    public void onBeginningOfSpeech(int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.beginningOfSpeech(); } catch (RemoteException ignored) {}
        });
    }

    public void onRmsChanged(float rmsdB, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.rmsChanged(rmsdB); } catch (RemoteException ignored) {}
        });
    }

    public void onEndOfSpeech(int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.endOfSpeech(); } catch (RemoteException ignored) {}
        });
    }

    /**
     * Live partial hypotheses from a streaming model while the user is
     * speaking, surfaced to the calling keyboard via
     * {@link Callback#partialResults}. Only the final text goes through the
     * AI post-processor (see onResults).
     */
    public void onPartialText(String text, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            Callback cb = mCallback;
            if (cb == null || text == null || text.trim().isEmpty()) return;
            ArrayList<String> hypotheses = new ArrayList<>();
            hypotheses.add(text);
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, hypotheses);
            try { cb.partialResults(bundle); } catch (RemoteException ignored) {}
        });
    }

    public void onResults(String text, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            AudioDeviceManager.releaseMicrophone(VoiceRecognitionService.this);
            Callback cb = mCallback;
            if (cb == null) return;
            // Capture the id of the current recognition session so the async
            // post-processor callback can verify it is still valid before it
            // tries to deliver results to the framework.
            deliverResults(cb, text, sessionId);
            mCallback = null;
        });
    }

    private void deliverResults(Callback cb, String text, int sessionId) {
        SettingsManager settings = new SettingsManager(this);
        // Cheap switch check (no credential I/O on this thread). PostProcessor does
        // the ordered credential read on the credential lane — queued behind any
        // pending legacy import — and hands back the raw transcript when there is
        // no usable credential, so nothing is dropped and no unauthenticated
        // request is sent.
        if (settings.isPostProcessSwitchedOn()) {
            // Owned by this service so a cancelled/destroyed session only
            // cancels its own call, never another surface's (P0.1).
            new PostProcessor(settings, mainHandler,
                    () -> sessionId == currentSessionId && settings.isPostProcessSwitchedOn(),
                    this)
                    .process(text, new PostProcessor.PostProcessCallback() {
                @Override
                public void onSuccess(String refinedText) {
                    if (sessionId != currentSessionId) return;
                    String out = (refinedText != null && !refinedText.trim().isEmpty())
                            ? refinedText : text;
                    postResults(cb, out);
                }

                @Override
                public void onError(String error) {
                    if (sessionId != currentSessionId) return;
                    // Privacy (v0.1.24): the error string can carry provider
                    // details; the raw transcript itself is never logged in
                    // release builds.
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Post-process failed, delivering raw text: " + error);
                    }
                    postResults(cb, text);
                }
            });
        } else {
            postResults(cb, text);
        }
    }

    private void postResults(Callback cb, String text) {
        ArrayList<String> hypotheses = new ArrayList<>();
        hypotheses.add(text);
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, hypotheses);
        try { cb.results(bundle); } catch (RemoteException ignored) {}
    }

    public void onError(int errorCode, int sessionId) {
        mainHandler.post(() -> {
            if (sessionId != currentSessionId) return;
            AudioDeviceManager.releaseMicrophone(VoiceRecognitionService.this);
            Callback cb = mCallback;
            if (cb == null) return;
            try { cb.error(errorCode); } catch (RemoteException ignored) {}
            mCallback = null;
        });
    }

    /** Invoked by the shared engine loader during model warm-up; UI-less here. */
    public void onStatusUpdate(String status) {
        Log.d(TAG, "engine: " + status);
    }

    private void safeError(int errorCode) {
        Callback cb = mCallback;
        if (cb == null) return;
        try { cb.error(errorCode); } catch (RemoteException ignored) {}
        mCallback = null;
    }

    // --- Native methods (implemented in src/recog_service.rs) ----------------

    private native void initNative(VoiceRecognitionService service);
    private native void startListening(VoiceRecognitionService service, int sessionId);
    private native void stopListening();
    private native void cancelNative();
    private native void destroyNative();
}
