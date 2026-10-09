# ============================================================================
# Aura Transcribe — R8 / ProGuard configuration (release builds)
# ============================================================================
# Every rule below exists because a concrete mechanism in this app depends on a
# name that R8 would otherwise rename or remove. Nothing here disables shrinking
# globally. Keep this file minimal: a broad `-keep class dev.notune.transcribe.**`
# would defeat the whole point of enabling R8.

# ----------------------------------------------------------------------------
# 1. JNI entry points
# ----------------------------------------------------------------------------
# Native methods are looked up by exact symbol name
# (Java_dev_notune_transcribe_<Class>_<method>) by both ART and our Rust
# `#[no_mangle]` exports. Renaming them silently breaks the JNI handshake.
-keepclasseswithmembernames,allowoptimization,includedescriptorclasses class * {
    native <methods>;
}

# ----------------------------------------------------------------------------
# 2. Java callbacks invoked from Rust by name
# ----------------------------------------------------------------------------
# src/jni_util.rs, src/recog_service.rs and src/main_activity.rs call
# `env.call_method(obj, "<name>", "<signature>", ..)`. The lookup is by string at
# call time, so the *names* (not the classes) must survive obfuscation. These are
# the complete callback surfaces; adding a new JNI callback means adding it here.
-keepclassmembers class dev.notune.transcribe.** {
    public void onStatusUpdate(java.lang.String);
    public void onStatusUpdate(java.lang.String, int);
    public void onTextTranscribed(java.lang.String, int);
    public void onPartialText(java.lang.String, int);
    public void onAudioLevel(float);
    public void onAudioLevel(float, int);
    public void onAutoStop();
    public void onAutoStop(int);
    public void onSubtitleText(java.lang.String, boolean);
    public void onBenchmarkResult(float, float, java.lang.String);
    public void onRmsChanged(float, int);
    public void onReadyForSpeech(int);
    public void onBeginningOfSpeech(int);
    public void onEndOfSpeech(int);
    public void onResults(java.lang.String, int);
    public void onError(int, int);
}

# ----------------------------------------------------------------------------
# 3. Android components referenced by name outside the code
# ----------------------------------------------------------------------------
# AGP already keeps manifest-declared Activities/Services/Receivers and their
# constructors. These two are referenced by name from *resource* XML rather than
# the manifest, which R8 does not inspect:
#   - res/xml/method.xml        -> android:settingsActivity="…MainActivity"
#   - res/xml/recognition_service.xml and accessibility_service_config.xml
#     reference string resources only, so no class rule is needed for them.
-keep class dev.notune.transcribe.MainActivity { <init>(...); }

# IME and AccessibilityService subclasses are instantiated by the framework from
# the manifest and receive additional framework-only entry points; keep their
# lifecycle methods intact so the framework dispatch keeps working.
-keepclassmembers class * extends android.inputmethodservice.InputMethodService {
    public void on*(...);
}
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    public void on*(...);
}

# ----------------------------------------------------------------------------
# 4. Third-party libraries with a reflection surface
# ----------------------------------------------------------------------------
# ML Kit's on-device translation loads its native language packs reflectively.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.internal.mlkit_translate** { *; }
-dontwarn com.google.mlkit.**

# androidx.security-crypto / Tink resolve their primitives reflectively.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# OkHttp: consumer rules ship with the AAR, but the optional platform add-ons it
# probes at runtime are absent in this project.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ----------------------------------------------------------------------------
# 5. Debug/quality of life
# ----------------------------------------------------------------------------
# Keep source file names and line numbers so release crash reports stay usable
# without exposing the full source. Adjust via the mapping file upload to Play.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
