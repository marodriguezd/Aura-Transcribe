# Aura Transcribe

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--37)-blue.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](#requisiti-e-compatibilità)
[![License: GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue.svg)](LICENSE)

[English](README.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · Italiano · [中文](README_zh.md)

Il codice sorgente dell’applicazione è distribuito con GPL-3.0-only. I componenti di terze parti e il modello vocale incluso mantengono le proprie condizioni; consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) e [MODEL_LICENSE.md](MODEL_LICENSE.md).

Aura Transcribe è un'applicazione speech-to-text on-device e un servizio di input vocale per Android. Basata su Rust, ottimizzazioni SIMD ARM NEON e kernel di inferenza GGML, esegue il riconoscimento vocale localmente sulla CPU del dispositivo senza richiedere una connessione di rete attiva.

L'applicazione opera su più punti di integrazione Android: dettatura vocale nelle tastiere standard, un editor del metodo di input (IME) autonomo, un overlay flottante di dettatura, sottotitoli live dell'audio di sistema e trascrizione offline di file audio.

## Screenshot

| Home | Registrazione | Sottotitoli live |
| --- | --- | --- |
| ![Schermata home](.screenshots/screenshot_home.png) | ![Schermata registrazione](.screenshots/screenshot_recording.png) | ![Sottotitoli live](.screenshots/screenshot_subtitles.png) |

---

## Funzionalità principali

- **Riconoscimento vocale on-device:** la trascrizione gira al 100% in locale con un modello quantizzato [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) (Q5_K_M GGUF). Le registrazioni audio non lasciano mai il tuo dispositivo.
- **Dettatura vocale di sistema:** risponde a `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` di Android per mostrare un pannello di dettatura inferiore nelle tastiere compatibili (come Microsoft SwiftKey) e nei campi di ricerca vocale del browser.
- **Tastiera vocale dedicata (IME):** include una tastiera vocale autonoma per dettare con un tocco in qualsiasi app tramite il cambio standard del metodo di input.
- **Servizio di riconoscimento vocale:** implementa l'API di sistema `SpeechRecognizer` per integrazioni di tastiere e app terze.
- **Overlay flottante di dettatura:** bolla microfono trascinabile (`SYSTEM_ALERT_WINDOW`) per dettare da qualsiasi schermata. Se attivato, un servizio di accessibilità opzionale (`FloatingDictationAccessibilityService`) inserisce il testo trascritto direttamente nel campo attivo dell'app in uso. Senza accesso di accessibilità, il testo è copiato negli appunti.
- **Sottotitoli live in tempo reale:** sottotitola gli stream audio interni del dispositivo (podcast, multimedia, videochiamate) in locale via MediaProjection, con traduzione opzionale on-device dei sottotitoli finali tramite Google ML Kit.
- **Trascrizione file audio:** trascrive file audio preregistrati direttamente sul dispositivo.
- **Routing audio multi-dispositivo:** rileva e instrada l'audio da microfoni interni, interfacce USB e headset Bluetooth (SCO e BLE Audio) dinamicamente, con commutazione automatica e override manuali.
- **Copertura lingue:**
  - **Interfaccia utente:** disponibile in 8 lingue (inglese base, tedesco, spagnolo, francese, italiano, portoghese, russo e cinese semplificato).
  - **Riconoscimento vocale:** il modello Nemotron integrato supporta 40 locale di lingua nativamente con auto-rilevamento in streaming; 33 locale regionali sono direttamente selezionabili. Modelli GGUF importati dall'utente (es. Whisper) possono supportare fino a 99 lingue.
- **Post-elaborazione testo opzionale:** formattazione e pulizia configurabili con provider esterni compatibili API OpenAI (OpenAI, Groq, Cerebras, OpenRouter, Mistral, Together, Ollama) o il preset intatto *Verbatim*.

---

## Privacy e comportamento di elaborazione

- **Il riconoscimento vocale è 100% locale:** tutta l'inferenza acustica, l'elaborazione audio e la trascrizione sono eseguite interamente sul tuo dispositivo. Microfono e file audio non sono mai caricati su server remoti.
- **Post-elaborazione remota opzionale:** la post-elaborazione IA è **disattivata di default**. Se attivi un provider esterno (come Groq o OpenAI), solo il **testo** riconosciuto (mai l'audio) è trasmesso al tuo endpoint configurato (HTTPS per provider remoti; HTTP in chiaro solo verso il loopback localhost/127.0.0.1 per un server Ollama locale). Le API key sono salvate localmente nello storage privato dell'app.
- **Modalità Verbatim e offline:** con la post-elaborazione spenta o su *Verbatim*, nessun testo esce in rete.
- **Telemetria e log in release:** nelle build release, la registrazione del testo trascritto e dei dettagli sensibili di errore è rimossa.

---

## Requisiti e compatibilità

### Dispositivi Android supportati
- **Sistema operativo:** Android 8.0 (API 26) fino ad Android 17 (API 37).
- **ABI target:** solo `arm64-v8a`, allineato a pagine di memoria da 16 KB.
- **Processore:** CPU ARMv8.2-A con estensioni vettoriali `dotprod` e `fp16`. Non supportato su ARM a 32 bit né x86/x86_64.

### Toolchain di build
- **JDK:** 17 (LTS)
- **Android SDK:** piattaforma `android-37` (Android 17)
- **Android NDK:** `28.2.13676358`
- **Rust Toolchain:** Rust `1.78.0+` (edizione 2021) con target `aarch64-linux-android`
- **cargo-ndk:** ultima release (`cargo install cargo-ndk`)
- **CMake e Ninja:** richiesti per le dipendenze native

La cross-compilazione funziona su host `linux-x86_64`, `linux-aarch64`, `darwin-x86_64`, `darwin-arm64` (Apple Silicon) e Windows.

---

## Utilizzo base

### 1. Dettatura vocale popup
1. Apri Aura Transcribe e concedi il permesso microfono.
2. In qualsiasi app, tocca il tasto microfono di una tastiera compatibile o un campo di ricerca vocale.
3. Il dialogo inferiore trascrive in tempo reale e inserisce il testo finale al completamento.

### 2. Tastiera vocale dedicata (IME)
1. Attiva Aura Transcribe in **Impostazioni Android → Sistema → Lingue e input → Tastiere su schermo**.
2. Passa ad Aura Transcribe con il tasto globo/cambio della tua tastiera attiva.
3. Tocca la superficie di registrazione per parlare; il testo è scritto direttamente nel campo attivo.

### 3. Overlay flottante di dettatura
1. Avvia l'overlay dalla schermata home e concedi il permesso di overlay (`SYSTEM_ALERT_WINDOW`).
2. Tocca la bolla flottante per avviare e fermare la dettatura.
3. Per l'inserimento diretto negli input delle altre app, attiva il servizio di accessibilità opzionale in **Impostazioni Android → Accessibilità**. L'app mostra una spiegazione prima di portarti alle impostazioni di sistema. Se disattivato, il testo è copiato negli appunti.

### 4. Sottotitoli live
1. Tocca **Avvia sottotitoli live** nella schermata home.
2. Approva il dialogo standard MediaProjection (*"Avviare registrazione o trasmissione?"*) per sottotitolare l'audio interno.

---

## Interoperabilità tastiere

| Tastiera | Modalità di compatibilità | Note |
| :--- | :--- | :--- |
| **Microsoft SwiftKey** | Speech Intent Popup | Apre il pannello inferiore direttamente. *(Disattiva "Digitazione vocale multimodale" nelle impostazioni SwiftKey)* |
| **AnySoftKeyboard** | Speech Intent Popup | Il tasto microfono apre il pannello inferiore. |
| **HeliBoard / FlorisBoard** | IME Switcher | Il tasto microfono passa direttamente alla tastiera vocale Aura Transcribe. |
| **FUTO Keyboard** | IME Switcher | Passa ad Aura Transcribe con il tasto globo. |
| **Fossify / OpenBoard** | IME Switcher | Passa alla tastiera vocale Aura Transcribe. |
| **Gboard** | System IME Switcher | Il pulsante microfono di Gboard è legato a Google; passa ad Aura Transcribe con il selettore di sistema. |

---

## Compilare dai sorgenti

### 1. Clonare il repository
```bash
git clone https://github.com/marodriguezd/Aura-Transcribe.git
cd Aura-Transcribe
```

### 2. Scaricare e verificare i modelli inclusi
Il modello Nemotron GGUF incluso è verificato via SHA-256 prima del packaging:
```bash
./gradlew downloadModels
./gradlew checkModels
```

### 3. Compilare le librerie native Rust
Compila la `cdylib` nativa e i binding JNI per `arm64-v8a`:
```bash
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release
```
*(Nota: `./gradlew assembleDebug` lo invoca automaticamente se manca la libreria `.so`.)*

### 4. Assemblare l'APK di debug
```bash
./gradlew assembleDebug
```
L'APK è generato in `app/build/outputs/apk/debug/app-debug.apk`.

### 5. Eseguire i gate di verifica
```bash
./gradlew :app:testDebugUnitTest        # Test unitari JVM (non compila la lib nativa)
./gradlew :app:lintDebug                # Controlli Android Lint
./gradlew :app:checkModels              # SHA-256 del modello incluso
./gradlew :app:checkNativeAlignment     # Allineamento pagine 16 KB di ogni .so
./gradlew :app:verifyPackagingDecision  # Decisione APK/AAB + gate del modello, su archivi sintetici
./gradlew :benchmark:assembleBenchmark  # Compila il modulo Macrobenchmark (senza dispositivo)

python3 scripts/check_translations.py          # Parità traduzioni sui 8 locale
python3 scripts/test_verify_release_artifact.py # Autotest del verificatore dell'artefatto release
python3 scripts/bench_performance.py           # Micro-benchmark di algoritmi di riferimento
cargo fmt --all -- --check                     # Formattazione Rust
```

`verifyPackagingDecision` e `test_verify_release_artifact.py` sono i due gate
che proteggono la consegna del modello incluso: girano senza scaricare il modello da
560 MB, perché esercitano la logica di decisione e il verificatore contro archivi
sintetici. `bench_performance.py` misura sostituti Python puri dei percorsi caldi
audio/correttore e **asserisce** che ogni percorso veloce coincida con il suo
riferimento ingenuo — è un gate di correttezza, non una misura di performance
on-device.

### 6. Compilare gli artefatti release
Compila gli artefatti release in **invocazioni separate** per un packaging corretto:
```bash
# APK release standalone (modello incluso nell'archivio base)
./gradlew assembleRelease

# App Bundle release (modello via asset pack di installazione)
./gradlew bundleRelease
```

> [!WARNING]
> Senza un `release.keystore` autorizzato (più `STORE_PASS`, `KEY_ALIAS`,
> `KEY_PASS`), `assembleRelease` riesce ma produce un
> `app-release-unsigned.apk` **non firmato**. È una compilazione dai sorgenti,
> non una release distribuibile: i buildserver stile F-Droid compilano dai sorgenti
> e firmano con la propria chiave. Se c'è un keystore ma manca una delle tre
> credenziali, la build fallisce subito invece di usare una chiave debug o default.

---

## Integrazione continua e pubblicazione release

| Workflow | Trigger | Produce | Pubblica |
|---|---|---|---|
| [`debug_telegram.yml`](.github/workflows/debug_telegram.yml) | Push su `main`/`feat/**`, ogni pull request, manuale | APK di debug firmato con la chiave debug del repo | Un artefatto a ogni run; l'APK è inviato al bot Telegram solo su push e run manuali — le PR ricevono i gate e l'artefatto, mai notifica |
| [`android_release.yml`](.github/workflows/android_release.yml) | Tag `v*`, manuale | **APK e AAB** release firmati | Una GitHub Release con entrambi i file, e solo dopo il passaggio di tutti i gate |

### Perché APK e AAB sono compilati separatamente

Un APK e un AAB richiedono layout di asset distinti per la stessa variante release. AGP
ignora gli asset pack nel packaging di un APK, quindi l'APK deve portare il GGUF nel
modulo base; l'AAB deve portarlo solo nell'asset pack di installazione `:model_assets`,
altrimenti Play riceverebbe ~560 MB due volte. `app/build.gradle.kts` aggiunge
`model_assets/src/main/assets/` al source set release solo sul percorso APK. Un'invocazione
Gradle che chieda entrambi gli artefatti insieme è rifiutata in configurazione con un messaggio
che nomina i task colpevoli.

### Check che la CI esige automaticamente

A ogni push su `main`/`feat/**` e a ogni pull request il workflow di debug
esegue `cargo fmt --all -- --check`, `scripts/check_translations.py`,
`scripts/test_send_telegram.py`, `scripts/test_verify_release_artifact.py`,
`scripts/bench_performance.py`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
`:app:lintDebug`, `:app:checkModels`, `:app:checkNativeAlignment`,
`:app:verifyPackagingDecision` e `:benchmark:assembleBenchmark`. Ogni step che
usa `tee` fissa anche `-o pipefail`, così un filtro non può trasformare un gate
rosso in verde.

Il workflow di release esegue prima gli stessi gate di test, traduzione,
formattazione e decisione di packaging, e inoltre:

- rifiuta di girare se manca uno dei quattro segreti di firma, prima della build costosa;
- rifiuta di compilare se la scappatoia di release senza modello appare in un workflow o in una variabile `ORG_GRADLE_PROJECT_*`;
- esegue `:app:lintDebug` e `:app:checkNativeAlignment` prima del packaging, per fallire in fretta su dipendenza disallineata;
- verifica l'APK con `zipalign -c -P 16 -v 4` (il requisito 16 KB di Android 17) e `apksigner verify --print-certs`, e fallisce se lo SHA-256 del firmatario coincide con la chiave debug del repo;
- verifica l'AAB con `jarsigner -verify -certs` e `keytool -printcert -jarfile` (l'output è ispezionato, perché `jarsigner -verify` esce `0` per un bundle non firmato) con lo stesso rifiuto della chiave debug;
- ri-verifica il modello in ogni artefatto con `scripts/verify_release_artifact.py`, che confronta i byte pacchettizzati con l'asset sorgente per hash di contenuto e rifiuta un modello mancante, duplicato, mal posizionato, troncato o corrotto.

La pubblicazione (`Upload`/`Create Release`) avviene solo dopo tutto quanto sopra, quindi
un fallimento di firma o del modello non può pubblicare un artefatto.

### Check che non sono stati eseguiti

Test di strumentazione, misurazioni Macrobenchmark, generazione Baseline Profile e
validazione su tablet/pieghevoli richiedono tutti un dispositivo o un emulatore. Nessun
dispositivo arm64 né emulatore è disponibile in questo ambiente, quindi nessuno è stato
eseguito; che `:benchmark:assembleBenchmark` **compili** non è evidenza di performance misurata.
Nessun timing Macrobenchmark né Baseline Profile è pubblicato qui, e nessuno deve
essere presunto.

Il percorso di firma del workflow di release non è stato esercitato end to end con
credenziali reali di produzione in questo ambiente (nessun `release.keystore`,
`KEYSTORE_BASE64`, `STORE_PASS`, `KEY_ALIAS` né `KEY_PASS` disponibili in locale).
Ciò che è verificato in locale è il comportamento di fallimento: senza credenziali l'output
è non firmato e non pubblicabile, e un artefatto firmato con la chiave debug del repo
è rifiutato.

---

## Note sul packaging del modello

L'APK release e l'App Bundle (AAB) usano strategie distinte di consegna del modello:
- L'**APK release** include il file modello nel modulo base (`assets/builtin-model/`).
- L'**AAB release** lo consegna via asset pack di installazione `:model_assets`.

Per evitare errori di packaging, la configurazione rifiuta deliberatamente di eseguire entrambi i task in un unico comando (es. `./gradlew assembleRelease bundleRelease`). Compila sempre ogni artefatto nel proprio comando.

Entrambi gli artefatti sono inoltre verificati aprendoli dopo il packaging:
`verifyReleaseApkModel` (dopo `packageRelease`) e `verifyReleaseBundleModel`
(dopo `signReleaseBundle`) esigono il GGUF presente **esattamente una volta** — il
layout duplicato bundle→APK è rilevato perché l'AAB è matchato per suffisso
`assets/builtin-model/…`, quindi una copia sotto `base/` più una nell'asset pack
conta come duplicato. `scripts/verify_release_artifact.py` esegue lo stesso check
indipendentemente su un artefatto già compilato, confrontando gli hash di
contenuto con l'asset sorgente.

> [!CAUTION]
> L'unico modo per produrre un APK release senza modello è passare
> `-Pauratranscribe.allowModelLessReleaseApk=true`, che avverte che il risultato non è
> distribuibile. Esiste per esperimenti locali; il workflow di release lo rileva
> e rifiuta di compilare. Un AAB non ha tale opt-out.

---

## Limitazioni note

- **Post-elaborazione S1-mini on-device non disponibile:** sebbene esistano opzioni di configurazione, percorsi di download e template di prompt per S1-mini, la generazione di testo on-device non è implementata in questa build. Il backend di inferenza attuale (`transcribe-cpp`) è specializzato nel riconoscimento vocale e non supporta la generazione causale di linguaggio. Il provider locale S1-mini è disattivato nelle impostazioni.
- **Restrizione di architettura:** l'app gira solo su ARM a 64 bit (`arm64-v8a`) con supporto dotprod/fp16 ARMv8.2. Non gira su emulatori o dispositivi x86/x86_64.
- **Inserimento via accessibilità:** l'inserimento diretto via overlay dipende dal framework di accessibilità di Android. Campi non standard o web che non espongono nodi editabili possono richiedere l'incollaggio manuale dagli appunti.

---

## Attribuzione

### Progetto upstream

Questo repository è un fork indipendente di [Offline Voice Input](https://github.com/notune/android_transcribe_app) (`notune/android_transcribe_app`), creato originariamente da Noah Mühl e basato sulla release upstream [v0.1.18](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28).

Le aggiunte e i cambiamenti architetturali successivi di questo fork — inclusi routing audio multi-dispositivo, overlay flottanti di dettatura, post-elaborazione remota opzionale e integrazioni multisuperficie — sono stati sviluppati indipendentemente e non sono associati all'autore originale.

### Crediti di terze parti

- **Architettura del modello vocale:** [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) di NVIDIA, quantizzato in GGUF (Q5_K_M) da [handy-computer](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf) (licenza del modello: OpenMDW-1.1; consulta [MODEL_LICENSE.md](MODEL_LICENSE.md)).
- **Kernel di inferenza:** [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) e [ggml](https://github.com/ggerganov/ggml) di CJ Pais e Georgi Gerganov.
- **Cattura audio:** [cpal](https://github.com/RustAudio/cpal) (Cross-Platform Audio Library).
