package dev.notune.transcribe;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import dev.notune.transcribe.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TranscribeFileActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";
    private static final int TARGET_SAMPLE_RATE = 16000;
    // Bounded-memory streaming decode: the file is converted to 16 kHz mono one
    // chunk at a time and handed to the native engine immediately, so the
    // decoded source never exists in memory as a whole. 30 s caps a single
    // chunk at ~1.9 MB of float data (plus a same-sized resampled copy) no
    // matter how long the input is; the previous implementation accumulated the
    // entire decode in a `List<float[]>`, merged it into one `float[]`, then
    // allocated a third `float[]` for the resampled output, and finally the
    // native side copied it again — roughly four full copies of the audio.
    private static final int CHUNK_SECONDS = 30;
    // Upper bound on the *duration* of an incoming file. This Activity is
    // exported for audio/* (SEND/VIEW), so any app can hand it an arbitrarily
    // long file: the limit stops a hostile or buggy input from pinning the CPU
    // for an unbounded time. It is expressed in seconds rather than a raw
    // sample count so it means the same thing at every input sample rate, and
    // it is now a policy cap on duration — memory is bounded by CHUNK_SECONDS
    // independently of this value.
    private static final int MAX_DURATION_SECONDS = 30 * 60;
    // A single chunk is short relative to this, so hitting it means the native
    // side stalled rather than the file being long.
    private static final long CHUNK_RESULT_TIMEOUT_MS = 10 * 60 * 1000L;

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

    private TextView statusText;
    private ProgressBar progressBar;
    private View progressArea;
    private ScrollView resultArea;
    private TextView resultText;
    private Button copyButton;

    // Monotonic operation-id source, shared across Activity instances so a
    // recreated Activity can never collide with a stale native worker's id.
    private static final AtomicInteger NEXT_OP = new AtomicInteger(1);
    // The operation id of the decode currently owned by this Activity; a
    // destroyed/recreated Activity bumps it to invalidate late callbacks.
    private volatile int currentOpId = 0;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    // Transcript pieces of the chunks already transcribed, in order. Filled from
    // the main thread (onTextTranscribed) and drained by the decode thread.
    private final List<String> chunkTexts = Collections.synchronizedList(new ArrayList<>());
    // Signalled by onTextTranscribed when the chunk currently in flight is done.
    private volatile CountDownLatch pendingChunk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transcribe_file_activity);

        View rootView = findViewById(R.id.root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        statusText = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progress_bar);
        progressArea = findViewById(R.id.progress_area);
        resultArea = findViewById(R.id.result_area);
        resultText = findViewById(R.id.txt_result);
        copyButton = findViewById(R.id.btn_copy);

        // bg_result is a rounded card, so its children must be clipped to the
        // rounded outline or the selectable result text paints over the corners.
        // The XML attribute android:clipToOutline only exists from API 31, so on
        // API 26-30 the attribute was silently ignored and the clipping never
        // happened; View.setClipToOutline() has been available since API 21, so
        // the behaviour now works on every supported release.
        if (resultArea != null) {
            resultArea.setClipToOutline(true);
        }

        findViewById(R.id.btn_close).setOnClickListener(v -> cancelAndClose());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> cancelCurrentOperation());

        copyButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (!text.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Transcription", text);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, getString(R.string.file_copied), Toast.LENGTH_SHORT).show();
            }
        });

        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText(getString(R.string.file_error_no_audio_received));
            progressBar.setVisibility(View.GONE);
            return;
        }

        statusText.setText(getString(R.string.file_loading_model));
        initNative(this);
    }

    /** Discards decode, native transcription, and post-processing without showing a result. */
    private void cancelCurrentOperation() {
        cancelRequested.set(true);
        currentOpId = NEXT_OP.incrementAndGet();
        try { cancelTranscription(); } catch (Throwable ignored) { }
        PostProcessor.cancelAllFor(this);
        progressArea.setVisibility(View.VISIBLE);
        resultArea.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        findViewById(R.id.btn_cancel).setVisibility(View.GONE);
        statusText.setText(getString(R.string.file_cancelled));
    }

    private void cancelAndClose() {
        cancelCurrentOperation();
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onDestroy() {
        // Invalidate any in-flight decode callbacks (P1.1): a late native
        // worker must not update a destroyed/recreated Activity.
        cancelRequested.set(true);
        currentOpId = NEXT_OP.incrementAndGet();
        try { cancelTranscription(); } catch (Throwable ignored) { }
        // Cancel this Activity's in-flight post-processing call (owner-scoped,
        // P0.1) so a late callback cannot update a finishing UI — without
        // cancelling another surface's legitimate request.
        PostProcessor.cancelAllFor(this);
        super.onDestroy();
        try { cleanupNative(); } catch (Throwable t) { /* ignore */ }
    }

    /**
     * Resolves the audio URI this Activity was launched with.
     *
     * <p>Both entries are supplied by OTHER apps ({@code SEND}/{@code VIEW} for
     * {@code audio/*} are exported), so the Intent is untrusted input.
     * {@link IntentCompat#getParcelableExtra} is used instead of the deprecated
     * {@code Intent.getParcelableExtra(String)}: the deprecated overload performs
     * an unchecked cast at the call site, so a hostile caller that puts a
     * non-Uri Parcelable (for example a Bundle) in {@code EXTRA_STREAM} would
     * crash the receiver with a {@link ClassCastException}. The typed overload
     * returns null for anything that is not a {@link Uri}.
     */
    private Uri getAudioUri() {
        Intent intent = getIntent();
        if (intent == null) return null;

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            return intent.getData();
        }
        return null;
    }

    // Called from Rust when model is ready
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            if (cancelRequested.get() || isFinishing() || isDestroyed()) return;
            if ("Ready".equals(status)) {
                statusText.setText(getString(R.string.file_decoding_audio));
                startDecodeAndTranscribe();
            } else {
                statusText.setText(status);
                if (status != null && status.startsWith("Error")) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    // Called from Rust for every transcribed chunk of the current operation.
    // The opId guard (P1.1) drops callbacks from a worker that finishes after
    // this Activity was destroyed or a new decode started.
    public void onTextTranscribed(String text, int opId) {
        // Read from a native worker thread: reject a superseded operation before
        // touching any state, so a late chunk can never land in a new transcript
        // (or release the new operation's latch).
        if (opId != currentOpId) return;
        runOnUiThread(() -> {
            if (opId != currentOpId) return;
            if (text != null && !text.trim().isEmpty() && !isFinishing() && !isDestroyed()) {
                chunkTexts.add(text.trim());
            }
            // Always release the decode thread, even on teardown, so it cannot
            // sit in its await loop.
            CountDownLatch latch = pendingChunk;
            if (latch != null) latch.countDown();
        });
    }

    private void showResult(String text) {
        // Hide progress, show result. The transcript is NOT auto-copied to the
        // system clipboard (privacy, V10): other apps can read the clipboard,
        // so copying stays an explicit user action via the copy button.
        progressArea.setVisibility(View.GONE);
        resultArea.setVisibility(View.VISIBLE);
        copyButton.setVisibility(View.VISIBLE);
        resultText.setText(text);
    }

    private void startDecodeAndTranscribe() {
        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText(getString(R.string.file_error_no_audio));
            return;
        }

        // Every decode gets a fresh unique operation id (static counter, so a
        // recreated Activity can never accept a stale worker's callbacks).
        final int opId = NEXT_OP.incrementAndGet();
        currentOpId = opId;
        cancelRequested.set(false);
        chunkTexts.clear();

        new Thread(() -> {
            try {
                runOnUiThread(() -> statusText.setText(getString(R.string.file_transcribing)));
                decodeAndTranscribeStreaming(audioUri, opId);

            } catch (CancellationException e) {
                // User explicitly cancelled; do not surface an error or result.
            } catch (BoundedSourceCopy.SourceTooLargeException tooLarge) {
                // The fallback copy refused to write past the import byte cap.
                // Show a dedicated localized message — the raw exception text is
                // for the log, never the only thing the user sees.
                if (cancelRequested.get() || opId != currentOpId) return;
                Log.w(TAG, "Rejected a source larger than "
                        + BoundedSourceCopy.MAX_SOURCE_FILE_BYTES + " bytes");
                showError(getString(R.string.file_error_too_large));
            } catch (Exception e) {
                if (cancelRequested.get() || opId != currentOpId) return;
                Log.e(TAG, "Error decoding audio", e);
                showError(getString(R.string.file_error_format, e.getMessage()));
            }
        }, "file-decode").start();
    }

    /**
     * Best-effort size the content provider declares for {@code uri}, or {@code -1}
     * when the metadata is missing, unknown (0/null) or the query is refused.
     *
     * <p>Only a fast-abort hint for the fallback copy: providers can and do
     * report nothing or a wrong number, so the read-time accounting inside
     * {@link BoundedSourceCopy} remains the authoritative enforcement.
     */
    private long queryDeclaredSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    long size = cursor.getLong(index);
                    if (size > 0) return size;
                }
            }
        } catch (Throwable ignored) {
            // Restricted providers may refuse the query; unknown size is fine.
        }
        return -1L;
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
            progressBar.setVisibility(View.GONE);
        });
    }

    /**
     * Decode audio from a Uri and transcribe it in fixed-size chunks.
     *
     * <p>Memory stays bounded: audio is converted to 16 kHz mono one chunk at a
     * time, handed to the native engine, then dropped. The file is never held in
     * memory as a whole, so a long recording costs the same heap as a short one.
     * Processed chunks are discarded, and the transcript is accumulated as text.
     */
    private void decodeAndTranscribeStreaming(Uri uri, int opId) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        File tempAudioFile = null;
        MediaCodec codec = null;
        try {
            boolean dataSourceSet = false;
            // 1. Try opening via ContentResolver asset file descriptor
            try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd != null) {
                    extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                    dataSourceSet = true;
                }
            } catch (Throwable ignored) {}

            // 2. If direct FD failed (e.g. raw file:// URI or restricted cross-app stream), copy to local app cache
            if (!dataSourceSet) {
                long declaredSize = queryDeclaredSize(uri);
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in != null) {
                        tempAudioFile = File.createTempFile("audio_decode_", ".tmp", getCacheDir());
                        // Bounded copy: the byte limit is enforced against the
                        // bytes actually read (declared metadata may be missing
                        // or wrong). Over the limit the helper deletes the
                        // partial file and the exception is rethrown — not
                        // swallowed like the other descriptor failures — so the
                        // caller can show a localized error instead of silently
                        // falling through to another resolution path.
                        BoundedSourceCopy.copyToFile(
                                in, tempAudioFile,
                                BoundedSourceCopy.MAX_SOURCE_FILE_BYTES, declaredSize);
                        extractor.setDataSource(tempAudioFile.getAbsolutePath());
                        dataSourceSet = true;
                    }
                } catch (BoundedSourceCopy.SourceTooLargeException tooLarge) {
                    throw tooLarge;
                } catch (Throwable ignored) {}
            }

            // 3. Fallback to standard framework URI resolution
            if (!dataSourceSet) {
                extractor.setDataSource(this, uri, null);
            }

            // Find audio track
            int audioTrackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String trackMime = format.getString(MediaFormat.KEY_MIME);
                if (trackMime != null && trackMime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    inputFormat = format;
                    break;
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) {
                // Report it. Returning quietly used to leave the screen on
                // "Transcribing…" with a spinning progress bar forever, because
                // the caller only reacts to an exception or to a delivered
                // transcript — and a file with no audio track produces neither.
                Log.e(TAG, "No audio track found");
                showError(getString(R.string.file_error_no_audio));
                return;
            }
            extractor.selectTrack(audioTrackIndex);

            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (channelCount < 1) channelCount = 1;
            if (sampleRate <= 0) {
                throw new IOException("Input declares an invalid sample rate: " + sampleRate);
            }
            Log.i(TAG, "Audio: mime=" + mime + " rate=" + sampleRate + " channels=" + channelCount);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long timeoutUs = 10000;

            float[] chunk = new float[CHUNK_SECONDS * sampleRate];
            int filled = 0;
            long totalSourceFrames = 0;

            while (!outputDone) {
                if (cancelRequested.get()) throw new CancellationException();

                // Feed input
                if (!inputDone) {
                    int inputBufferIndex = codec.dequeueInputBuffer(timeoutUs);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                        int bytesRead = extractor.readSampleData(inputBuffer, 0);
                        if (bytesRead < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            codec.queueInputBuffer(inputBufferIndex, 0, bytesRead,
                                    presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // Drain output
                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs);

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The decoder is authoritative about the PCM it emits: the
                    // container's header can disagree (unusual rates, HE-AAC
                    // upsampling). Re-read the format, and start a new chunk so
                    // two different rates are never spliced into one buffer.
                    MediaFormat outFormat = codec.getOutputFormat();
                    int outRate = intOr(outFormat, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                    int outChannels = intOr(outFormat, MediaFormat.KEY_CHANNEL_COUNT, channelCount);
                    if (outRate > 0 && outRate != sampleRate) {
                        if (filled > 0) {
                            transcribeChunk(chunk, filled, sampleRate, opId);
                            filled = 0;
                        }
                        sampleRate = outRate;
                        chunk = new float[CHUNK_SECONDS * sampleRate];
                        Log.i(TAG, "Decoder output sample rate changed to " + sampleRate);
                    }
                    if (outChannels > 0) channelCount = outChannels;
                    continue;
                }

                if (outputBufferIndex < 0) continue;

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }

                ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                    // Decoded PCM is 16-bit signed. Convert to mono float.
                    ShortBuffer shortBuf = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    int frameCount = shortBuf.remaining() / channelCount;
                    totalSourceFrames += frameCount;
                    if (FileAudioChunks.exceedsDurationCap(
                            totalSourceFrames, sampleRate, MAX_DURATION_SECONDS)) {
                        throw new IOException(getString(R.string.file_error_too_long));
                    }
                    for (int i = 0; i < frameCount; i++) {
                        if (channelCount == 1) {
                            chunk[filled++] = shortBuf.get() / 32768.0f;
                        } else {
                            // Mix channels to mono
                            float sum = 0f;
                            for (int c = 0; c < channelCount; c++) {
                                sum += shortBuf.get() / 32768.0f;
                            }
                            chunk[filled++] = sum / channelCount;
                        }
                        if (filled == chunk.length) {
                            transcribeChunk(chunk, filled, sampleRate, opId);
                            filled = 0;
                        }
                    }
                }

                codec.releaseOutputBuffer(outputBufferIndex, false);
            }

            if (filled > 0) {
                transcribeChunk(chunk, filled, sampleRate, opId);
            }
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
            if (tempAudioFile != null) {
                tempAudioFile.delete();
            }
        }

        deliverAccumulated(opId);
    }

    /** Reads an int from a MediaFormat, returning [fallback] when absent. */
    private static int intOr(MediaFormat format, String key, int fallback) {
        try {
            Integer value = format.getInteger(key);
            return value != null ? value : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Resamples one chunk to 16 kHz and hands it to the native engine, then
     * waits for its result.
     *
     * <p>Chunks are strictly serialised on purpose: the native state keeps a
     * single cancel token for the file-transcription surface, so issuing a
     * second call while the first is in flight would cancel it. Waiting here
     * (on the decode thread) is what keeps the peak memory to one chunk.
     */
    private void transcribeChunk(float[] source, int length, int sampleRate, int opId)
            throws IOException {
        float[] pcm = (sampleRate == TARGET_SAMPLE_RATE)
                ? Arrays.copyOf(source, length)
                : FileAudioChunks.resample(source, length, sampleRate, TARGET_SAMPLE_RATE);
        if (pcm.length == 0) return;

        CountDownLatch latch = new CountDownLatch(1);
        pendingChunk = latch;
        try {
            transcribeAudio(pcm, pcm.length, opId);

            long deadline = System.currentTimeMillis() + CHUNK_RESULT_TIMEOUT_MS;
            while (true) {
                // Cancellation is polled rather than awaited indefinitely: a
                // cancelled native call reports nothing back, so a plain await
                // would block until the timeout.
                if (cancelRequested.get() || opId != currentOpId) throw new CancellationException();
                try {
                    if (latch.await(200, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("Timed out waiting for a transcription chunk");
                }
            }
        } finally {
            pendingChunk = null;
        }
    }

    /** Joins the per-chunk transcripts and hands the finished text to the UI. */
    private void deliverAccumulated(int opId) {
        String text;
        synchronized (chunkTexts) {
            text = FileAudioChunks.joinTranscripts(chunkTexts);
        }
        if (cancelRequested.get() || opId != currentOpId) return;
        if (text.isEmpty()) {
            // The decode itself succeeded; there was simply nothing the engine
            // recognised as speech (silence, music, an empty track). Reporting
            // "could not decode" for that sent the user hunting for a format
            // problem that does not exist.
            showError(getString(R.string.file_error_no_speech));
            return;
        }
        runOnUiThread(() -> deliverTranscript(text, opId));
    }

    /**
     * Shows the finished transcript, refining it first when AI post-processing
     * is enabled. Always invoked on the main thread.
     */
    private void deliverTranscript(String text, int opId) {
        if (opId != currentOpId || cancelRequested.get() || isFinishing() || isDestroyed()) return;
        SettingsManager settings = new SettingsManager(this);
        // Cheap switch check (no credential I/O on this thread). PostProcessor
        // performs the ordered credential read itself and shows the raw transcript
        // when there is no usable credential, so a pending legacy import is never
        // read as "not configured".
        if (settings.isPostProcessSwitchedOn()) {
            statusText.setText(getString(R.string.file_refining));
            // Owned by this Activity so its teardown only cancels its own
            // in-flight call, never another surface's (P0.1). The operation id
            // is part of the validator exactly as the IME and overlay include
            // their session id: a read that completes after this operation was
            // cancelled or replaced must not start a request with the stale
            // transcript — the response would be dropped by the opId check
            // below, but the cancelled speech would already have been sent to
            // the provider.
            final int ppOpId = opId;
            new PostProcessor(settings, new Handler(Looper.getMainLooper()),
                    () -> ppOpId == currentOpId && !isFinishing() && !isDestroyed(), this)
                    .process(text, new PostProcessor.PostProcessCallback() {
                @Override
                public void onSuccess(String refinedText) {
                    if (opId != currentOpId || cancelRequested.get()
                            || isFinishing() || isDestroyed()) return;
                    String out = (refinedText != null && !refinedText.trim().isEmpty())
                            ? refinedText : text;
                    showResult(out);
                }

                @Override
                public void onError(String error) {
                    if (opId != currentOpId || cancelRequested.get()
                            || isFinishing() || isDestroyed()) return;
                    // Privacy (v0.1.24): the error string can carry
                    // provider details; the transcript itself is never
                    // logged in release builds.
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Post-process failed, showing raw text: " + error);
                    }
                    showResult(text);
                }
            });
        } else {
            showResult(text);
        }
    }

    // Decode-scoped status ("Transcribing...", decode errors). Ignored when
    // the operation no longer belongs to this Activity instance.
    public void onStatusUpdate(String status, int opId) {
        runOnUiThread(() -> {
            if (opId != currentOpId || isFinishing() || isDestroyed()) return;
            statusText.setText(status);
            if (status != null && status.startsWith("Error")) {
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    // Native methods
    private native void initNative(TranscribeFileActivity activity);
    private native void cleanupNative();
    private native void transcribeAudio(float[] samples, int length, int opId);
    private native void cancelTranscription();
}
