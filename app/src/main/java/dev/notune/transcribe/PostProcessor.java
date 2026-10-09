package dev.notune.transcribe;

import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI post-processing over an OpenAI-compatible /chat/completions endpoint.
 *
 * <p>The speech model owns the real-time experience: its partial hypotheses
 * remain visual-only and its final transcript arrives through
 * {@code onTextTranscribed}. Post-processing deliberately uses one complete
 * response request after that final transcript is available. This keeps the
 * editor atomic: it receives either the refined transcript once, or the raw
 * transcript once if the optional network step fails.</p>
 *
 * <p>A shared OkHttpClient reuses connections across dictations. In-flight
 * calls are tracked so they can be cancelled when the feature is disabled or
 * an owning Activity/Service is destroyed.</p>
 */
public class PostProcessor {
    private static final String TAG = "PostProcessor";
    private static final String DIAGNOSTIC_INPUT =
            "This is a diagnostic post-processing test.";
    private static final String DIAGNOSTIC_MARKER = "POSTPROCESS_DIAGNOSTIC_OK";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Broadcast action used to cancel calls in the isolated IME process. */
    public static final String CANCEL_ACTION = "dev.notune.transcribe.CANCEL_PP";
    /** Stable callback error used by settings UI to show a localized fix. */
    public static final String MISSING_API_KEY_ERROR =
            "API key required; open post-processing settings";

    /**
     * Stable callback error for the on-device provider, which has no inference
     * engine in this build (see
     * {@link SettingsManager#LOCAL_S1_INFERENCE_AVAILABLE}). Delivered through
     * {@code onError}, so every caller keeps its guaranteed fallback to the raw
     * transcript instead of receiving the input text back as if it were refined.
     */
    public static final String LOCAL_S1_UNAVAILABLE_ERROR =
            "On-device AI cleanup (SuperWhisper S1-mini) is unavailable in this build: "
                    + "it has no inference engine";

    /**
     * Timeouts for the shared production client: 30 s to reach a provider
     * (DNS + TLS on mobile networks) and 60 s to read/write the complete
     * non-streaming response, which for long transcripts on slow links can
     * legitimately take a while. Kept as named constants so the applied
     * values are asserted by the plain-JVM tests (the wall-clock durations
     * themselves are too long to wait out inside the harness).
     */
    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    private static final int READ_TIMEOUT_SECONDS = 60;
    private static final int WRITE_TIMEOUT_SECONDS = 60;

    private static volatile OkHttpClient sharedClient;
    private static final Object CLIENT_LOCK = new Object();

    private final PostProcessorSettings settings;
    private final Handler uiHandler;
    private final BooleanSupplier validator;
    /**
     * The surface (Activity/Service) that owns this processor's calls.
     * Cancellation is scoped to the owner so destroying one surface never
     * cancels another surface's in-flight post-processing (P0.1).
     */
    private final Object owner;

    /** Creates a processor that delivers callbacks on the caller's thread. */
    public PostProcessor(SettingsManager settings) {
        this(settings, null, null, null);
    }

    /** Creates a processor that delivers callbacks on {@code uiHandler}. */
    public PostProcessor(SettingsManager settings, Handler uiHandler) {
        this(settings, uiHandler, null, null);
    }

    /**
     * Creates a processor that delivers callbacks on {@code uiHandler} only
     * while {@code validator} returns true.
     */
    public PostProcessor(SettingsManager settings, Handler uiHandler,
                         BooleanSupplier validator) {
        this(settings, uiHandler, validator, null);
    }

    /**
     * Creates a processor whose in-flight calls are owned by {@code owner}:
     * {@link #cancelAllFor(Object)} only cancels that owner's calls, so a
     * destroyed Activity/Service cannot interrupt another surface's request.
     */
    public PostProcessor(SettingsManager settings, Handler uiHandler,
                         BooleanSupplier validator, Object owner) {
        this((PostProcessorSettings) settings, uiHandler, validator, owner);
    }

    /**
     * Package-private seam for plain-JVM tests (P1.3): accepts any
     * {@link PostProcessorSettings} implementation, so the payload/fallback
     * contract of {@link #process} can be verified against a controlled
     * HTTP server without an Android Context.
     */
    PostProcessor(PostProcessorSettings settings, Handler uiHandler,
                  BooleanSupplier validator, Object owner) {
        this.settings = settings;
        this.uiHandler = uiHandler;
        this.validator = validator;
        this.owner = owner;
    }

    public static final String PROVIDER_LOCAL_S1 = "local_s1";
    public static final String PRESET_CLEAN = "clean";
    public static final String PRESET_FORMAL = "formal";
    public static final String PRESET_CASUAL = "casual";
    public static final String PRESET_VERBATIM = "verbatim";

    private static final java.util.concurrent.ExecutorService onDeviceExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    public static native String nativeNormalizeOnDevice(String rawText, String preset, String customPrompt);
    public static native void nativeSetS1ModelPath(String path);
    // Lifecycle probes for the on-device provider. Neither has a Java call site
    // today: the native side unloads on its own 60-second idle timer and from
    // App.onTrimMemory -> nativeTrimMemory, which is the hook that actually runs.
    // They are kept as the explicit API for that lifecycle, and R8 removes them
    // from release builds as unreachable members (verified against the release
    // DEX); wiring them up is only meaningful once the on-device inference path
    // is real — see the PLACEHOLDER note on
    // src/post_processor.rs::normalize_text_on_device.
    public static native void nativeUnloadS1();
    public static native boolean nativeIsS1Loaded();
    public static native void nativeTrimMemory(int level);

    /**
     * The settings surface {@link PostProcessor} reads. Implemented by
     * {@link SettingsManager} in production; a fake implements it in the
     * plain-JVM HTTP tests.
     */
    interface PostProcessorSettings {
        boolean isPostProcessEnabled();

        String getEffectiveApiUrl();

        String getApiKey();

        String getModelName();

        String getActivePromptBody();

        default String getProviderId() {
            return "custom";
        }

        default String getPostProcessPreset() {
            return "clean";
        }

        default java.io.File getLocalS1ModelFile() {
            return new java.io.File("/dev/null");
        }

        default boolean isLocalS1ModelInstalled() {
            return false;
        }

        default boolean isPostProcessConfigured() {
            String provider = getProviderId();
            if (PROVIDER_LOCAL_S1.equals(provider)) {
                // Must mirror SettingsManager.isPostProcessConfigured(): a model
                // on disk does not make the provider functional while there is no
                // inference engine behind it.
                return SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE && isLocalS1ModelInstalled();
            }
            String key = getApiKey();
            return key != null && !key.trim().isEmpty();
        }
    }

    private static OkHttpClient getSharedClient() {
        OkHttpClient client = sharedClient;
        if (client == null) {
            synchronized (CLIENT_LOCK) {
                client = sharedClient;
                if (client == null) {
                    client = buildProductionClient();
                    sharedClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Builds the production client: generous timeouts for real dictations
     * (30 s to connect, 60 s read/write for the complete response) and a
     * small connection pool so consecutive dictations reuse connections.
     *
     * <p>Package-private so the plain-JVM tests can assert the exact applied
     * values — the guarantee users actually get on device — without having
     * to wait for the 30 s/60 s wall-clock timeouts themselves.</p>
     */
    static OkHttpClient buildProductionClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
    }

    /**
     * Test-only seam (plain-JVM harness): installs a client with test-scaled
     * timeouts so {@link #process} exercises the real OkHttp timeout path
     * (socket read timeout → {@code onFailure} → onError) without waiting for
     * the production 30 s/60 s values. Not for production use — restore with
     * {@link #resetSharedClientForTests()} (or leave null to recreate the
     * production client lazily).
     */
    static void setSharedClientForTests(OkHttpClient client) {
        sharedClient = client;
    }

    /** Test-only seam: restores lazy production-client creation. */
    static void resetSharedClientForTests() {
        sharedClient = null;
    }

    /**
     * Cancels every in-flight OkHttp call created by any processor instance.
     * Reserved for real process-global shutdown events: the IME service being
     * destroyed and the "post-processing disabled" toggle, which also
     * broadcasts to the IME component.
     */
    public static void cancelAll() {
        CallRegistry.cancelAll();
    }

    /**
     * Cancels only the in-flight calls owned by {@code owner} (by identity).
     * Used by surfaces on destroy/cancel so one surface's teardown never
     * cancels a legitimate request from another surface.
     */
    public static void cancelAllFor(Object owner) {
        CallRegistry.cancelAllForOwner(owner);
    }

    /**
     * Sends exactly one non-streaming completion request for the final ASR
     * transcript. The callback receives either the complete refined response
     * or an error so each caller can deliver the raw transcript as fallback.
     */
    public void process(final String rawText, final PostProcessCallback callback) {
        processInternal(rawText, callback, false);
    }

    /**
     * Runs the real configured endpoint with a fixed, non-user diagnostic
     * sentence. This deliberately bypasses the enabled marker so the settings
     * screen can distinguish "disabled" from "configured but broken" without
     * sending any user transcript.
     */
    public void testConnection(final PostProcessCallback callback) {
        processInternal(DIAGNOSTIC_INPUT, new PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                if (DIAGNOSTIC_MARKER.equals(refinedText != null
                        ? refinedText.trim() : "")) {
                    callback.onSuccess(refinedText);
                } else {
                    callback.onError("Provider responded, but the diagnostic marker was not returned");
                }
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        }, true);
    }

    private void processInternal(final String rawText, final PostProcessCallback callback,
                                 boolean forceRequest) {
        if (rawText == null || rawText.trim().isEmpty()) {
            dispatchToUi(() -> callback.onSuccess(rawText != null ? rawText : ""));
            return;
        }

        // Re-check the marker here, not only at each caller, because the toggle
        // can change between receiving the ASR result and creating this call.
        if (!forceRequest && !settings.isPostProcessEnabled()) {
            dispatchToUi(() -> callback.onSuccess(rawText));
            return;
        }

        if (PROVIDER_LOCAL_S1.equals(settings.getProviderId())) {
            if (!SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE) {
                // Defensive: the settings UI refuses to enable this provider, but
                // a marker file written by an older build (or a direct caller
                // such as the diagnostic button) must still not be answered with
                // the unchanged transcript dressed up as a refined one.
                dispatchToUi(() -> callback.onError(LOCAL_S1_UNAVAILABLE_ERROR));
                return;
            }
            processOnDeviceInternal(rawText, callback, forceRequest);
            return;
        }

        final String completionUrl = buildCompletionUrl(settings.getEffectiveApiUrl());
        final String apiKey = settings.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            // An upgraded installation can legitimately have lost the legacy
            // Keystore key. Fail before touching the network so release builds
            // do not look like a broken provider and the raw transcript is
            // delivered immediately by every caller. The diagnostic button
            // uses the same guard, so it never sends an unauthenticated probe.
            dispatchToUi(() -> callback.onError(MISSING_API_KEY_ERROR));
            return;
        }
        final String model = settings.getModelName();
        final String systemInstruction = settings.getActivePromptBody();
        final boolean injected = systemInstruction != null
                && systemInstruction.contains("${output}");
        String activeSystemInstruction = systemInstruction;
        if (forceRequest) {
            String diagnosticInstruction = "\n\nDIAGNOSTIC MODE: For this diagnostic request only, "
                    + "return exactly " + DIAGNOSTIC_MARKER + " and nothing else.";
            activeSystemInstruction = (activeSystemInstruction != null
                    ? activeSystemInstruction : "") + diagnosticInstruction;
        }
        final String requestSystemInstruction = injected
                ? activeSystemInstruction.replace("${output}", rawText)
                : activeSystemInstruction;

        try {
            JSONObject json = new JSONObject();
            json.put("model", model);
            // Be explicit: this is the single complete-response path. Some
            // OpenAI-compatible providers otherwise apply their own default.
            json.put("stream", false);

            JSONArray messages = new JSONArray();
            if (requestSystemInstruction != null
                    && !requestSystemInstruction.trim().isEmpty()) {
                JSONObject systemMessage = new JSONObject();
                systemMessage.put("role", "system");
                systemMessage.put("content", requestSystemInstruction.trim());
                messages.put(systemMessage);
            }

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            // A ${output} prompt already contains the transcript in the system
            // message. Do not send it a second time: duplicate input makes some
            // providers echo the transcript instead of editing it.
            userMessage.put("content", injected
                    ? "Apply the instructions to the transcript above."
                    : rawText);
            messages.put(userMessage);
            json.put("messages", messages);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(completionUrl)
                    .addHeader("Accept", "application/json")
                    .post(body);
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }

            Call call = getSharedClient().newCall(requestBuilder.build());
            CallRegistry.register(call, owner);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    CallRegistry.unregister(call);
                    String message = e.getMessage() != null
                            ? e.getMessage() : "Post-processing request failed";
                    debugLog("API call failed: " + message);
                    dispatchToUi(() -> callback.onError("Network error: " + message));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    CallRegistry.unregister(call);
                    // Always close responses, including HTTP errors and parse
                    // failures, so repeated dictations do not exhaust OkHttp.
                    try (Response responseResource = response) {
                        // If the user disabled post-processing while the request
                        // was in flight, the raw transcript wins.
                        if (!forceRequest && !settings.isPostProcessEnabled()) {
                            dispatchToUi(() -> callback.onSuccess(rawText));
                            return;
                        }

                        if (!responseResource.isSuccessful()) {
                            String error = describeHttpError(responseResource);
                            debugLog(error);
                            dispatchToUi(() -> callback.onError(error));
                            return;
                        }

                        try {
                            if (responseResource.body() == null) {
                                dispatchToUi(() -> callback.onError("Empty response body"));
                                return;
                            }
                            String responseData = responseResource.body().string();
                            JSONObject responseJson = new JSONObject(responseData);
                            JSONArray choices = responseJson.getJSONArray("choices");
                            if (choices.length() == 0) {
                                dispatchToUi(() -> callback.onError("Empty response from AI"));
                                return;
                            }

                            JSONObject message = choices.getJSONObject(0)
                                    .optJSONObject("message");
                            String resultText = message != null
                                    ? message.optString("content", "").trim()
                                    : "";
                            if (resultText.isEmpty()) {
                                dispatchToUi(() -> callback.onError("Empty response from AI"));
                            } else {
                                // Re-check on the UI thread immediately before delivery.
                                // If the user disabled PP after the HTTP thread parsed the
                                // response, the raw ASR transcript still wins.
                                dispatchToUi(() -> callback.onSuccess(
                                        forceRequest || settings.isPostProcessEnabled()
                                                ? resultText : rawText));
                            }
                        } catch (Exception e) {
                            String error = "Invalid AI response";
                            debugLog("Failed to parse API response: " + e.getClass().getSimpleName());
                            dispatchToUi(() -> callback.onError(error));
                        }
                    }
                }
            });
        } catch (Exception e) {
            String error = e.getMessage() != null
                    ? e.getMessage() : "Failed to create post-processing request";
            debugLog("Failed to create post-processing request: " + e.getClass().getSimpleName());
            dispatchToUi(() -> callback.onError("Request setup error: " + error));
        }
    }

    private void processOnDeviceInternal(final String rawText, final PostProcessCallback callback,
                                         final boolean forceRequest) {
        if (!settings.isLocalS1ModelInstalled()) {
            dispatchToUi(() -> callback.onError("On-device S1-mini model not found. Download it in settings."));
            return;
        }

        final java.io.File modelFile = settings.getLocalS1ModelFile();
        final String modelPath = (modelFile != null) ? modelFile.getAbsolutePath() : "";
        final String preset = settings.getPostProcessPreset();
        final String customPrompt = settings.getActivePromptBody();

        onDeviceExecutor.execute(() -> {
            try {
                nativeSetS1ModelPath(modelPath);
                if (forceRequest) {
                    // Diagnostic probe for on-device S1 normalizer
                    String result = nativeNormalizeOnDevice(DIAGNOSTIC_INPUT, "clean", null);
                    if (result != null) {
                        dispatchToUi(() -> callback.onSuccess(DIAGNOSTIC_MARKER));
                    } else {
                        dispatchToUi(() -> callback.onError("On-device model initialization failed"));
                    }
                    return;
                }

                String refined = nativeNormalizeOnDevice(rawText, preset, customPrompt);
                if (refined != null && !refined.trim().isEmpty()) {
                    dispatchToUi(() -> callback.onSuccess(refined));
                } else {
                    dispatchToUi(() -> callback.onError("On-device normalizer returned empty output"));
                }
            } catch (Throwable t) {
                try {
                    Log.e(TAG, "Error running on-device normalization", t);
                } catch (Throwable ignored) {}
                dispatchToUi(() -> callback.onError(t.getMessage() != null ? t.getMessage() : "On-device normalization error"));
            }
        });
    }

    /** Returns a release-safe explanation for common provider failures. */
    private static String describeHttpError(Response response) throws IOException {
        int code = response.code();
        String message;
        switch (code) {
            case 400:
                message = "bad request (check the model and provider)";
                break;
            case 401:
                message = "unauthorized (check the API key)";
                break;
            case 403:
                message = "forbidden (check the API key or account)";
                break;
            case 404:
                message = "endpoint or model not found";
                break;
            case 408:
                message = "provider timed out";
                break;
            case 429:
                message = "provider rate limit or quota exceeded";
                break;
            default:
                message = "provider rejected the request";
                break;
        }

        if (BuildConfig.DEBUG && response.body() != null) {
            try {
                String body = response.body().string();
                String providerMessage = extractProviderErrorMessage(body);
                if (!providerMessage.isEmpty()) {
                    message += ": " + providerMessage;
                }
            } catch (IOException e) {
                // Preserve the callback contract even when an error body cannot
                // be read; never let diagnostics turn into a silent callback loss.
                debugLog("Could not read provider error body: "
                        + e.getClass().getSimpleName());
            }
        }
        return "API Error " + code + ": " + message;
    }

    /** Extracts only a short, sanitized provider message; never logs the body. */
    private static String extractProviderErrorMessage(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            String message = error != null ? error.optString("message", "") : "";
            if (message == null || message.trim().isEmpty()) return "";
            message = message.replaceAll("\\s+", " ").trim();
            message = message.replaceAll("(?i)bearer\\s+\\S+", "Bearer [redacted]");
            message = message.replaceAll("(?i)sk-[A-Za-z0-9_-]+", "[redacted]");
            if (message.length() > 180) message = message.substring(0, 180) + "…";
            return message;
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Keeps diagnostic details out of release logs, including transcript data. */
    private static void debugLog(String message) {
        if (BuildConfig.DEBUG) {
            try {
                Log.d(TAG, message);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String buildCompletionUrl(String configuredUrl) {
        String apiUrl = configuredUrl != null ? configuredUrl.trim() : "";
        while (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        if (!apiUrl.endsWith("/chat/completions")) {
            apiUrl += "/chat/completions";
        }
        return apiUrl;
    }

    /** Runs {@code action} on the configured handler, if the owner is valid. */
    private void dispatchToUi(Runnable action) {
        if (uiHandler != null) {
            uiHandler.post(() -> {
                if (validator != null && !validator.getAsBoolean()) return;
                action.run();
            });
        } else {
            if (validator != null && !validator.getAsBoolean()) return;
            action.run();
        }
    }

    /** Fetches model IDs from the provider's OpenAI-compatible /models endpoint. */
    public void fetchModels(final ModelsCallback callback) {
        String baseUrl = settings.getEffectiveApiUrl() != null
                ? settings.getEffectiveApiUrl().trim() : "";
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String modelsUrl = baseUrl;
        if (!modelsUrl.endsWith("/models")) {
            modelsUrl += "/models";
        }

        String apiKey = settings.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            dispatchToUi(() -> callback.onError(MISSING_API_KEY_ERROR));
            return;
        }
        final Request request;
        try {
            Request.Builder requestBuilder = new Request.Builder().url(modelsUrl).get();
            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }
            request = requestBuilder.build();
        } catch (Exception e) {
            debugLog("Failed to create model-list request: " + e.getClass().getSimpleName());
            dispatchToUi(() -> callback.onError("Invalid provider URL"));
            return;
        }

        Call call = getSharedClient().newCall(request);
        CallRegistry.register(call, owner);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                CallRegistry.unregister(call);
                String message = e.getMessage() != null ? e.getMessage() : "Request failed";
                dispatchToUi(() -> callback.onError(message));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                CallRegistry.unregister(call);
                try (Response responseResource = response) {
                    if (!responseResource.isSuccessful()) {
                        String error = describeHttpError(responseResource);
                        debugLog(error);
                        dispatchToUi(() -> callback.onError(error));
                        return;
                    }
                    try {
                        if (responseResource.body() == null) {
                            dispatchToUi(() -> callback.onError("Empty response body"));
                            return;
                        }
                        String data = responseResource.body().string();
                        JSONObject json = new JSONObject(data);
                        JSONArray array = json.getJSONArray("data");
                        java.util.List<String> models = new java.util.ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            models.add(array.getJSONObject(i).getString("id"));
                        }
                        Collections.sort(models);
                        dispatchToUi(() -> callback.onSuccess(models));
                    } catch (Exception e) {
                        debugLog("Failed to parse model list: " + e.getClass().getSimpleName());
                        dispatchToUi(() -> callback.onError("Invalid model-list response"));
                    }
                }
            }
        });
    }

    public interface PostProcessCallback {
        void onSuccess(String refinedText);
        void onError(String error);
    }

    public interface ModelsCallback {
        void onSuccess(java.util.List<String> models);
        void onError(String error);
    }

    /**
     * Registry of in-flight OkHttp calls and their owning surface.
     *
     * <p>Calls are keyed in a concurrent map so cancellation can be scoped:
     * {@link #cancelAll()} is the process-global shutdown path (feature
     * disabled, IME death), while {@link #cancelAllForOwner(Object)} only
     * cancels one owner's calls by identity — destroying the popup or the
     * settings screen must never interrupt a dictation from another surface.</p>
     *
     * <p>Kept as a separate, package-visible unit so the isolation semantics
     * are covered by plain-JVM tests (see CallRegistryTest) without touching
     * Android framework classes.</p>
     */
    static final class CallRegistry {
        private static final Map<Call, Object> OWNERS = new ConcurrentHashMap<>();

        private CallRegistry() {
        }

        /** Sentinel for ownerless calls: still globally cancellable, never
         *  matched by an owner-scoped cancel (ConcurrentHashMap forbids null
         *  values, and legacy constructors pass a null owner). */
        private static final Object NO_OWNER = new Object();

        static void register(Call call, Object owner) {
            if (call != null) OWNERS.put(call, owner != null ? owner : NO_OWNER);
        }

        static void unregister(Call call) {
            if (call != null) OWNERS.remove(call);
        }

        /** Cancels every registered call (global shutdown). */
        static void cancelAll() {
            for (Call call : OWNERS.keySet()) {
                if (!call.isCanceled()) call.cancel();
            }
        }

        /**
         * Cancels only the calls registered with exactly {@code owner}
         * (identity comparison — surfaces pass {@code this}).
         */
        static void cancelAllForOwner(Object owner) {
            if (owner == null) return;
            for (Map.Entry<Call, Object> entry : OWNERS.entrySet()) {
                if (entry.getValue() == owner) {
                    Call call = entry.getKey();
                    if (!call.isCanceled()) call.cancel();
                }
            }
        }
    }
}
