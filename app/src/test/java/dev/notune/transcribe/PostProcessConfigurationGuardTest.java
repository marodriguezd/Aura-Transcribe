package dev.notune.transcribe;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plain-JVM test suite for AI post-processing configuration guards.
 * Ensures AI post-processing cannot be enabled or executed when no valid API key
 * or supported provider is configured.
 */
public class PostProcessConfigurationGuardTest {

    static class TestSettings implements PostProcessor.PostProcessorSettings {
        boolean enabled = false;
        String provider = "groq";
        String apiKey = "";

        @Override
        public boolean isPostProcessSwitchedOn() {
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
    }

    /**
     * The production enabled rule, driven with a switch state and a read outcome.
     * It lives in {@link SettingsManager#resolvePostProcessEnabled} so the async
     * read and the settings screen cannot disagree about it.
     */
    private static boolean enabledWith(String apiKey, String providerId) {
        return SettingsManager.resolvePostProcessEnabled(
                true, providerId, ApiKeyRead.loaded(apiKey));
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
                        true, "groq", ApiKeyRead.unreadable()));
    }

    @Test
    public void testRemovedOrUnknownProviderIsUnavailableAndNotConfigured() {
        assertFalse("Legacy removed local_s1 id must not be available",
                SettingsManager.isProviderAvailable("local_s1"));
        assertFalse("Null provider id must not be available",
                SettingsManager.isProviderAvailable(null));
        assertFalse("Unknown provider id must not be configured",
                SettingsManager.resolvePostProcessEnabled(
                        true, "local_s1", ApiKeyRead.loaded("gsk_validKey123")));
        assertTrue("Cloud providers must stay available",
                SettingsManager.isProviderAvailable("groq"));
        assertTrue("Cloud providers must stay available",
                SettingsManager.isProviderAvailable("openai"));
    }

    @Test
    public void testUnavailableProviderCannotBePersistedAsEnabled() {
        assertFalse("an unavailable provider must never be persisted as enabled",
                SettingsManager.resolveEnabledOnSave("local_s1", true));
        assertFalse("... and stays off when the switch is already off",
                SettingsManager.resolveEnabledOnSave("local_s1", false));
        assertTrue("an available provider keeps the requested state",
                SettingsManager.resolveEnabledOnSave("groq", true));
        assertFalse("... and an available provider switched off stays off",
                SettingsManager.resolveEnabledOnSave("groq", false));
    }

    @Test
    public void testProviderCatalogueContainsOnlyAvailableProviders() {
        for (SettingsManager.Provider provider : SettingsManager.PROVIDERS) {
            assertTrue("every listed provider must be available: " + provider.id,
                    SettingsManager.isProviderAvailable(provider.id));
            assertFalse("every provider needs a non-empty label: " + provider.id,
                    provider.label.trim().isEmpty());
            assertTrue("placeholder local_s1 must not appear in the catalogue",
                    !"local_s1".equals(provider.id));
        }
    }

    @Test
    public void testStaleRemovedProviderDeliversRawTextWithoutPretendingToRefine() {
        TestSettings settings = new TestSettings();
        settings.enabled = true;
        settings.provider = "local_s1";
        settings.apiKey = "gsk_validKey123";

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

        assertFalse("an unavailable provider must not raise an error on process()", errorCalled.get());
        assertEquals("the raw transcript must be delivered unchanged",
                "texto crudo sin refinar", resultRef.get());
    }

    @Test
    public void testForcedRemovedProviderRunReportsUnavailableInsteadOfHittingNetwork() {
        TestSettings settings = new TestSettings();
        settings.enabled = true;
        settings.provider = "local_s1";
        settings.apiKey = "gsk_validKey123";

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

        assertEquals("the unavailable provider must fail loudly instead of succeeding",
                PostProcessor.UNAVAILABLE_PROVIDER_ERROR, errorRef.get());
        assertNull("the unchanged input must never be delivered as a refined result", resultRef.get());
    }

    @Test
    public void testEnabledReturnsFalseWhenNotConfigured() {
        assertFalse("Even with the switch on, an absent credential is not enabled",
                enabledWith("", "openai"));
        assertTrue("The switch plus a usable credential is enabled",
                enabledWith("sk-valid-key", "openai"));
        assertFalse("The switch off is never enabled, credential or not",
                SettingsManager.resolvePostProcessEnabled(
                        false, "openai", ApiKeyRead.loaded("sk-valid-key")));
    }

    @Test
    public void testResolvePostProcessConfiguredIndependentOfSwitchState() {
        assertTrue("A valid credential means the provider is configured to be toggled on",
                SettingsManager.resolvePostProcessConfigured(
                        "openai", ApiKeyRead.loaded("sk-valid-key")));
        assertFalse("An empty credential means the provider is not configured",
                SettingsManager.resolvePostProcessConfigured(
                        "openai", ApiKeyRead.loaded("")));
        assertFalse("An absent credential means the provider is not configured",
                SettingsManager.resolvePostProcessConfigured(
                        "openai", ApiKeyRead.absent()));
        assertFalse("An unreadable credential means the provider is not configured",
                SettingsManager.resolvePostProcessConfigured(
                        "openai", ApiKeyRead.unreadable()));
        assertFalse("An unavailable provider cannot be configured",
                SettingsManager.resolvePostProcessConfigured(
                        "local_s1", ApiKeyRead.absent()));
    }

    @Test
    public void testResolvePostProcessEnabledAgreesWithConfiguredAndSwitch() {
        ApiKeyRead validKey = ApiKeyRead.loaded("sk-valid-key");
        assertTrue("Switch ON + configured -> enabled",
                SettingsManager.resolvePostProcessEnabled(true, "openai", validKey));
        assertFalse("Switch OFF + configured -> disabled",
                SettingsManager.resolvePostProcessEnabled(false, "openai", validKey));

        ApiKeyRead emptyKey = ApiKeyRead.loaded("");
        assertFalse("Switch ON + unconfigured -> disabled",
                SettingsManager.resolvePostProcessEnabled(true, "openai", emptyKey));
        assertFalse("Switch OFF + unconfigured -> disabled",
                SettingsManager.resolvePostProcessEnabled(false, "openai", emptyKey));
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
