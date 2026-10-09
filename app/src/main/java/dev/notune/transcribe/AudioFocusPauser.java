package dev.notune.transcribe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

/**
 * Optionally asks the system for audio focus while the app records, so that
 * playing media pauses instead of talking over the user.
 *
 * <p>Focus strategy: {@code AUDIOFOCUS_GAIN_TRANSIENT} — not
 * {@code AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE}. The exclusive variant tells the
 * platform (and every other app) that nothing else may play, and is reserved for
 * cases that genuinely need the audio device to themselves (e.g. a call). This
 * app only needs other audio to pause for the few seconds of a dictation, so
 * requesting the exclusive flavour interrupted media more aggressively than the
 * feature requires — and on Android 15+/17 the platform increasingly penalises
 * apps that grab exclusive focus. A transient, non-exclusive gain with
 * {@code setWillPauseWhenDucked(true)} produces the behaviour the "Pause audio"
 * setting promises: a cooperative pause, with the platform free to duck instead
 * on devices/OEMs that prefer it.
 *
 * <p>This class is only ever invoked when the user has opted into "Pause audio";
 * when that setting is off the app takes no audio focus at all and never
 * interrupts playback.
 */
public class AudioFocusPauser {
    private static final String TAG = "AudioFocusPauser";

    private AudioFocusRequest focusRequest = null;

    /**
     * Receives focus changes. Deliberately passive: the app does not need to
     * pause its own playback (it has none), but the callback is required by the
     * API, and logging loss lets device logs explain "media kept playing" /
     * "media stayed silent" reports without guessing.
     */
    private final AudioManager.OnAudioFocusChangeListener listener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d(TAG, "Audio focus lost (code " + focusChange + ")");
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d(TAG, "Audio focus gained");
                break;
            default:
                Log.d(TAG, "Audio focus change: " + focusChange);
                break;
        }
    };

    public void request(Context ctx) {
        AudioManager am = audioManager(ctx);
        if (am == null) return;

        try {
            // Always release first so a second request cannot stack a stale
            // request object on top of the live one.
            abandon(ctx);

            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setOnAudioFocusChangeListener(listener)
                    // Ask other apps to pause rather than duck: the user is
                    // dictating, so a half-volume music bed would be picked
                    // up by the recogniser.
                    .setWillPauseWhenDucked(true)
                    .setAcceptsDelayedFocusGain(false)
                    .build();
            int result = am.requestAudioFocus(focusRequest);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                // Not an error worth surfacing: the grant is best-effort and
                // recording proceeds regardless.
                Log.d(TAG, "Audio focus request was not granted; continuing without it");
            }
        } catch (RuntimeException e) {
            // Do not let a focus problem break recording. Logged (not swallowed
            // silently) so the cause is diagnosable from logcat.
            Log.w(TAG, "Could not request audio focus", e);
        }
    }

    public void abandon(Context ctx) {
        AudioManager am = audioManager(ctx);
        if (am == null) return;

        try {
            if (focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest);
            }
            focusRequest = null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not abandon audio focus", e);
        }
    }

    private AudioManager audioManager(Context ctx) {
        if (ctx == null) return null;
        return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }
}
