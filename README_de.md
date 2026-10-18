# Aura Transcribe

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--37)-blue.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](#anforderungen--kompatibilität)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

[English](README.md) · [Español](README_es.md) · Deutsch · [Français](README_fr.md) · [Italiano](README_it.md) · [中文](README_zh.md)

Aura Transcribe ist eine On-Device-Speech-to-Text-Anwendung und ein Spracheingabedienst für Android. Angetrieben von Rust, ARM-NEON-SIMD-Optimierungen und GGML-Inferenzkernen führt es Spracherkennung lokal auf der Geräte-CPU durch — ohne aktive Netzwerkverbindung.

Die Anwendung arbeitet über mehrere Android-Integrationspunkte: Standard-Tastatur-Spracheingabe, ein eigenständiger Eingabemethoden-Editor (IME), ein schwebendes Diktier-Overlay, Live-Untertitel für Systemaudio und Offline-Transkription von Audiodateien.

## Screenshots

| Start | Aufnahme | Live-Untertitel |
| --- | --- | --- |
| ![Startbildschirm](.screenshots/screenshot_home.png) | ![Aufnahmebildschirm](.screenshots/screenshot_recording.png) | ![Live-Untertitel](.screenshots/screenshot_subtitles.png) |

---

## Hauptfunktionen

- **On-Device-Spracherkennung:** Transkription läuft zu 100 % lokal mit einem quantisierten [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b)-Modell (Q5_K_M GGUF). Audioaufnahmen verlassen niemals dein Gerät.
- **Systemweite Spracheingabe:** Reagiert auf Androids `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, um ein Bottom-Sheet-Diktierpanel in kompatiblen Tastaturen (z. B. Microsoft SwiftKey) und Browser-Sprachfeldern bereitzustellen.
- **Dedizierter Eingabemethoden-Editor (IME):** Enthält eine eigenständige Sprachtastatur für Ein-Tipp-Diktat über Apps hinweg mittels Standard-Eingabemethodenwechsel.
- **Spracherkennungsdienst:** Implementiert die System-API `SpeechRecognizer` für Tastatur- und App-Integrationen Dritter.
- **Schwebendes Diktier-Overlay:** Ziehbares Mikrofon-Overlay (`SYSTEM_ALERT_WINDOW`) zum Diktieren von jedem Bildschirm aus. Wenn aktiviert, fügt ein optionaler Accessibility-Dienst (`FloatingDictationAccessibilityService`) transkribierten Text direkt in das fokussierte Feld der aktiven Anwendung ein. Ohne Accessibility-Zugriff wird der Text in die Zwischenablage kopiert.
- **Echtzeit-Live-Untertitel:** Untertitelt interne Geräte-Audiostreams (Podcasts, Medienwiedergabe, Videoanrufe) lokal via MediaProjection, mit optionaler On-Device-Übersetzung finalisierter Untertitel per Google ML Kit.
- **Audiodatei-Transkription:** Transkribiert voraufgenommene Audiodateien direkt auf dem Gerät.
- **Multi-Geräte-Audiorouting:** Erkennt und routet Audio von internen Mikrofonen, USB-Audiointerfaces und Bluetooth-Headsets (SCO und BLE Audio) dynamisch, mit automatischer Umschaltung und manuellen Overrides.
- **Sprachabdeckung:**
  - **Benutzeroberfläche:** In 8 Sprachen verfügbar (Englisch als Basis, Deutsch, Spanisch, Französisch, Italienisch, Portugiesisch, Russisch und vereinfachtes Chinesisch).
  - **Spracherkennung:** Das integrierte Nemotron-Modell unterstützt 40 Sprach-Locales nativ mit Streaming-Autoerkennung; 33 regionale Locales sind direkt in der Sprachauswahl wählbar. Zusätzlich vom Anwender importierte GGUF-Modelle (z. B. Whisper) können bis zu 99 Sprachen unterstützen.
- **Optionale Textnachbearbeitung:** Konfigurierbare Formatierung und Bereinigung über externe OpenAI-kompatible API-Anbieter (OpenAI, Groq, Cerebras, OpenRouter, Mistral, Together, Ollama) oder ein unberührtes *Verbatim*-Preset.

---

## Datenschutz und Verarbeitungsverhalten

- **Spracherkennung ist zu 100 % lokal:** Sämtliche akustische Modellinferenz, Audioverarbeitung und Transkription laufen vollständig auf deinem Gerät. Mikrofoneingaben und Audiodateien werden niemals auf entfernte Server hochgeladen.
- **Optionale entfernte Textnachbearbeitung:** KI-Nachbearbeitung ist standardmäßig **deaktiviert**. Wenn du einen externen API-Anbieter aktivierst (z. B. Groq oder OpenAI), wird nur der erkannte **Text** (niemals Audio) an deinen konfigurierten Endpunkt übertragen (HTTPS für entfernte Anbieter; Klartext-HTTP nur zu Loopback localhost/127.0.0.1 für einen lokalen Ollama-Server). API-Schlüssel werden lokal im privaten Speicher der Anwendung abgelegt.
- **Verbatim- und Offline-Modi:** Bei ausgeschalteter Nachbearbeitung oder auf *Verbatim* verlässt kein Text das Netzwerk.
- **Release-Telemetrie und Logging:** In Release-Builds ist das Logging von rohem Transkriptionstext und sensiblen Fehlerdetails entfernt.

---

## Anforderungen & Kompatibilität

### Unterstützte Android-Geräte
- **Betriebssystem:** Android 8.0 (API 26) bis Android 17 (API 37).
- **Ziel-ABI:** nur `arm64-v8a`, ausgerichtet auf 16-KB-Speicherseiten.
- **Prozessor:** ARMv8.2-A-CPU mit `dotprod`- und `fp16`-Vektorerweiterungen. 32-Bit-ARM-Prozessoren oder x86/x86_64-Architekturen werden nicht unterstützt.

### Build-Toolchain
- **JDK:** 17 (LTS)
- **Android SDK:** Plattform `android-37` (Android 17)
- **Android NDK:** `28.2.13676358`
- **Rust-Toolchain:** Rust `1.78.0+` (Edition 2021) mit Target `aarch64-linux-android`
- **cargo-ndk:** Aktuelles Release (`cargo install cargo-ndk`)
- **CMake & Ninja:** Für native Build-Abhängigkeiten erforderlich

Cross-Kompilierung wird auf `linux-x86_64`-, `linux-aarch64`-, `darwin-x86_64`-, `darwin-arm64`- (Apple Silicon) und Windows-Hosts unterstützt.

---

## Grundlegende Verwendung

### 1. Spracheingabe-Popup
1. Aura Transcribe öffnen und die Mikrofonberechtigung erteilen.
2. In einer beliebigen Anwendung die Mikrofontaste einer kompatiblen Tastatur oder ein Spracheingabefeld antippen.
3. Der Bottom-Sheet-Dialog transkribiert Sprache in Echtzeit und fügt den finalen Text nach Abschluss ein.

### 2. Dedizierte Sprachtastatur (IME)
1. Aura Transcribe unter **Android-Einstellungen → System → Sprachen & Eingabe → Bildschirmtastaturen** aktivieren.
2. Über die Globus-/Umschalttaste der aktiven Tastatur zu Aura Transcribe wechseln.
3. Die Aufnahmefläche zum Sprechen antippen; transkribierter Text wird direkt in das aktive Textfeld übernommen.

### 3. Schwebendes Diktier-Overlay
1. Das Overlay vom Startbildschirm aus starten und die System-Overlay-Berechtigung (`SYSTEM_ALERT_WINDOW`) erteilen.
2. Die schwebende Mikrofonblase zum Starten und Stoppen des Diktats antippen.
3. Für direktes Einfügen in aktive Eingaben anderer Apps den optionalen Accessibility-Dienst unter **Android-Einstellungen → Bedienungshilfen** einschalten. Die Anwendung zeigt vorab eine erläuternde Offenlegung, bevor sie zu den Systemeinstellungen navigiert. Falls deaktiviert, wird transkribierter Text stattdessen in die Zwischenablage kopiert.

### 4. Live-Untertitel
1. Auf dem Startbildschirm **Live-Untertitel starten** antippen.
2. Die standardmäßige Android-System-MediaProjection-Abfrage (*„Aufnahme oder Übertragung starten?"*) bestätigen, um mit der Untertitelung internen Geräteaudios zu beginnen.

---

## Tastatur-Interoperabilität

| Tastaturanwendung | Kompatibilitätsmodus | Hinweise |
| :--- | :--- | :--- |
| **Microsoft SwiftKey** | Speech-Intent-Popup | Öffnet das Bottom-Sheet-Panel direkt. *(„Multimodale Spracheingabe" in den SwiftKey-Rich-Input-Einstellungen deaktivieren)* |
| **AnySoftKeyboard** | Speech-Intent-Popup | Mikrofontaste startet das Bottom-Sheet-Panel. |
| **HeliBoard / FlorisBoard** | IME-Umschalter | Mikrofontaste wechselt direkt zur Aura-Transcribe-Spracheingabetastatur. |
| **FUTO Keyboard** | IME-Umschalter | Über die Globus-/Eingabe-Umschalttaste zu Aura Transcribe wechseln. |
| **Fossify / OpenBoard** | IME-Umschalter | Wechselt zur Aura-Transcribe-Spracheingabetastatur. |
| **Gboard** | System-IME-Umschalter | Gboards Mikrofontaste ist an Google-Sprachdienste gebunden; über den System-Tastaturumschalter zu Aura Transcribe wechseln. |

---

## Aus dem Quellcode bauen

### 1. Repository klonen
```bash
git clone https://github.com/marodriguezd/Aura-Transcribe.git
cd Aura-Transcribe
```

### 2. Integrierte Modelle herunterladen und verifizieren
Das gebündelte Nemotron-GGUF-Modell wird vor dem Paketieren per SHA-256 verifiziert:
```bash
./gradlew downloadModels
./gradlew checkModels
```

### 3. Native Rust-Bibliotheken bauen
Die native `cdylib` und JNI-Bindings für `arm64-v8a` kompilieren:
```bash
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release
```
*(Hinweis: `./gradlew assembleDebug` ruft dies automatisch auf, falls die `.so`-Bibliothek fehlt.)*

### 4. Debug-APK assemblieren
```bash
./gradlew assembleDebug
```
Die Ausgabe-APK wird unter `app/build/outputs/apk/debug/app-debug.apk` erzeugt.

### 5. Verifikations-Gates ausführen
```bash
./gradlew :app:testDebugUnitTest        # JVM-Unit-Tests (baut die native Bibliothek nicht)
./gradlew :app:lintDebug                # Android-Lint-Checks
./gradlew :app:checkModels              # SHA-256 des gebündelten Modells
./gradlew :app:checkNativeAlignment     # 16-KB-Seitenausrichtung jeder ausgelieferten .so
./gradlew :app:verifyPackagingDecision  # APK/AAB-Entscheidung + Modell-Gates, auf synthetischen Archiven
./gradlew :benchmark:assembleBenchmark  # Kompiliert das Macrobenchmark-Modul (kein Gerät nötig)

python3 scripts/check_translations.py          # Übersetzungsparität über die 8 Locales
python3 scripts/test_verify_release_artifact.py # Selbsttest des Release-Artefakt-Verifizierers
python3 scripts/bench_performance.py           # Referenzalgorithmus-Mikrobenchmarks
cargo fmt --all -- --check                     # Rust-Formatierung
```

`verifyPackagingDecision` und `test_verify_release_artifact.py` sind die beiden Gates,
die die Auslieferung des gebündelten Modells schützen: Sie laufen ohne das 560-MB-Modell
herunterzuladen, weil sie Entscheidungslogik und Verifizierer gegen synthetische Archive
ausüben. `bench_performance.py` misst reine Python-Stellvertreter der Audio-/Korrektor-Hotpaths
und **assertet**, dass jeder schnelle Pfad weiterhin mit seiner naiven Referenz übereinstimmt —
es ist ein Korrektheits-Gate, keine Messung der On-Device-Leistung.

### 6. Release-Artefakte bauen
Release-Artefakte in **getrennten Aufrufen** bauen, um korrekte Asset-Paketierung sicherzustellen:
```bash
# Eigenständige Release-APK bauen (Modell im Basisarchiv gebündelt)
./gradlew assembleRelease

# Release-App-Bundle bauen (Modell via Installations-Asset-Pack geliefert)
./gradlew bundleRelease
```

> [!WARNING]
> Ohne autorisierte `release.keystore` (plus `STORE_PASS`, `KEY_ALIAS`,
> `KEY_PASS`) gelingt `assembleRelease`, produziert aber eine **unsignierte**
> `app-release-unsigned.apk`. Das ist ein Quellcode-Build, kein verteilbares Release:
> Buildserver im F-Droid-Stil kompilieren aus dem Quellcode und signieren mit eigenem Schlüssel. Falls ein Keystore vorhanden ist, aber eine der drei Anmeldedaten fehlt, scheitert der Build sofort, statt auf einen Debug- oder Standardschlüssel zurückzufallen.

---

## Kontinuierliche Integration und Release-Veröffentlichung

| Workflow | Auslöser | Erzeugt | Veröffentlicht |
|---|---|---|---|
| [`debug_telegram.yml`](.github/workflows/debug_telegram.yml) | Push auf `main`/`feat/**`, jeder Pull Request, manuell | Debug-APK, mit dem Debug-Schlüssel des Repositorys signiert | Ein Workflow-Artefakt bei jedem Lauf; die APK wird nur bei Pushes und manuellen Läufen an den Telegram-Bot gesendet — Pull Requests erhalten die Gates und das Artefakt, niemals eine Benachrichtigung |
| [`android_release.yml`](.github/workflows/android_release.yml) | Tag `v*`, manueller Dispatch | Signierte Release-**APK und AAB** | Ein GitHub Release mit beiden Dateien, und nur nachdem jedes Gate bestanden ist |

### Warum APK und AAB getrennt gebaut werden

Eine APK und ein AAB benötigen unterschiedliche Asset-Layouts für dieselbe Release-Variante. AGP
ignoriert Asset-Packs beim Paketieren einer APK, daher muss die APK das GGUF im Basismodul tragen;
das AAB darf es nur im `:model_assets`-Installations-Asset-Pack tragen, sonst erhielte Play
etwa 560 MB doppelt. `app/build.gradle.kts` fügt `model_assets/src/main/assets/` nur im APK-Pfad
zum Release-Source-Set hinzu. Ein Gradle-Aufruf, der beide Artefakte auf einmal anfordert, wird
zur Konfigurationszeit mit einer Meldung abgewiesen, die die beanstandeten Tasks nennt.

### Checks, die CI automatisch erzwingt

Bei jedem Push auf `main`/`feat/**` und bei jedem Pull Request führt der Debug-Workflow
`cargo fmt --all -- --check`, `scripts/check_translations.py`,
`scripts/test_send_telegram.py`, `scripts/test_verify_release_artifact.py`,
`scripts/bench_performance.py`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
`:app:lintDebug`, `:app:checkModels`, `:app:checkNativeAlignment`,
`:app:verifyPackagingDecision` und `:benchmark:assembleBenchmark` aus. Jeder Schritt, der
durch `tee` pfeift, setzt außerdem `-o pipefail`, sodass ein Filter kein rotes Gate
grün melden kann.

Der Release-Workflow führt zuerst dieselben Test-, Übersetzungs-, Formatierungs- und
Verpackungsentscheidungs-Gates aus, dann zusätzlich:

- verweigert den Lauf, wenn eines der vier Signatur-Geheimnisse fehlt, vor dem teuren Build;
- verweigert den Build, wenn der modellose Release-Notausstieg in einer Workflow-Datei oder einer `ORG_GRADLE_PROJECT_*`-Umgebungsvariable auftaucht;
- führt `:app:lintDebug` und `:app:checkNativeAlignment` vor dem Paketieren aus, sodass eine fehlausgerichtete Abhängigkeit schnell scheitert;
- verifiziert die paketierte APK mit `zipalign -c -P 16 -v 4` (die 16-KB-Anforderung von Android 17) und `apksigner verify --print-certs`, und scheitert, wenn der Signierer-SHA-256 dem Repository-Debug-Schlüssel entspricht;
- verifiziert das paketierte AAB mit `jarsigner -verify -certs` und `keytool -printcert -jarfile` (die Ausgabe wird inspiziert, weil `jarsigner -verify` für ein unsigniertes Bundle mit `0` exitt) und wendet dieselbe Debug-Schlüssel-Ablehnung an;
- verifiziert das Modell in jedem gebauten Artefakt erneut mit `scripts/verify_release_artifact.py`, das die paketierten Bytes per Content-Hash gegen das verifizierte Quell-Asset vergleicht und ein fehlendes, doppeltes, falsch platziertes, abgeschnittenes oder korruptes Modell ablehnt.

Die Veröffentlichung (`Upload`/`Create Release`) geschieht erst nach allem Obigen, sodass
eine fehlgeschlagene Signierung oder Modellprüfung kein Artefakt veröffentlichen kann.

### Checks, die nicht gelaufen sind

Instrumentierungstests, Macrobenchmark-Messungen, Baseline-Profile-Generierung und
Laufzeitvalidierung auf Tablets/Foldables erfordern alle ein Gerät oder einen Emulator. Kein
arm64-Gerät oder Emulator ist in dieser Umgebung verfügbar, daher wurde keines davon ausgeführt;
dass `:benchmark:assembleBenchmark` **kompiliert**, ist kein Beleg gemessener Laufzeitleistung. Kein
Macrobenchmark-Timing oder Baseline-Profile wird hier veröffentlicht, und keines sollte angenommen werden.

Der Signierungspfad des Release-Workflows wurde in dieser Umgebung ebenfalls nicht Ende zu Ende
mit echten Produktions-Anmeldedaten ausgeübt (keine `release.keystore`,
`KEYSTORE_BASE64`, `STORE_PASS`, `KEY_ALIAS` oder `KEY_PASS` lokal verfügbar).
Was lokal verifiziert ist, ist das Fehlerverhalten: Ohne Anmeldedaten ist die Ausgabe
unsigniert und nicht veröffentlichbar, und ein mit dem Repository-Debug-Schlüssel signiertes Artefakt
wird abgelehnt.

---

## Hinweise zur Modellpaketierung

Die Release-APK und das Android App Bundle (AAB) nutzen unterschiedliche Modell-Auslieferungsstrategien:
- Die **Release-APK** bündelt die Modelldatei im Basismodul (`assets/builtin-model/`).
- Das **Release-AAB** liefert das Modell via `:model_assets`-Installations-Asset-Pack.

Um Paketierungsfehler zu verhindern, lehnt die Build-Konfiguration bewusst das Ausführen beider Tasks in einem einzigen Befehl ab (z. B. `./gradlew assembleRelease bundleRelease`). Jedes Artefakt immer in eigenem Befehl bauen.

Beide Artefakte werden zusätzlich nach dem Paketieren durch Öffnen verifiziert:
`verifyReleaseApkModel` (nach `packageRelease`) und `verifyReleaseBundleModel`
(nach `signReleaseBundle`) verlangen das GGUF **genau einmal** vorhanden — das
Bundle-zu-APK-Duplikatlayout wird erkannt, weil das AAB per Suffix
`assets/builtin-model/…` gematcht wird, sodass eine Kopie unter `base/` sowie eine im
Asset-Pack als Duplikat zählt. `scripts/verify_release_artifact.py` führt denselben Check
unabhängig auf einem bereits gebauten Artefakt durch und vergleicht Content-Hashes
gegen das Quell-Asset.

> [!CAUTION]
> Der einzige Weg, eine Release-APK ohne das Modell zu erzeugen, ist die Übergabe von
> `-Pauratranscribe.allowModelLessReleaseApk=true`, das ausgibt, dass das Ergebnis nicht
> verteilbar ist. Es existiert für lokale Experimente; der Release-Workflow grept
> danach und verweigert den Build. Ein AAB hat kein solches Opt-out.

---

## Bekannte Einschränkungen

- **On-Device-S1-mini-Nachbearbeitung ist nicht verfügbar:** Obwohl Konfigurationsoptionen, Modell-Downloadpfade und Prompt-Templates für S1-mini im Codebase existieren, ist Textgenerierung auf dem Gerät in diesem Build nicht implementiert. Das aktuelle Inferenz-Backend (`transcribe-cpp`) ist auf Spracherkennung spezialisiert und unterstützt keine kausale Sprachmodellgenerierung. Der lokale S1-mini-Anbieter ist in den Einstellungen deaktiviert.
- **Hardwarearchitektur-Einschränkung:** Die Anwendung läuft nur auf 64-Bit-ARM-Hardware (`arm64-v8a`) mit ARMv8.2-dotprod/fp16-Unterstützung. Sie läuft nicht auf x86- oder x86_64-Emulatoren oder Geräten.
- **Accessibility-Einfügeunterstützung:** Direktes Texteinfügen via schwebendem Overlay beruht auf Androids Accessibility-Framework. Nicht standardmäßige oder webbasierte Textfelder, die keine editierbaren Accessibility-Knoten exponieren, erfordern ggf. manuelles Einfügen aus der Zwischenablage.

---

## Zuschreibung

### Upstream-Projekt

Dieses Repository ist ein unabhängiger Fork von [Offline Voice Input](https://github.com/notune/android_transcribe_app) (`notune/android_transcribe_app`), ursprünglich erstellt von Noah Mühl und basierend auf dem Upstream-Release [v0.1.18](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28).

Nachfolgende Ergänzungen und Architekturänderungen dieses Forks — einschließlich Multi-Geräte-Audiorouting, schwebender Diktier-Overlays, optionaler entfernter Textnachbearbeitung und Multi-Surface-Integrationen — wurden unabhängig entwickelt und sind nicht mit dem ursprünglichen Upstream-Autor assoziiert.

### Drittanbieter-Credits

- **Sprachmodellarchitektur:** [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) von NVIDIA, quantisiert zu GGUF (Q5_K_M) von [handy-computer](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf) (lizenziert unter CC-BY 4.0).
- **Inferenzkerne:** [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) und [ggml](https://github.com/ggerganov/ggml) von CJ Pais und Georgi Gerganov.
- **Audioaufnahme:** [cpal](https://github.com/RustAudio/cpal) (Cross-Platform Audio Library).
