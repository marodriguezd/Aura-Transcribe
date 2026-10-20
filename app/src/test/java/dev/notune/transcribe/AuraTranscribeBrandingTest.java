package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure JVM unit test validating Aura Transcribe core branding, constants,
 * audio routing modes, and AI provider configurations.
 */
public class AuraTranscribeBrandingTest {

    @Test
    public void testAudioInputModesConstants() {
        assertEquals("auto", AudioDeviceManager.MIC_MODE_AUTO);
        assertEquals("bluetooth", AudioDeviceManager.MIC_MODE_BLUETOOTH_ONLY);
        assertEquals("builtin", AudioDeviceManager.MIC_MODE_BUILTIN_ONLY);
    }

    @Test
    public void testPostProcessorProvidersIntegrity() {
        assertNotNull(SettingsManager.PROVIDERS);
        assertTrue(SettingsManager.PROVIDERS.length >= 4);

        // Verify Local S1 provider preset
        SettingsManager.Provider localS1 = SettingsManager.providerById(SettingsManager.PROVIDER_LOCAL_S1);
        assertNotNull(localS1);
        assertEquals(SettingsManager.PROVIDER_LOCAL_S1, localS1.id);
        assertEquals("s1-mini-q4_k_m.gguf", localS1.defaultModel);

        // Verify Groq provider preset
        SettingsManager.Provider groq = SettingsManager.providerById("groq");
        assertNotNull(groq);
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl);
    }

    @Test
    public void testPromptProfileIdentifiers() {
        // The style presets are gone: prompts are the built-in default plus the
        // ones the user creates. The default id is the one reserved name, and it
        // is shared by the settings layer and the on-disk store.
        assertEquals("default", SettingsManager.PROMPT_DEFAULT_ID);
        assertEquals(SettingsManager.PROMPT_DEFAULT_ID, PromptProfiles.DEFAULT_ID);
        assertEquals("pp_prompts", PromptProfiles.DIR_NAME);
    }

    @Test
    public void testAdaptiveIconAndImeBranding() {
        java.io.File bgFile = new java.io.File("src/main/res/drawable/ic_launcher_background.xml");
        java.io.File fgFile = new java.io.File("src/main/res/drawable/ic_launcher_foreground.xml");
        java.io.File monoFile = new java.io.File("src/main/res/drawable/ic_launcher_monochrome.xml");
        java.io.File launcherFile = new java.io.File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml");
        java.io.File methodFile = new java.io.File("src/main/res/xml/method.xml");

        // When running from project root or app submodule, verify existence
        if (new java.io.File("app").exists()) {
            bgFile = new java.io.File("app/" + bgFile.getPath());
            fgFile = new java.io.File("app/" + fgFile.getPath());
            monoFile = new java.io.File("app/" + monoFile.getPath());
            launcherFile = new java.io.File("app/" + launcherFile.getPath());
            methodFile = new java.io.File("app/" + methodFile.getPath());
        }

        assertTrue(bgFile.exists());
        assertTrue(fgFile.exists());
        assertTrue(monoFile.exists());
        assertTrue(launcherFile.exists());
        assertTrue(methodFile.exists());
    }
}
