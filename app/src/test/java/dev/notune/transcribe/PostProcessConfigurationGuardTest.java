package dev.notune.transcribe;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plain-JVM test suite for AI post-processing configuration guards.
 * Ensures AI post-processing cannot be enabled or executed when no valid API key
 * or local model is configured.
 */
public class PostProcessConfigurationGuardTest {

    static class TestSettings implements PostProcessor.PostProcessorSettings {
        boolean enabled = false;
        String provider = "groq";
        String apiKey = "";
        boolean localModelInstalled = false;

        @Override
        public boolean isPostProcessSwitchedOn() {
            // Mirrors the production rule: the marker is on and the provider can run
            // at all in this build.
            return enabled && SettingsManager.isProviderAvailable(provider);
        }

        @Override
        public String getEffectiveApiUrl() {
            return "https://api.groq.com/openai/v1";
        }

        @Override
        public void readApiKey(java.util.concurrent.Executor callbackExecutor,
                               java.util.function.Consumer<ApiKeyRead> callback) {
            callbackExecutor.execute(() -> callback.accept(ApiKeyRead.loaded(apiKey)));
        }

        @Override
        public String getModelName() {
            return "llama-3.3-70b-versatile";
        }

        @Override
        public String getActivePromptBody() {
            return "";
        }

        @Override
        public String getProviderId() {
            return provider;
        }

        @Override
        public String getPostProcessPreset() {
            return "clean";
        }

        @Override
        public File getLocalS1ModelFile() {
            return new File("/dev/null");
        }

        @Override
        public boolean isLocalS1ModelInstalled() {
            return localModelInstalled;
        }
    }

    /**
     * The production enabled rule, driven with a switch state and a read outcome.
     * It lives in {@link SettingsManager#resolvePostProcessEnabled} so the async
     * read and the settings screen cannot disagree about it.
     */
    private static boolean enabledWith(String apiKey, String providerId) {
        return SettingsManager.resolvePostProcessEnabled(
                true, providerId, ApiKeyRead.loaded(apiKey), false);
    }

    @Test
    public void testCloudProviderRequiresApiKey() {
        assertFalse("Cloud provider without API key must not be configured",
                enabledWith("", "groq"));
        assertFalse("Cloud provider with whitespace API key must not be configured",
                enabledWith("   ", "groq"));
        assertTrue("Cloud provider with valid API key must be configured",
                enabledWith("gsk_validKey123", "groq"));
        assertFalse("A read that failed is not a configured provider either",
                SettingsManager.resolvePostProcessEnabled(
                        true, "groq", ApiKeyRead.unreadable(), false));
    }

    /**
     * The on-device provider is not "configured" just because its GGUF is on
     * disk: there is no inference engine behind it (see
     * {@link SettingsManager#LOCAL_S1_INFERENCE_AVAILABLE}), so treating it as
     * configured is what previously let a user enable AI cleanup that returned
     * the transcript unchanged. The assertion is written against the flag rather
     * than hard-coded, so it stays correct the day inference is implemented.
     */
    @Test
    public void testLocalProviderIsNotConfiguredWithoutInferenceEngine() {
        assertFalse("Local provider without model installed must not be configured",
                SettingsManager.resolvePostProcessEnabled(
                        true, SettingsManager.PROVIDER_LOCAL_S1, ApiKeyRead.absent(), false));

        if (SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE) {
            assertTrue("Local provider with a model and an inference engine must be configured",
                    SettingsManager.resolvePostProcessEnabled(
                            true, SettingsManager.PROVIDER_LOCAL_S1, ApiKeyRead.absent(), true));
        } else {
            assertFalse("Local provider must not be configured while it has no inference engine",
                    SettingsManager.resolvePostProcessEnabled(
                            true, SettingsManager.PROVIDER_LOCAL_S1, ApiKeyRead.absent(), true));
        }
    }

    @Test
    public void testLocalProviderIsReportedUnavailableWhileCloudProvidersAreAvailable() {
        assertFalse("The on-device provider must report itself unavailable",
                SettingsManager.isProviderAvailable(SettingsManager.PROVIDER_LOCAL_S1));
        assertTrue("Cloud providers must stay available",
                SettingsManager.isProviderAvailable("groq"));
        assertTrue("Cloud providers must stay available",
                SettingsManager.isProviderAvailable("openai"));
    }

    /**
     * A marker left by an older build (or by a user who selected the provider
     * before it was marked unavailable) must not make the app claim it refined
     * anything. Because the provider is not "configured", the normal path skips
     * post-processing entirely and hands the raw transcript straight through —
     * no error toast, and above all no fabricated "refined" text.
     */
    /**
     * The switch state is not what decides whether post-processing runs — the
     * {@code pp_enabled} marker is, and every surface reads it. So the rule that
     * converts a switch state into a persisted value has to refuse the on-device
     * provider on its own, independently of whatever the settings screen happens
     * to be showing (a restored switch, a marker written by an older build, a
     * caller that forgets to check availability).
     */
    @Test
    public void testUnavailableProviderCannotBePersistedAsEnabled() {
        assertFalse("the on-device provider must never be persisted as enabled",
                SettingsManager.resolveEnabledOnSave(SettingsManager.PROVIDER_LOCAL_S1, true));
        assertFalse("... and stays off when the switch is already off",
                SettingsManager.resolveEnabledOnSave(SettingsManager.PROVIDER_LOCAL_S1, false));
        assertTrue("an available provider keeps the requested state",
                SettingsManager.resolveEnabledOnSave("groq", true));
        assertFalse("... and an available provider switched off stays off",
                SettingsManager.resolveEnabledOnSave("groq", false));
        if (SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE) {
            assertTrue("with an inference engine the on-device provider may be enabled",
                    SettingsManager.resolveEnabledOnSave(
                            SettingsManager.PROVIDER_LOCAL_S1, true));
        }
    }

    /**
     * Guards the user-visible half of the contract: the provider catalogue is what
     * the dropdown renders, and a label that reads like a working feature is what
     * made the placeholder believable. Also pins that every *other* catalogue entry
     * stays available, so an edit cannot silently disable cloud providers, and that
     * the on-device entry itself is still listed (its download plumbing and JNI
     * surface are intentionally kept for whenever the engine lands).
     *
     * <p>Whether the download button is visible is driven by the same
     * {@link SettingsManager#isProviderAvailable(String)} predicate asserted here,
     * in {@code PostProcessSettingsActivity.updateLocalModelCard()}; a JVM test
     * cannot inspect that view without Robolectric, so the predicate is the
     * contract and the binding is verified by inspection.
     */
    @Test
    public void testProviderCatalogueIsConsistentWithAvailability() {
        boolean sawLocalProvider = false;
        for (SettingsManager.Provider provider : SettingsManager.PROVIDERS) {
            if (SettingsManager.PROVIDER_LOCAL_S1.equals(provider.id)) {
                sawLocalProvider = true;
                if (!SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE) {
                    assertTrue(
                            "the on-device entry must be labelled unavailable so the dropdown "
                                    + "cannot present it as a working option (label: "
                                    + provider.label + ")",
                            provider.label.toLowerCase(java.util.Locale.ROOT)
                                    .contains("unavailable"));
                }
            } else {
                assertTrue("every other provider must stay available: " + provider.id,
                        SettingsManager.isProviderAvailable(provider.id));
                assertFalse("every provider needs a non-empty label: " + provider.id,
                        provider.label.trim().isEmpty());
            }
        }
        assertTrue("the on-device provider must stay listed (its plumbing is kept on purpose)",
                sawLocalProvider);
    }

    @Test
    public void testStaleLocalProviderDeliversRawTextWithoutPretendingToRefine() {
        TestSettings settings = new TestSettings();
        settings.enabled = true;
        settings.provider = SettingsManager.PROVIDER_LOCAL_S1;
        settings.localModelInstalled = true;

        PostProcessor processor = new PostProcessor(settings, null, null, null);
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicBoolean errorCalled = new AtomicBoolean(false);

        processor.process("texto crudo sin refinar", new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                resultRef.set(refinedText);
            }

            @Override
            public void onError(String error) {
                errorCalled.set(true);
            }
        });

        assertFalse("an unconfigured provider must not raise an error", errorCalled.get());
        assertEquals("the raw transcript must be delivered unchanged",
                "texto crudo sin refinar", resultRef.get());
    }

    /**
     * The forced path (the settings screen's "Test connection" button, and any
     * caller that bypasses the enabled marker) must report the missing inference
     * engine instead of answering with the unchanged diagnostic sentence as if it
     * were a successful refinement.
     */
    @Test
    public void testForcedLocalProviderRunReportsUnavailableInsteadOfEchoingInput() {
        TestSettings settings = new TestSettings();
        settings.enabled = true;
        settings.provider = SettingsManager.PROVIDER_LOCAL_S1;
        settings.localModelInstalled = true;

        PostProcessor processor = new PostProcessor(settings, null, null, null);
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        processor.testConnection(new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                resultRef.set(refinedText);
            }

            @Override
            public void onError(String error) {
                errorRef.set(error);
            }
        });

        assertEquals("the placeholder must fail loudly instead of succeeding",
                PostProcessor.LOCAL_S1_UNAVAILABLE_ERROR, errorRef.get());
        assertNull("the unchanged input must never be delivered as a refined result", resultRef.get());
        assertTrue("the error must name the provider so the UI can explain it",
                PostProcessor.LOCAL_S1_UNAVAILABLE_ERROR.contains("S1-mini"));
    }

    @Test
    public void testEnabledReturnsFalseWhenNotConfigured() {
        assertFalse("Even with the switch on, an absent credential is not enabled",
                enabledWith("", "openai"));
        assertTrue("The switch plus a usable credential is enabled",
                enabledWith("sk-valid-key", "openai"));
        assertFalse("The switch off is never enabled, credential or not",
                SettingsManager.resolvePostProcessEnabled(
                        false, "openai", ApiKeyRead.loaded("sk-valid-key"), false));
    }

    @Test
    public void testPostProcessorBypassesWhenNotConfigured() {
        TestSettings settings = new TestSettings();
        settings.enabled = true;
        settings.provider = "groq";
        settings.apiKey = ""; // Missing API key

        PostProcessor processor = new PostProcessor(settings, null, null, null);
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicBoolean errorCalled = new AtomicBoolean(false);

        processor.process("Hola mundo de prueba", new PostProcessor.PostProcessCallback() {
            @Override
            public void onSuccess(String refinedText) {
                resultRef.set(refinedText);
            }

            @Override
            public void onError(String error) {
                errorCalled.set(true);
            }
        });

        assertFalse("Should not report error when not configured; it should deliver raw text seamlessly",
                errorCalled.get());
        assertEquals("Hola mundo de prueba", resultRef.get());
    }
}
