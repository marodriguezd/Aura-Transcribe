package dev.notune.transcribe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Plain-JVM coverage for {@link PostProcessor#process(String, PostProcessCallback)}
 * against a controlled HTTP server (P1.3): exactly one non-streaming request
 * with {@code stream:false}, the transcript injected at most once, correct
 * handling of success/empty/HTTP-error responses and of the toggle-off
 * during-flight fallback to the raw transcript.
 *
 * <p>Since 2026-08-04 the harness also closes the two remaining network
 * scenarios that do not need hardware (P1.5): a real DNS failure against
 * the RFC 6761 reserved {@code .invalid} host, and a real OkHttp connect
 * timeout against the TEST-NET address {@code 192.0.2.1} with a
 * test-scaled client. Both exercise genuine network/OkHttp mechanisms on
 * the JVM and assert the caller-facing {@code onError} contract (the
 * surfaces then deliver the raw transcript).</p>
 *
 * <p>Uses the package-private {@link PostProcessor.PostProcessorSettings}
 * seam with a fake settings object, so no Android Context or framework
 * classes are involved — matching the Guantelette JVM-test harness.</p>
 *
 * <p><strong>Timeout coverage:</strong> the real OkHttp timeout path
 * (socket read timeout → {@code onFailure} → onError) is exercised with a
 * test-scaled client (100 ms read timeout against a stalled MockWebServer
 * body), and the production values (30 s connect, 60 s read/write) are
 * asserted via {@link PostProcessor#buildProductionClient()}. The wall-clock
 * durations themselves are <em>not</em> waited out here — a 60 s read
 * timeout would stall CI for a minute per run — so that part of the
 * guarantee stays with device/network smoke tests.</p>
 */
public class PostProcessorTest {

    private MockWebServer server;

    /** Fake settings source for the processor under test. */
    static class FakeSettings implements PostProcessor.PostProcessorSettings {
        boolean enabled = true;
        String url;
        String key = "test-key";
        String model = "test-model";
        String prompt = "";

        @Override
        public boolean isPostProcessSwitchedOn() {
            return enabled;
        }

        @Override
        public String getEffectiveApiUrl() {
            return url;
        }

        @Override
        public void readApiKey(java.util.concurrent.Executor callbackExecutor,
                               java.util.function.Consumer<ApiKeyRead> callback) {
            // Handed straight through the executor the processor passes in: the
            // ordering guarantee itself belongs to the credential lane
            // (SettingsManager), which CredentialOperationsTest drives with real
            // latches. Every assertion in this suite is about the request the
            // credential produces, not about the queue.
            callbackExecutor.execute(() -> callback.accept(ApiKeyRead.loaded(key)));
        }

        @Override
        public String getModelName() {
            return model;
        }

        @Override
        public String getActivePromptBody() {
            return prompt;
        }

        @Override
        public String getProviderId() {
            return "custom";
        }

        @Override
        public String getPostProcessPreset() {
            return "clean";
        }

        @Override
        public java.io.File getLocalS1ModelFile() {
            return new java.io.File("/dev/null");
        }

        @Override
        public boolean isLocalS1ModelInstalled() {
            return false;
        }
    }

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        // Never leak a test-scaled client into the next test: restore the
        // lazy production client regardless of which test ran.
        PostProcessor.resetSharedClientForTests();
        server.shutdown();
    }

    private static String completionJson(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    private PostProcessor newProcessor(FakeSettings settings, Object owner) {
        return new PostProcessor(settings, null, null, owner);
    }

    @Test
    public void aMissingApiKeyNeverReachesTheNetwork() throws Exception {
        // Ordinary dictation path: the switch is on but the ordered credential read
        // finds no usable credential, so the raw transcript is delivered exactly
        // once and no request is made — what every surface did in its `else` branch.
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        settings.key = "";

        CountDownLatch raw = new CountDownLatch(1);
        AtomicReference<String> rawOutcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw",
                callback("ok:", "err:", rawOutcome, raw));

        assertTrue("the raw transcript must still be delivered", raw.await(2, TimeUnit.SECONDS));
        assertEquals("ok:raw", rawOutcome.get());
        assertEquals("a missing key must not make an HTTP request", null,
                server.takeRequest(200, TimeUnit.MILLISECONDS));

        // Forced path (the settings screen's diagnostic button): the same missing
        // credential is reported as an error before the network is touched, so the
        // screen can say "API key required" instead of looking like a broken
        // provider — and an unreadable store is reported the same way rather than as
        // a successful request.
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> errorOutcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).testConnection(
                callback("ok:", "err:", errorOutcome, failed));

        assertTrue("the diagnostic must report the missing key",
                failed.await(2, TimeUnit.SECONDS));
        assertEquals("err:" + PostProcessor.MISSING_API_KEY_ERROR, errorOutcome.get());
        assertEquals("the diagnostic must not make an HTTP request either", null,
                server.takeRequest(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void singleNonStreamingRequestWithInjectedOutputOnce() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/v1").toString();
        settings.prompt = "Edit this: ${output}\nFix grammar.";

        // A delayed response keeps the request observable in-flight; the
        // server-side recording lets us assert the exact payload.
        server.enqueue(new MockResponse().setBodyDelay(1, TimeUnit.SECONDS)
                .setBody(completionJson("texto refinado")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("hola mundo",
                new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        outcome.set("ok:" + refinedText);
                        done.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        outcome.set("err:" + error);
                        done.countDown();
                    }
                });

        assertTrue("callback must fire", done.await(5, TimeUnit.SECONDS));
        assertEquals("ok:texto refinado", outcome.get());

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/v1/chat/completions", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));

        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertFalse("stream must be explicitly false", body.getBoolean("stream"));
        assertEquals("test-model", body.getString("model"));

        JSONArray messages = body.getJSONArray("messages");
        assertEquals(2, messages.length());
        String system = messages.getJSONObject(0).getString("content");
        assertTrue("system prompt must embed the transcript at ${output}",
                system.contains("Edit this: hola mundo"));
        assertEquals("Apply the instructions to the transcript above.",
                messages.getJSONObject(1).getString("content"));

        // The raw transcript must appear exactly once in the whole payload
        // (in the system message), never duplicated as a user message.
        String full = body.toString();
        assertEquals(1, countOccurrences(full, "hola mundo"));
    }

    @Test
    public void withoutOutputMarkerTranscriptGoesAsUserMessage() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        settings.prompt = "Just fix grammar.";
        server.enqueue(new MockResponse().setBody(completionJson("fixed")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw text",
                callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("ok:fixed", outcome.get());

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        JSONArray messages = body.getJSONArray("messages");
        assertEquals("raw text", messages.getJSONObject(messages.length() - 1).getString("content"));
    }

    @Test
    public void emptyResultTextReportsError() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setBody(completionJson("   ")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("err:Empty response from AI", outcome.get());
    }

    @Test
    public void nullContentReportsEmptyResponseInsteadOfParseFailure() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setBody(
                "{\"choices\":[{\"message\":{\"content\":null}}]}"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("err:Empty response from AI", outcome.get());
    }

    @Test
    public void diagnosticConnectionTestRunsWhenFeatureIsDisabled() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.enabled = false;
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setBody(completionJson("POSTPROCESS_DIAGNOSTIC_OK")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).testConnection(
                callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("ok:POSTPROCESS_DIAGNOSTIC_OK", outcome.get());
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertFalse("diagnostic request must remain non-streaming", body.getBoolean("stream"));
        String payload = body.toString();
        assertTrue("diagnostic marker instruction must be sent",
                payload.contains("POSTPROCESS_DIAGNOSTIC_OK"));
        assertTrue("diagnostic request must not contain a user transcript",
                payload.contains("This is a diagnostic post-processing test."));
    }

    @Test
    public void providerErrorMessageRedactsCredentialLikeValues() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setResponseCode(401).setBody(
                "{\"error\":{\"message\":\"invalid Bearer sk-secret-token\"}}"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw",
                callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(outcome.get().contains("API Error 401"));
        assertFalse("credential must not reach the caller-facing diagnostic",
                outcome.get().contains("sk-secret-token"));
        assertFalse("bearer value must not reach the caller-facing diagnostic",
                outcome.get().contains("Bearer sk-secret-token"));
        assertTrue(outcome.get().contains("[redacted]"));
    }

    @Test
    public void productionClientAppliesTimeoutGuarantees() {
        // The exact guarantee users get on device: 30 s to connect, 60 s
        // read/write for the complete non-streaming response. Asserting the
        // applied values is cheap in the harness; waiting out 60 s is not.
        OkHttpClient client = PostProcessor.buildProductionClient();
        assertEquals(30_000, client.connectTimeoutMillis());
        assertEquals(60_000, client.readTimeoutMillis());
        assertEquals(60_000, client.writeTimeoutMillis());
    }

    @Test
    public void stalledResponseHitsReadTimeoutAndReportsError() throws Exception {
        // Real OkHttp timeout behavior, scaled down for CI: a 100 ms read
        // timeout against a 5 s-delayed body must abort the call and surface
        // onError (the caller then delivers the raw transcript as fallback).
        // The production 60 s read timeout would need a minute to verify the
        // same path — that wall-clock duration stays out of the harness.
        PostProcessor.setSharedClientForTests(new OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.MILLISECONDS)
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .writeTimeout(100, TimeUnit.MILLISECONDS)
                .build());
        try {
            FakeSettings settings = new FakeSettings();
            settings.url = server.url("/").toString();
            server.enqueue(new MockResponse().setHeadersDelay(5, TimeUnit.SECONDS)
                    .setBody(completionJson("late")));

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> outcome = new AtomicReference<>("pending");
            newProcessor(settings, new Object()).process("raw",
                    callback("ok:", "err:", outcome, done));

            // 6 s > the 5 s body delay: if the timeout mechanism were broken,
            // the delayed body would arrive and the assertion below would fail
            // with the more diagnostic "expected an error, got ok:late".
            assertTrue("timeout callback must fire", done.await(6, TimeUnit.SECONDS));
            assertTrue("expected an error, got " + outcome.get(),
                    outcome.get().startsWith("err:"));
            assertTrue("expected socket timeout message, got " + outcome.get(),
                    outcome.get().toLowerCase().contains("timed out") || outcome.get().toLowerCase().contains("timeout"));
        } finally {
            PostProcessor.resetSharedClientForTests();
        }
    }

    @Test
    public void dnsFailureReportsError() throws Exception {
        // Deterministic DNS failure: inject an OkHttp Dns that always throws
        // UnknownHostException, so the test exercises the real onFailure
        // contract without depending on the runner's resolver/proxy. The
        // .invalid-style host name is what a real failure produces.
        PostProcessor.setSharedClientForTests(new OkHttpClient.Builder()
                .dns(new Dns() {
                    @Override
                    public java.util.List<InetAddress> lookup(String hostname)
                            throws java.net.UnknownHostException {
                        throw new java.net.UnknownHostException(hostname);
                    }
                })
                .proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build());
        try {
            FakeSettings settings = new FakeSettings();
            settings.url = "https://no-such-host.invalid/v1";

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> outcome = new AtomicReference<>("pending");
            newProcessor(settings, new Object()).process("raw",
                    callback("ok:", "err:", outcome, done));

            assertTrue("DNS failure callback must fire", done.await(10, TimeUnit.SECONDS));
            // The contract surfaces onError; the caller (IME/popup/...) then
            // delivers the raw transcript as fallback.
            assertTrue("expected a DNS/connect error, got " + outcome.get(),
                    outcome.get().startsWith("err:"));
        } finally {
            PostProcessor.resetSharedClientForTests();
        }
    }

    @Test
    public void connectTimeoutReportsError() throws Exception {
        // TEST-NET (RFC 5737) 192.0.2.1 is guaranteed unroutable; with a
        // 500 ms connect timeout the OkHttp connect phase must time out
        // instead of succeeding, exercising the real SocketTimeoutException
        // path (the production connect timeout is 30 s — see
        // productionClientAppliesTimeoutGuarantees; waiting it out would
        // stall CI, so this uses a scaled client via the seam).
        PostProcessor.setSharedClientForTests(new OkHttpClient.Builder()
                // Bypass any runner proxy so the connect attempt goes to the
                // TEST-NET address directly and reliably times out.
                .proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build());
        try {
            FakeSettings settings = new FakeSettings();
            settings.url = "http://192.0.2.1/v1";

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> outcome = new AtomicReference<>("pending");
            newProcessor(settings, new Object()).process("raw",
                    callback("ok:", "err:", outcome, done));

            assertTrue("connect timeout callback must fire", done.await(10, TimeUnit.SECONDS));
            assertTrue("expected a connect timeout error, got " + outcome.get(),
                    outcome.get().startsWith("err:"));
        } finally {
            PostProcessor.resetSharedClientForTests();
        }
    }

    @Test
    public void httpErrorReportsApiError() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setResponseCode(500).setBody(
                "{\"error\":{\"message\":\"provider says model is unavailable\"}}"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("err:API Error 500: provider rejected the request: provider says model is unavailable",
                outcome.get());
    }

    @Test
    public void malformedJsonReportsParseError() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setBody("this is not json"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("expected parse error, got " + outcome.get(), outcome.get().startsWith("err:"));
    }

    @Test
    public void toggleOffDuringFlightDeliversRawTranscript() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        // Slow response so we can flip the toggle while the request flies.
        server.enqueue(new MockResponse().setBodyDelay(1, TimeUnit.SECONDS)
                .setBody(completionJson("refined")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>("pending");
        newProcessor(settings, new Object()).process("raw", callback("ok:", "err:", outcome, done));

        // The user disabled post-processing while the request was in flight.
        settings.enabled = false;

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("ok:raw", outcome.get());
    }

    @Test
    public void exactlyOneFinalDeliveryPerRequest() throws Exception {
        FakeSettings settings = new FakeSettings();
        settings.url = server.url("/").toString();
        server.enqueue(new MockResponse().setBody(completionJson("once")));

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger(0);
        newProcessor(settings, new Object()).process("raw",
                new PostProcessor.PostProcessCallback() {
                    @Override
                    public void onSuccess(String refinedText) {
                        deliveries.incrementAndGet();
                        done.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        deliveries.incrementAndGet();
                        done.countDown();
                    }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("exactly one final delivery", 1, deliveries.get());
    }

    @Test
    public void localS1ProviderWithoutModelFailsSafely() throws Exception {
        FakeSettings settings = new FakeSettings() {
            @Override
            public String getProviderId() {
                return PostProcessor.PROVIDER_LOCAL_S1;
            }

            @Override
            public boolean isLocalS1ModelInstalled() {
                return false;
            }
        };

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> errorRef = new AtomicReference<>();

        newProcessor(settings, new Object()).process("hello world", new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                done.countDown();
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
                done.countDown();
            }
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotNull(errorRef.get());
        assertTrue(errorRef.get().contains("S1-mini"));
    }

    /**
     * A credential source that answers from another thread, counts the reads and
     * can be parked mid-flight. The ordering guarantee itself belongs to the
     * credential lane (driven with real latches in {@link CredentialOperationsTest});
     * what these tests pin is the consumer side: one read per call, no request and
     * no callback when the read has no usable answer, and no UI update once the
     * surface is gone.
     */
    static class AsyncCredentialSettings extends FakeSettings {
        private final Executor deliverOn;
        final AtomicInteger reads = new AtomicInteger();
        ApiKeyRead outcome = ApiKeyRead.loaded("test-key");
        /** When set, the answer is held until the test releases it. */
        CountDownLatch gate;
        /** Counts down after the processor's continuation has run. */
        CountDownLatch continuationRan;

        AsyncCredentialSettings(Executor deliverOn) {
            this.deliverOn = deliverOn;
        }

        @Override
        public void readApiKey(Executor callbackExecutor, java.util.function.Consumer<ApiKeyRead> callback) {
            reads.incrementAndGet();
            deliverOn.execute(() -> callbackExecutor.execute(() -> {
                CountDownLatch held = gate;
                if (held != null) {
                    try {
                        if (!held.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("the credential read was never released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                callback.accept(outcome);
                CountDownLatch ran = continuationRan;
                if (ran != null) ran.countDown();
            }));
        }
    }

    /** Credential source whose storage cannot be read. */
    static class UnreadableCredentialSettings extends FakeSettings {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public void readApiKey(Executor callbackExecutor, java.util.function.Consumer<ApiKeyRead> callback) {
            reads.incrementAndGet();
            callbackExecutor.execute(() -> callback.accept(ApiKeyRead.unreadable()));
        }
    }

    /** Counts every final delivery, whatever its kind. */
    private static final class CountingCallback implements PostProcessor.PostProcessCallback {
        final AtomicInteger deliveries = new AtomicInteger();
        final AtomicReference<String> outcome = new AtomicReference<>("pending");
        private final CountDownLatch done;

        CountingCallback(CountDownLatch done) {
            this.done = done;
        }

        @Override
        public void onSuccess(String refinedText) {
            outcome.set("ok:" + refinedText);
            deliveries.incrementAndGet();
            done.countDown();
        }

        @Override
        public void onError(String error) {
            outcome.set("err:" + error);
            deliveries.incrementAndGet();
            done.countDown();
        }
    }

    @Test
    public void anUnreadableCredentialIsNeverAnsweredWithARefinedTranscript() throws Exception {
        // Storage that exists but cannot be read is not "not configured": either way
        // no request may be sent, and the ordinary dictation path must still deliver
        // the raw transcript exactly once.
        UnreadableCredentialSettings settings = new UnreadableCredentialSettings();
        settings.url = server.url("/").toString();

        CountDownLatch raw = new CountDownLatch(1);
        CountingCallback dictation = new CountingCallback(raw);
        newProcessor(settings, new Object()).process("raw", dictation);

        assertTrue("the raw transcript must still be delivered", raw.await(2, TimeUnit.SECONDS));
        assertEquals("ok:raw", dictation.outcome.get());
        assertEquals(1, dictation.deliveries.get());
        assertEquals("one ordered read per call", 1, settings.reads.get());
        assertEquals("an unreadable credential must not reach the network", null,
                server.takeRequest(200, TimeUnit.MILLISECONDS));

        // The settings screen's diagnostic button: reported as a configuration
        // problem, so the screen can say "API key required" instead of showing a
        // provider error or a fake refinement.
        CountDownLatch failed = new CountDownLatch(1);
        CountingCallback diagnostic = new CountingCallback(failed);
        newProcessor(settings, new Object()).testConnection(diagnostic);

        assertTrue(failed.await(2, TimeUnit.SECONDS));
        assertEquals("err:" + PostProcessor.MISSING_API_KEY_ERROR, diagnostic.outcome.get());
        assertEquals(1, diagnostic.deliveries.get());
        assertEquals("one ordered read per call", 2, settings.reads.get());
        assertEquals("the diagnostic must not probe without a credential", null,
                server.takeRequest(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void anAsynchronousCredentialReadMakesExactlyOneRequestAndOneDelivery()
            throws Exception {
        ExecutorService credentialThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pp-credential-read");
            thread.setDaemon(true);
            return thread;
        });
        try {
            AsyncCredentialSettings settings = new AsyncCredentialSettings(credentialThread);
            settings.url = server.url("/").toString();
            server.enqueue(new MockResponse().setBody(completionJson("refined")));

            CountDownLatch done = new CountDownLatch(1);
            CountingCallback callback = new CountingCallback(done);
            newProcessor(settings, new Object()).process("raw", callback);

            assertTrue("callback must fire", done.await(5, TimeUnit.SECONDS));
            assertEquals("ok:refined", callback.outcome.get());
            assertEquals("exactly one delivery", 1, callback.deliveries.get());
            assertEquals("exactly one ordered read", 1, settings.reads.get());

            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull("the request is sent once the read answers", request);
            assertEquals("Bearer test-key", request.getHeader("Authorization"));
            assertEquals("no second request", null,
                    server.takeRequest(200, TimeUnit.MILLISECONDS));
        } finally {
            credentialThread.shutdownNow();
        }
    }

    @Test
    public void aDestroyedConsumerGetsNoCallbackWhileTheReadStillCompletes() throws Exception {
        ExecutorService credentialThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pp-credential-read");
            thread.setDaemon(true);
            return thread;
        });
        try {
            AsyncCredentialSettings settings = new AsyncCredentialSettings(credentialThread);
            settings.url = server.url("/").toString();
            // Park the read so the surface can be destroyed while it is in flight.
            settings.gate = new CountDownLatch(1);
            settings.continuationRan = new CountDownLatch(1);
            AtomicBoolean alive = new AtomicBoolean(true);
            PostProcessor processor = new PostProcessor(settings, null, alive::get, new Object());

            CountDownLatch done = new CountDownLatch(1);
            CountingCallback callback = new CountingCallback(done);
            processor.process("raw", callback);

            alive.set(false); // the Activity/Service goes away mid-read
            settings.gate.countDown();

            assertTrue("the read must still complete",
                    settings.continuationRan.await(5, TimeUnit.SECONDS));
            assertEquals("the read is submitted once and not reordered",
                    1, settings.reads.get());
            assertEquals("a destroyed consumer receives no update", 0, callback.deliveries.get());
            assertEquals("and no request is made on its behalf", null,
                    server.takeRequest(200, TimeUnit.MILLISECONDS));

            // A live surface afterwards is served normally: the dropped callback did
            // not poison the processor or the credential source.
            server.enqueue(new MockResponse().setBody(completionJson("refined")));
            settings.gate = null;
            settings.continuationRan = null;
            alive.set(true);
            CountDownLatch second = new CountDownLatch(1);
            CountingCallback live = new CountingCallback(second);
            processor.process("raw again", live);

            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertEquals("ok:refined", live.outcome.get());
            assertEquals(1, live.deliveries.get());
            assertEquals(2, settings.reads.get());
        } finally {
            credentialThread.shutdownNow();
        }
    }

    private static PostProcessor.PostProcessCallback callback(
            final String okPrefix, final String errPrefix,
            final AtomicReference<String> outcome, final CountDownLatch done) {
        return new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                outcome.set(okPrefix + refinedText);
                done.countDown();
            }

            @Override
            public void onError(String error) {
                outcome.set(errPrefix + error);
                done.countDown();
            }
        };
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
