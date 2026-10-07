# AGENTS.md — Guía para asistentes de IA

> Archivo de instrucciones para agentes IA que trabajen en este repositorio. Define stack, comandos, convenciones y reglas críticas que **no** deben romperse. Equivalente al `AGENTS.md` estándar (mismo rol que `CONTRIBUTING.md` tiene para humanos, pero dirigido a IAs).

> **Repo:** `android_transcribe_app` (fork de `notune/android_transcribe_app`)
> **Tipo:** App Android de transcripción de voz *offline* con opción de post-procesado con IA.
> **Versión declarada actualmente en Gradle:** 0.2.2 (`versionCode 41`, ver `app/build.gradle.kts`). La versión realmente publicada debe comprobarse en tags/releases; no asumir que coincide con este fichero.
>
> **Idioma (decisión 2026-08-04):** los ficheros agénticos nuevos/actualizados
> (`.ai_context/`, `agent_prompt.md`) se escriben en **inglés**; este `AGENTS.md`
> conserva su idioma histórico. Cualquier sección nueva que añadas aquí puede
> ir en inglés.

> **Scope:** este archivo documenta el **contrato de implementación** (versiones
> pinneadas, handshake JNI, reglas críticas, plantillas de nuevos componentes).
> La cara humana del proyecto — instalación paso a paso, prerequisitos, lista de
> features en prosa, capturas, agradecimientos, licencia — vive en [`README.md`](README.md).
> Si dudas de dónde poner algo: si cambia **la cara** → README; si cambia
> **cómo está hecho** → AGENTS.md.

---

## 1. Resumen del Proyecto

App Android que convierte voz en texto **100 % en local**. Se ofrece al sistema de tres formas distintas (para maximizar compatibilidad con teclados/apps):

| Superficie | Cómo se dispara | Ficheros clave |
|---|---|---|
| **Popup de voz** | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (teclados tipo SwiftKey, búsqueda por voz web) | `RecognizeActivity.java` + `src/recognize.rs` |
| **Servicio de reconocimiento** | `SpeechRecognizer` (Android) — otros keyboards/apps lo usan como STT | `VoiceRecognitionService.java` + `src/recog_service.rs` |
| **IME (teclado propio)** | `BIND_INPUT_METHOD` (HeliBoard/FlorisBoard/etc.) | `RustInputMethodService.java` + `src/ime.rs` |

Funciones adicionales: **subtítulos en vivo** sobre audio del sistema (`LiveSubtitleService.java` + `src/subtitle.rs`), **gestión de modelos GGUF** (`ModelsActivity.java` + `src/models.rs`), **overlay flotante** (`FloatingOverlayService.java`) y **transcripción de archivos** (`TranscribeFileActivity.java`).

Post-procesado IA (fork addition): opcional, *off-line-by-default*, refina texto con cualquier LLM compatible OpenAI (`PostProcessor.java`, settings en `PostProcessSettingsActivity.java`).

**Arquitectura general:** proceso Java principal + proceso aislado `:ime` (declarado en `AndroidManifest.xml` con `android:process=":ime"`). Toda la lógica pesada (ASR, captura audio, segmentación, JNI) está en **Rust** compilado como `cdylib` (`Cargo.toml`) y enlazado por JNI desde Java. Los procesos se sincronizan vía ficheros *marker* en `getFilesDir()` (sin `SharedPreferences`).

---

## 2. Stack Tecnológico

| Capa | Tecnología | Versión / Nota |
|---|---|---|
| Lenguaje nativo | **Rust** | edition `2021`, `crate-type = ["cdylib"]` |
| Backend ASR | [`transcribe-cpp`](https://github.com/handy-computer/transcribe.cpp) (ggml + whisper.cpp fork) | `0.1.3` |
| Audio captura | [`cpal`](https://github.com/RustAudio/cpal) | `0.15` |
| Canales Rust ⇄ Java | [`jni`](https://github.com/jni-rs/jni) | `0.21` |
| Concurrencia | `crossbeam-channel`, `once_cell`, `std::sync` (Arc/Mutex/atomic) | — |
| Logging nativo | `log` + `android_logger` | max level `Info` |
| Errores | `anyhow` (Rust), `try/catch` + `Throwable` (Java) | — |
| Lenguaje UI | **Java** (sin Kotlin) | Java 8 source/target |
| Android Gradle Plugin | `com.android.application` | **8.7.3** |
| Build tool | Gradle wrapper | ver `gradle/wrapper/gradle-wrapper.properties` |
| Toolchain humano | JDK 17 · Android NDK **28.0.13004108** (unificado en Gradle = CI = README) · Rust `aarch64-linux-android` · [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) | Host soportados: linux-x86_64, darwin-x86_64, darwin-arm64, windows (ver `ndkPrebuiltDir()` en `app/build.gradle.kts`); un host sin prebuilt NDK falla con mensaje claro |
| Target NDK ABIs | **`arm64-v8a` únicamente** (`abiFilters += "arm64-v8a"`) | excluidas x86 / armeabi-v7a; 16KB page size aligned |
| `compileSdk` / `targetSdk` / `minSdk` | Gradle efectivo: `35 / 35 / 26` | Compatible Android 8.0 hasta Android 17 (API 35+) |
| Material | Material Components for Android | `1.12.0` (Material 3 + Material You) |
| HTTP (post-procesado) | OkHttp | `4.12.0` |
| Almacenamiento clave API | marker file Base64 en `filesDir()` | sin dependencia externa |
| Alineación kotlin-stdlib | Forzadas a `1.8.22` (vacías) | ver bloque `constraints` en `app/build.gradle.kts` |

**Permisos críticos** (`AndroidManifest.xml`):
- `RECORD_AUDIO`, `INTERNET` (post-procesado), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`.

**i18n:** 7 locales paralelos (EN `values/`, ES `values-es/`, DE `values-de/`, FR `values-fr/`, IT `values-it/`, PT `values-pt/`, RU `values-ru/`) + `values-night/` (estilos dark).

---

## 3. Comandos y wiring

> Comandos user-facing y prerequisitos viven en [`README.md` §Building](README.md#building)
> y [`README.md` §Prerequisites](README.md#prerequisites). Esta sección sólo
> lista las **decisiones de wiring** que un agente debe respetar y los puntos
> donde divergen de la regla general.

### Wiring Gradle ↔ cargo-ndk (AGENT-ONLY)

- `cargoNdkBuild` está registrada como `preBuild` dependiente y **siempre re-corre**
  (`outputs.upToDateWhen { false }`): el incremental de Cargo es fiable, el
  matching inputs/outputs de Gradle no. Sin este override, Gradle salta rebuilds
  cuando sólo cambian fuentes Rust.
- `app/build.gradle.kts` fuerza `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` vía
  env en `cargoNdkBuild`. **No bajar** este flag: ggml cross-compila con kernels
  baseline y la inferencia cuantizada cae a ≥4× más lenta. `check_cpu_features`
  en `engine.rs` aborta al cargar si la CPU no tiene dotprod+fp16 (ARMv8.2 ~2018+).
- `libc++_shared.so` se copia tras cada build desde el NDK a
  `app/src/main/jniLibs/<abi>/` (Bionic no la trae integrada y `transcribe-cpp`
  la enlaza dinámicamente).
- **APK-only**: cuando `taskNames` contiene `"bundle"`, el bloque `if (!isBundle)`
  en `app/build.gradle.kts` **NO** añade `model_assets/src/main/assets/builtin-model/`
  a `assets.srcDirs` — añadirlo ahí causa duplicate-resource errors con el asset
  pack. Para bundle usa el asset pack `:model_assets` con
  `dynamicDelivery = "install-time"`.

### Build Pipeline & Continuous Integration (REGLA GRABADA A FUEGO)

- 🤖 **Compilaciones de Depuración (Debug):** Se realizan SIEMPRE a través de GitHub Actions (`Build Debug APK` workflow), el cual recompila y envía automáticamente el archivo APK de depuración al usuario a través del bot de Telegram.
- 🚀 **Compilaciones de Lanzamiento (Release):** Se realizan SIEMPRE a través de GitHub Actions (`Build Signed Release APK` workflow / `android_release.yml`) mediante tags `vX.Y.Z` o disparadores manuales.
- 🛠️ **Disparador Manual Obligatorio (`workflow_dispatch`):** Todos los workflows de release (`android_release.yml`) deben mantener habilitado `workflow_dispatch` en su bloque `on:` y `tag_name: v${{ env.VERSION_NAME }}` en el paso de publicación. Esto garantiza resiliencia si GitHub Actions sufre un outage o pierde un evento de tag.

### Regla de validación por entorno (2026-08-06, REGLA GRABADA A FUEGO)

Los agentes deben validar compilaciones según el host donde se ejecutan:

- 📱 **Host tipo dispositivo móvil / embebido (p. ej. un Android userspace ARM64 sin KVM — como el host de desarrollo actual): NUNCA ejecutar compilaciones pesadas locales.** `./gradlew assembleDebug`, `cargo build`, `cargo ndk ...`, `cargo check` con NDK, `assembleRelease`, etc. sobrecargan el sistema y pueden dejarlo inoperativo (verificado 2026-08-06). Toda validación de compilación se hace **de forma dinámica vía GitHub**: `git commit` + `git push` a `main` dispara el workflow debug (`debug_telegram.yml`), que corre todos los gates — `cargo fmt --check`, `check_translations.py`, `testDebugUnitTest`, `assembleDebug` (compila el Rust vía cargo-ndk), `lintDebug`, `checkModels` — y envía el APK a Telegram. El agente lee el resultado con `gh run list` / `gh run view` y **itera: arreglar → push → leer CI → repetir**, subiendo pruebas que pasen o no.
- 💻 **Equipo físico (portátil/ordenador del mantenedor):** se puede compilar localmente (`./gradlew assembleDebug`, `cargo ndk ...`, tests JVM, etc.) respetando la protección térmica (los tests JVM no disparan `cargoNdkBuild`) y los gates de la sección "Validación y estilo".
- ✅ **Gates ligeros permitidos en el host embebido:** lectura estática de código, `git diff`, `cargo fmt --check` (no compila), `python3 scripts/check_translations.py`, edición de strings XML. Nada que invoque Gradle/Cargo/CMake en modo build.
- 🛠️ `gh` está autenticado como el mantenedor en el host embebido; úsalo para inspeccionar runs del CI en vez de compilar localmente.

### Signing (AGENT-ONLY)

- Si `release.keystore` existe en la raíz **y** las 3 env vars (`KEY_ALIAS`,
  `KEY_PASS`, `STORE_PASS`) están exportadas, AGP firma el release con
  `signingConfigs.release`. Si el keystore existe pero falta alguna env var,
  **los builds release fallan con un error claro** (hardening 2026-08-06):
  nunca se firma con credenciales por defecto. Sin keystore = release sin
  firmar (debug-only).
- En CI la keystore es base64-decoded desde un repo secret; ver
  `.github/workflows/android_release.yml` §Decode Keystore.

### Validación y estilo (Ley de Arneses / Gauntlet de Uncle Bob)

- 🛡️ **La Regla del Guantelete (*The Gauntlet Validation Rule*):** De acuerdo con la filosofía de arnés de pruebas (*Test Harness*) postulada por Robert C. Martin ("Uncle Bob") para el desarrollo con IA, los asistentes de código **no** deben solicitar revisiones manuales línea por línea al usuario. Toda modificación debe someterse a portones automatizados de prueba/compilación y presentar evidencia empírica (`BUILD SUCCESSFUL`) en los registros de salida antes de declarar completada la tarea.
- 🛡️ **Invariantes Algorítmicos y Pruebas Negativas Obligatorias (2026-08-14):** Cualquier optimización o cambio heurístico en motores de coincidencia (corrector fonético, VAD, tokenización, segmentación de audio) debe incluir obligatoriamente suites de pruebas negativas (validar que oraciones/entradas cotidianas no relacionadas produzcan 0 falsos positivos y 0 sustituciones espurias). Se priorizan librerías canónicas auditadas (`strsim`, etc.) con filtros de orden $O(1)$ (e.g. poda rápida por longitud) frente a reimplementaciones matriciales ad-hoc sin cobertura exhaustiva.
- **Soporte Multi-Arquitectura (ARM64 + x86_64):** La aplicación y sus pipelines de construcción deben ser compatibles y validables tanto en dispositivos/máquinas anfitrionas ARM64 como en portátiles/emuladores x86_64.
- **Protección Térmica y de CPU en Móviles:** Las pruebas unitarias locales (`testDebugUnitTest`) se ejecutan aisladas en la JVM sin desencadenar la compilación pesada nativa en Rust (`cargoNdkBuild`), protegiendo el procesador del dispositivo. La generación de binarios APK/AAB completos se realiza en CI/CD (GitHub Actions). **En hosts tipo dispositivo móvil/embebido (regla 2026-08-06, ver "Regla de validación por entorno"), el agente no compila nada localmente: valida únicamente push a `main` + resultado del workflow debug de GitHub Actions.**
- **Anulación de AAPT2 del Sistema:** En entornos ARM64 Linux, configurar `android.aapt2FromMavenOverride=/usr/bin/aapt2` en `gradle.properties` para usar el procesador de recursos nativo del sistema.
- **Validación automatizada:** Ejecutar `./gradlew assembleDebug` o suites de unit test sin nuevos advertencias o fallos + smoke test funcional en dispositivo/emulador.
- **Java:** Sigue las convenciones de §4.
- **Rust:** Sigue el estilo inline existente (4 espacios, `rustfmt` por defecto).
- **Gates configurados / evidencia (Guantelete ABIERTO):**
  - `testDebugUnitTest`: **34 tests JVM verdes** (`BUILD SUCCESSFUL`, último run 2026-08-04): markers/subtitle prefs + `CallRegistryTest` (cancelación por owner) + `MarkerAtomicityTest` (lecturas concurrentes) + `FileSha256Test` + `PostProcessorTest` (suite HTTP P1.3 — payload, `stream:false`, `${output}` una vez, errores, fallback, timeout real de OkHttp por seam con valores escalados — **más DNS fail real con host `.invalid` y connect timeout por seam contra `192.0.2.1` desde 2026-08-04**; los 30 s/60 s de producción se asertan, no se esperan en wall-clock).
  - **Privacidad (2026-08-04):** el transcript crudo y los detalles de error del post-procesado **no se loguean en release** (gating con `BuildConfig.DEBUG`). No reintroducir logs de texto transcrito sin gate de debug.
  - **Gates CI (2026-08-04):** `cargo fmt --all -- --check` es gate duro en ambos workflows; `checkModels` también corre en el workflow debug; el workflow release falla rápido si falta `KEYSTORE_BASE64` y verifica `zipalign -c` + `apksigner verify` del APK antes de publicar.
  - `checkModels` verifica SHA-256 de assets bundled presentes; es no-op cuando falta el asset. La descarga runtime debug **también** verifica SHA-256 antes de activar `active_model` (P0.3, `FileSha256`), pendiente de smoke en dispositivo.
  - `scripts/check_translations.py` comprueba paridad de nombres en 6 locales alternativos; no detecta todas las cadenas hardcodeadas en Java. P2.4 (2026-08-03): las strings visibles de Java/layouts están migradas a recursos (44 nuevas, gate PASS); excepción documentada para los detalles de error de `PostProcessor` (sin `Context`, seam JVM) y las strings de protocolo JNI que usa la máquina de estados como comparadores.
  - `lintDebug` está configurado como hard gate en debug CI; pendiente de una ejecución actual tras los cambios del 2026-08-03.
  - Tests Rust `#[cfg(test)]` en `audio` y `corrector` tienen cobertura de lógica pura mediante crate espejo; el crate cdylib completo sigue bloqueado por `transcribe-cpp-sys v0.1.3` y no debe describirse como ejecutado satisfactoriamente en CI hasta existir un workflow/log reproducible.
  - El plan, los bloqueadores P0 y los criterios de cierre viven en [`GAUNTLETE_PLAN.md`](GAUNTLETE_PLAN.md) y [`.agents/memory/static-audit-debt-2026-08-03.md`](.agents/memory/static-audit-debt-2026-08-03.md).

---

## 4. Convenciones de Código y Estructura

### 4.1 Arquitectura

- **Patrón:** **Puente JNI por módulo Rust ↔ Java/Kotlin Service**. Cada servicio Java (`RecognizeActivity`, `RustInputMethodService`, `LiveSubtitleService`, `VoiceRecognitionService`) tiene su homólogo en `src/<mod>.rs` y expone al menos `initNative` / `cleanupNative` más acciones de alto nivel (`startRecording`, `stopRecording`, `pushAudio`, ...).
- **Estado global:** Rust usa `Lazy<Mutex<..>>` (de `once_cell`) para singletons (engine, sesión IME, sesión recog, estado de subtítulos).
- **Engine compartido:** `Arc<Mutex<Engine>>` con `Condvar` para coordinar cargas concurrentes. Ver patrón completo en `src/engine.rs` (`ensure_loaded_from_thread`).
- **Modelo de procesos:** main + `:ime` (aislado). Comparten **marcadores en `getFilesDir()`** — *no* `SharedPreferences` — porque `:ime` no puede leer prefs de la app principal sin un `ContentProvider`, y los marker files son trivialmente compartibles entre procesos.

### 4.2 Naming

- **Rust:** `snake_case` para variables/funciones, `PascalCase` para tipos (`Engine`, `VoiceSessionState`, `Endpointing`). `SCREAMING_SNAKE_CASE` para constantes (`MAX_RUN_SAMPLES`, `MIN_SPEECH_LEVEL`). `Java_*` prefix en símbolos JNI exportados.
- **Java:** `PascalCase` para clases, `camelCase` para campos/métodos. Standard Android (Activity/Service/View).
- **Recursos:** `snake_case` (`voice_status_ready`, `activity_main`, `bg_card`).

### 4.3 Naming JNI (regla dura)

Todos los entry points nativos exportados siguen este formato:

```
Java_dev_notune_transcribe_<JavaClassName>_<methodName>
```

Mismas funcionesJava + mismas firmas (incluido el `JClass` aunque no se use). Ver por ejemplo `Java_dev_notune_transcribe_RecognizeActivity_initNative` en `src/recognize.rs`. **No renombrar sin actualizar la declaración nativa en Java.**

Carga de librerías en cada proceso (orden estricto, en bloque `static`):

```java
static {
    try { System.loadLibrary("c++_shared"); } catch (UnsatisfiedLinkError e) { /* warn */ }
    System.loadLibrary("android_transcribe_app");
}
```

### 4.4 Manejo de errores y logging

- **Logging nativo:** `log::info!`, `log::warn!`, `log::error!` (filtrado a `Info`, ver `android_logger::init_once`).
- **Logging Java:** `Log.d/i/w/e(TAG, msg, throwable)` con `TAG` por clase (`OfflineVoiceInput`, `MainActivity`, `LiveSubtitleService`...).
- **Call-backs JNI → Java (firmas estables — NO cambiarlas sin tocar todas las superficies):**
  - `onStatusUpdate(String)` — mensajes como `"Ready"`, `"Listening..."`, `"Transcribing..."`, `"Error: <msg>"`.
  - `onTextTranscribed(String)` — texto final (post-procesado aplicado si está activo).
  - `onAudioLevel(float)`/`onRmsChanged(float)` — nivel rms 0..1.
  - `onSubtitleText(String, boolean)` — parcial (false) o final (true).
  - `onPartialText(String)` — hipótesis parcial en vivo de modelos streaming (Nemotron) mientras se graba; visual-only en Java (el texto final llega siempre por `onTextTranscribed`/`onResults`). Las superficies sin streaming envían no-op.
  - `onAutoStop()` — disparo del monitor de endpointing.
  - **Grabación IME/popup con generación:** las superficies `RustInputMethodService` y `RecognizeActivity` mantienen además las variantes JNI `onStatusUpdate(String, int)`, `onTextTranscribed(String, int)`, `onAudioLevel(float, int)`, `onPartialText(String, int)` y `onAutoStop(int)`. El `int` es el `sessionId` de la grabación; Java descarta callbacks tardíos cuyo ID ya no coincide con la generación activa. El engine/lifecycle conserva las variantes sin ID para estados de carga y compatibilidad con las demás superficies.
  - **Transcripción de archivos con operation-id (P1.1):** `TranscribeFileActivity` mantiene `onTextTranscribed(String, int)` y `onStatusUpdate(String, int)` donde el `int` es un operation-id único por decode (`AtomicInteger` estático, inmune a recreaciones de Activity); `transcribeAudio(float[], int, int)` lo recibe de Java y lo devuelve en cada callback. Java descarta callbacks cuyo opId ya no es el actual o cuya Activity está `isFinishing()/isDestroyed()`.
  - `onBenchmarkResult(float audioSecs, float computeSecs, String error)`.
- **Resiliencia del engine:** `transcribe_shared` hace `panic::catch_unwind` + recuperación de `Mutex` envenenado (`unwrap_or_else(|p| p.into_inner())`). Si lo refactorizas, **mantén estas dos capas**.

### 4.5 Manejo de settings (regla del proyecto)

Los ajustes son **marker files en `filesDir()`**, no `SharedPreferences`. Ejemplos:

| Marker file | Significado |
|---|---|
| `auto_record` | presente = *Auto-start recording* ON |
| `select_transcription` | presente = selecciona el texto transcrito |
| `pause_audio` | presente = pausa audio del sistema mientras se graba |
| `stop_on_hide` | presente = *Record in background* OFF (default ON es el opuesto) |
| `auto_stop` | presente = *Auto-stop after silence* ON |
| `model_language` | contenido = locale BCP-47 (`en-US`, `es-ES`, …) o `auto` |
| `model_translate` | presente = traducir a inglés (Whisper) |
| `active_model` | contenido = nombre del GGUF importado en `models/` |
| `model_threads` | contenido = nº entero; ausente/inválido = automático |
| `stream_context_right` | contenido = `13`/`6`/`1`/`0` → chunks cache-aware {1.12 s, 560 ms, 160 ms, 80 ms} (Nemotron, `chunk = (right+1) × 80 ms`); ausente/inválido = `13` (default de precisión del modelo). Valores fuera de menú se reintentan con el default del modelo en `run_stream`, no rompen el stream |
| `hardware_backend` | contenido = `cpu` (ARM NEON + dotprod + fp16 - default/recomendado), `npu` (NNAPI), `gpu` (Vulkan) → acelerador de inferencia seleccionado en `ModelsActivity` |
| `custom_words` | contenido = términos correctos, uno por línea (líneas `#` = comentarios); ausente/vacío = corrección fonética desactivada |
| `subtitle_translation_target` | contenido = `auto` (idioma original, sin traducción — decisión de producto) o un locale BCP-47 (`es-ES`, `en-US`, `fr-FR`, `de-DE`, `it-IT`, `pt-PT`, `ru-RU`) → traducción on-device de subtítulos **finalizados** (ML Kit, paquetes vía Google Play Services; fallback siempre al texto original). Lo lee `LiveSubtitleService` al iniciar sesión; no requiere recarga del engine |

Bindings típicos (ver `MainActivity.bindMarkerSwitch`): un `CompoundButton` cuja presencia del file representa el estado.

La **clave de API del post-procesado** se almacena como marker file codificado en Base64 (`pp_api_key`), igual que el resto de ajustes. La protección real la proporciona el sandbox de Android (filesDir es privado de la app).

### 4.6 Mapping Rust ↔ Java (mapping de módulos para agentes)

El árbol de carpetas está en [`README.md` §Project Structure](README.md#project-structure).
Esta tabla mapea **qué módulo Rust ↔ qué componente Java ↔ dónde se invoca al
engine compartido** — la información que un agente necesita pero el README no
cubre.

| Módulo Rust (`src/<mod>.rs`) | Componente Java | Llama al engine desde | Particularidades (AGENT-ONLY) |
|---|---|---|---|
| `lib.rs` | — | — | `pub mod` raíz. Cualquier módulo nuevo debe declararse aquí. |
| `engine.rs` | (singleton global) | `voice_session.rs`, `subtitle.rs` (worker), `recog_service.rs` (audio callback), `main_activity.rs` (benchmark) | `ensure_loaded_from_thread` coordina con `Condvar`; **idioma se re-lee en cada `run`** (no cachear). |
| `audio.rs` | — | `engine.rs` (`find_quietest_split`) | Utilidad pura, sin JNI. |
| `voice_session.rs` | `RecognizeActivity`, `RustInputMethodService` | vía JNI callback `onTextTranscribed(text)` | Comparte `cpal::Stream` entre las dos surfaces; auto-stop propio. |
| `subtitle.rs` | `LiveSubtitleService` | vía JNI callback `onSubtitleText(text, isFinal)` | Pipeline partial/final con merging; lag-policies calibradas (ver §4.8). |
| `recog_service.rs` | `VoiceRecognitionService` | síncrono en `audio_callback` | Endpointing con VAD silencioso; sin auto-stop (lo gestiona el monitor). |
| `ime.rs` | `RustInputMethodService` | thin bridge → `voice_session` | Llamadas JNI: `init/cleanup` + `start/stop/cancel` Recording. |
| `recognize.rs` | `RecognizeActivity` | thin bridge → `voice_session` | Idéntico a `ime.rs` pero con `jboolean auto_stop` en `startRecording`. |
| `main_activity.rs` | `MainActivity` | benchmark + status notifier | `initNative`, `benchmarkNative`; cachea samples en `Vec<f32>` desde `bench.wav`. |
| `models.rs` | `ModelsActivity` | reload engine vía `engine::reset()` | Importa GGUF a `filesDir/models/<nombre>`. |
| `transcribe_file.rs` | `TranscribeFileActivity` | una sola `transcribe_shared` | Comparte audio desde `Intent.ACTION_SEND/VIEW` (`audio/*`). |
| `corrector.rs` | (`CustomWordsActivity` escribe el marker file) | vía `engine::transcribe_shared` (post-ASR, pre-callback) | Corrección fonética post-ASR: lee `filesDir/custom_words`, codifica cada palabra del transcript y los términos del diccionario con un codificador fonético ES+EN, reemplaza palabras mal reconocidas por la más cercana (Levenshtein ≤ 2 sobre claves fonéticas + tiebreak coseno de bigramas). Safe-fallback: cualquier fallo (sin diccionario, I/O error, panic) devuelve el texto crudo. Ejecuta dentro de su propio `catch_unwind` para no congelar el IME. Cubre TODAS las superficies (IME, popup, subtítulos, SpeechRecognizer, archivo) de golpe. |
| `assets.rs` | (interno) | `engine.rs` (`do_load`) | Extrae el modelo bundled desde assets al primer arranque; `invalidate_builtin_model` borra extraídos corruptos. |

**Regla modular:** mover lógica entre módulos exige actualizar también
`AndroidManifest.xml` (registro de activities/services) y `lib.rs` (`pub mod`).
No renombrar archivos Rust/Java sin actualizar la entrada JNI (§4.3).**Post-procesado AI (fork addition, Java-only — contrato AGENT-ONLY):**
- **`pp_default_prompt` es una sola línea con escapes `\n`.** AAPT2 **colapsa los saltos de línea
  literales** de un `<string>` a un solo espacio (verificado con `aapt2 dump strings`). Un valor
  multilínea se rompe en runtime: el campo de `PostProcessSettingsActivity` muestra todo junto
  y el payload enviado al LLM es un muro plano — esa fue la causa del "prompt juntado" y de que el
  LLM no refinara el texto. Usa `\n` (y `\n\n` para párrafos en blanco) dentro del valor.
- **El transcript no se manda dos veces.** Si el prompt activo contiene `${output}`, el texto
  crudo se inyecta en el system prompt y el `user message` pasa a ser el marcador fijo
  `"Apply the instructions to the transcript above."`. Si NO hay `${output}`, el texto viaja en
  el user message. No duplicarlo (flag `injected` en `process()` de `PostProcessor.java`):
  mandarlo dos veces hace que el LLM devuelva la entrada sin refinar.
- **Post-procesado final-only:** el streaming del transcriptor (`onPartialText`) es únicamente
  una previsualización visual. Cuando llega `onTextTranscribed`, se manda el texto final una
  sola vez mediante `process()` con una respuesta JSON completa (`stream` no se envía), y el
  editor recibe un único `commitText` con el resultado refinado. Si el postprocesado está
  desactivado, se comete el texto ASR directamente; si la llamada falla, se cancela o devuelve
  una respuesta vacía/no válida, siempre se comete el texto crudo. Así se evita pegar tokens
  parciales, duplicados o texto "Frankenstein" y se conserva la fluidez del streaming ASR.
- **Cancelación/lifecycle:** la cancelación es **por propietario** (`PostProcessor.cancelAllFor(owner)`, identidad de la Activity/Service dueña de la llamada; registro en `CallRegistry`): destruir una superficie o cancelar un reconocimiento nunca cancela una petición legítima de otra superficie. `PostProcessor.cancelAll()` (global) queda reservado para eventos realmente globales: toggle PP-off (con broadcast `CANCEL_ACTION` al proceso `:ime`) y muerte del servicio IME. Los validadores de Activity/IME impiden callbacks tardíos sobre componentes destruidos, y el `Response` de OkHttp se cierra siempre, incluidos errores y parseos fallidos.
- **Traducción de subtítulos (fork addition, contrato AGENT-ONLY):** el modelo bundled (Nemotron) **no traduce**; la traducción es una etapa Java-side exclusiva de subtítulos sobre segmentos **finalizados** (`LiveSubtitleService` + `OnDeviceSubtitleTranslator` con ML Kit). El destino vive en el marker `subtitle_translation_target` (`auto` = idioma original; locale fijo = traducir) y lo lee el servicio al iniciar sesión — **no** reutilizar `model_translate` (eso es ASR-global y **nunca** debe aplicar a subtítulos: `engine::transcribe_subtitle` fuerza `Task::Transcribe`). Cola FIFO serial (máx. 8), resultados aplicados en orden en main thread, fallback **siempre** al texto original (sin Play Services, sin paquete, error, saturación), generación de sesión para callbacks tardíos, y origen automático resuelto por script del texto (`SourceLanguageResolver`) cuando `model_language` no es fijo.

### 4.7 Rust: contratos del singleton Engine (no romper)

- **`engine::get_engine()` → `Option<Arc<Mutex<Engine>>>`**: compartido por todos los procesos Java. Garantías: panic-catching, recovery de Mutex poison.
- **`engine::ensure_loaded*`**: idempotente, multi-thread safe, espera via `Condvar` si otra thread está cargando, reintenta tras `Failed(_)`.
- **`Engine::transcribe`** divide audio largo en el punto más silencioso (`audio::find_quietest_split`); une los textos con un espacio.
- **`Engine::run` re-lee `model_language` en CADA llamada** (issue v0.1.20→21). Esto es lo que hace que el cambio de idioma en el spinner de `ModelsActivity` aplique también en el proceso `:ime` sin recarga manual. **No cachear el idioma dentro del Engine** — ese fue el bug original.

### 4.8 Subtítulos: pipeline con coste predecible

`src/subtitle.rs` es probablemente el código más sutil. Reglas:

- **Parciales:** sólo se encolan si (a) worker ocioso, (b) sin pendientes finales, (c) `segment_secs * rtf <= MAX_PARTIAL_COST_SECS` (latest-wins). Si el dispositivo transcribe más lento que el audio, las parciales se cortan — es por diseño.
- **Finales:** siempre se encolan (FIFO), *pero* se pliegan con `try_recv` hasta `MAX_MERGED_SAMPLES` (25 s) para amortiguar la ventana fija de Whisper.
- **Lag-policies (importantes, no subir los umbrales a la ligera):**
  - `MAX_FINAL_LAG_SAMPLES = 8 s` → drop con `"…"` gap.
  - `MAX_PARTIAL_LAG_SAMPLES = 3 s` → descartar.
  - `MAX_PARTIAL_COST_SECS = 2.0` → toggle on/off de parciales por coste.
- La fusión de finales es lo que permite a un dispositivo lento seguir el ritmo real sin perder texto. Si lo eliminas, la latencia crece sin cota.

### 4.9 Java UI: convenciones observables

- **Tema base:** `Theme.Material3.DayNight.NoActionBar` + `DynamicColors.applyToActivitiesIfAvailable(this)` en `App.onCreate` (Material You desde Android 12+).
- **IME:** `RustInputMethodService` no es `AppCompatActivity` (es `InputMethodService` en proceso `:ime`). Construye su propio contexto theme-aware con `ThemePrefs.wrapForNight` + `DynamicColors.wrapContextIfAvailable` para que la vista coincida con el ajuste de tema de la app principal.
- **Pantalla de voz (popup):** `AppTheme.VoicePanel` translúcido — NO pantalla completa — para que la app que invocó la voz conserve su UI detrás.
- **Estado interno del engine (`"Loading"`, `"Initializing"`, `"Waiting"`) NO se muestra al usuario:** la UI mapea siempre a `Tap to Record`, `Listening...`, `Processing...`, etc. (ver `updateUiState` en `RustInputMethodService`).
- **Pantallas han de re-pintar en cambios de tema** (ej. IME reconstruye su `inputView` si `ThemePrefs.isNight` cambia desde `onStartInputView`).
- **Botón Cancelar del IME (`ime_cancel`):** visible durante la grabación (descarta la captura antes de transcribir/postprocesar, evitando consumo de API) y durante toda la ventana `resultPending` (desde que se suelta el micrófono hasta el commit/cancel). `resultPending` en `RustInputMethodService` es la **única fuente de verdad** en `updateRecordButtonUI(false)`; **no reintroducir** checks de `lastStatus` (Transcribing/Processing) en esa rama — un `"Processing..."` stale re-mostraría el botón tras el commit (parpadeo). Toda ruta de stop (mic, auto-stop, switch de teclado) activa `resultPending` de forma síncrona; la limpian commit, cancel, texto vacío, `stop_on_hide`, error terminal y el receiver `CANCEL_PP`; una grabación nueva la resetea.

### 4.10 i18n

- **Todas las cadenas nuevas** → añadir en `app/src/main/res/values/strings.xml` y propagarlas a `values-es`, `values-de`, `values-fr`, `values-it`, `values-pt`, `values-ru`.
- Si una cadena **no debe traducirse** (ej. prompt de sistema por defecto), usa `translatable="false"` (ver `pp_default_prompt`).
- **Valores `<string>` multilínea:** AAPT2 colapsa los saltos de línea literales a un solo espacio. Si el texto debe verse/enviarse multi-parágrafo (caso `pp_default_prompt`), guarda el valor en UNA línea con escapes `\n` (`\n\n` = párrafo en blanco). Verificado con `aapt2 dump strings`; ver §4.6.
- Si el orden de idioma importa (ej. ajustes que aparecen en una columna lateral con icono), respetar el orden existente en `MainActivity.bindMarkerSwitch`.

### 4.11 Versión y release

- **Bumps de versión:** editar `versionCode` **y** `versionName` en `app/build.gradle.kts`. `versionCode` siempre incremental.
- **Notas de release:** añadir bloque nuevo a `RELEASE_NOTES.md` en la cabecera (no al final). El CI (`android_release.yml`) usa `body_path: RELEASE_NOTES.md` para `softprops/action-gh-release@v2`.
- **Solo APK:** construir AAB está deshabilitado en CI y en comentarios. Si necesitas AAB para Play Store, recuerda que el `:model_assets` activa `dynamicDelivery` install-time (ver `model_assets/build.gradle.kts`) y el `app/build.gradle.kts` hace fallback añadiendo `model_assets/src/main/assets/` a `assets.srcDirs` solo cuando NO es bundle.

---

## 5. Reglas para Agentes de IA

### 5.1 EVITAR (cosas que romperían el proyecto)

- ❌ **No cambiar la firma de los call-backs JNI** listados en §4.4 sin actualizar cada superficie Java (`RecognizeActivity`, `RustInputMethodService`, `LiveSubtitleService`, `VoiceRecognitionService`).
- ❌ **No cachear el `model_language`** dentro de `Engine` o entre llamadas. El comportamiento intencional es leer el marker file en cada `Engine::run` para que el cambio aplique también en `:ime`. Ese fue el bug crítico de v0.1.20 → v0.1.21.
- ❌ **No eliminar las dos capas de resiliencia** de `transcribe_shared` (`catch_unwind` + recuperación de Mutex envenenado). Si lo haces, un único panic deja el IME bloqueado en "Processing" hasta que muera el proceso.
- ❌ **No añadir soporte para otras ABIs** (`armeabi-v7a`, `x86`, `x86_64`) sin revisar antes:
  - `check_cpu_features` requiere dotprod+fp16 (ARMv8.2 ~2018+); cualquier dispositivo antiguo fallaría en medio de la inferencia con `SIGILL`.
  - el ggml cross-compile con `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` está fijado en `app/build.gradle.kts`.
- ❌ **No romper la verificación SHA-256** ni eliminar el marker de "modelo bundled" — protege contra apps que se actualizan con un modelo nuevo y mantienen el viejo en cache. La descarga runtime debug verifica el hash con `FileSha256` **antes** de escribir `active_model` (P0.3, implementado 2026-08-03); no degradar esa ruta a activación sin hash.
- ❌ **No convertir la app a Kotlin** sin discutirlo antes. Es un proyecto Java a propósito (legado del upstream, evita overhead de KSP/codegen).
- ❌ **No mover ajustes a `SharedPreferences`** sin migrar también los marker files consumidos por Rust (`model_language`, `model_translate`, `active_model`, `model_threads`). El código Rust solo lee del *filesystem*, no de prefs.
- ❌ **No añadir dependencias síncronas bloqueantes** al evento del audio callback de `cpal` (en `voice_session.rs`/`recog_service.rs`). Toda escritura a `audio_buffer` debe ser O(1) bajo el mutex.
- ❌ **No cambiar el orden de carga** de las librerías nativas (siempre `c++_shared` antes de `android_transcribe_app`) ni saltarse el `try/catch UnsatisfiedLinkError` — protege contra dispositivos/ABIs mal.
- ❌ **No subir umbrales** de `MAX_FINAL_LAG_SAMPLES` / `MAX_PARTIAL_LAG_SAMPLES` / `MAX_PARTIAL_COST_SECS` en `subtitle.rs` sin probar hardware lento: esos números están calibrados para que un dispositivo medio no se quede atrás, no son free-tuning.
- ❌ **No reemplazar `transcribe_shared` por código que ignore panics.** El `panic::catch_unwind` documenta un caso real: el modelo puede panic de fondo por allocations grandes, y el efecto sin catch sería un IME congelado.
- ❌ **No romper el fallback del post-procesado:** si la llamada al LLM falla, siempre se entrega la transcripción cruda (`onError → deliverResult(text)`). Es la garantía de "no perder texto".
- ❌ **No escribir `pp_default_prompt` (ni ningún `<string>` largo) con saltos de línea literales.** AAPT2 los colapsa a un solo espacio: rompe la UI del prompt y aplana el payload que guía al LLM a refinar. Usa escapes `\n` (ver §4.6).
- ❌ **No realizar compilaciones manuales de release/debug fuera del pipeline oficial de GitHub Actions** salvo para pruebas puntuales por ADB durante desarrollo local. Las compilaciones de depuración se canalizan vía GitHub Actions enviando el APK al bot de Telegram; las de release se generan automáticamente por GitHub Actions.
- ❌ **No borrar el comentario explicativo en app/build.gradle.kts sobre `assetPacks` + bundle-vs-APK.** Es la trampa que rompió v0.1.x del upstream y se documentó específicamente para evitar.
- ❌ **No introducir tests automatizados que importen el módulo entero** a través de JNI en CI: no hay emulador arm64 en el runner de GitHub Actions y los tests no-existentes ya dicen que validación = build + smoke test manual.

### 5.2 HACER (reglas positivas al añadir código)

- ✅ Si añades un call-back JNI nuevo, documéntalo con la firma exacta en este `AGENTS.md` (§4.4) y añade un stub no-op en Java por defecto para no romper builds en los que aún no has cableado el lado Java.
- ✅ Cada nuevo ajuste del usuario va como **marker file en `filesDir()`**, sin excepción. La API key usa codificación Base64 para ofuscación mínima; la seguridad real viene del sandbox de Android.
- ✅ Toda escritura de markers cross-process (main/`:ime`) pasa por `MarkerFileHelper` (temp **único por escritura** + fsync + rename; delete en vacío). Nunca `FileOutputStream` directo sobre el nombre final: writers concurrentes compartiendo el mismo `*.tmp` pueden exponer contenido parcial (cazado por `MarkerAtomicityTest`).
- ✅ Antes de añadir cadenas visibles, duplicarlas en los 7 locales. Si añades una cadena que **no** quieres traducir, márcala con `translatable="false"`.
- ✅ Si tocas el `Engine`, lee primero `src/engine.rs` completo (~370 líneas, muy comentado): decisiones sobre warm-up, re-read de idioma, fallback a modelo bundled, warnings de capabilities (`supports_translate`/`variant().contains("turbo")`), Whisper `temperature_inc`, todo está allí por una razón documentada.
- ✅ Si añades un nuevo surface (Activity/Service), recuerda:
  1. Bloque `static` con `c++_shared` + `android_transcribe_app`.
  2. Método nativo declarado en Java + implementación en Rust con prefijo `Java_dev_notune_transcribe_<ClassName>_`.
  3. Llamadas JNI con `get_java_vm`, `new_global_ref` y release apropiado en `cleanupNative`.
  4. Si es un servicio con proceso aislado, sincroniza vía marker files (no `SharedPreferences`).
- ✅ Si añades un modelo bundled:
  1. Subir el SHA-256 real, **no** inventar uno.
  2. Sumar el `ModelFile` en `modelPackFiles` (`app/build.gradle.kts`).
  3. Si el modelo es > 200 MB, mantener `model_assets` como asset pack.
- ✅ Si modificas la lógica de carga de Rust:
  - Los `outputs.upToDateWhen { false }` están puestos **a propósito** (el incremental de Cargo es fiable, los inputs/outputs de Gradle no).
  - El shim de `libpthread.a` y la librería `c++_shared` dinámica son **necesarios para el build Android**. Ver comentario en `build.rs`.
- ✅ Si tocas el benchmark, lee primero los comentarios de `MainActivity.runBenchmark`/`readWavAsset` (sólo admite 16 kHz mono 16-bit PCM).
- ✅ Bump de versión: `versionCode` siempre +1 sobre el actual; `versionName` semver-friendly; añadir entrada en cabecera de `RELEASE_NOTES.md` (no al final).
- ✅ Si rompes un idioma o un locale: la app **transcribe en el dispositivo-locale por defecto** (`App.applyDeviceLanguageIfUnset`) y es opt-out. Cualquier override del usuario debe respetar ese comportamiento (ver `ModelsActivity` + `engine::run`).
- ✅ Si añades una dependencia Gradle: respeta el bloque `constraints` que alinea `kotlin-stdlib-jdk7/jdk8` a `1.8.22`. Sin esa alineación, builds nuevos rompen con `Duplicate class` por culpa del legacy transitivo de Material.
- ✅ Si modificas el flujo de subtítulos, preserva el contrato de `onSubtitleText(text, isFinal)`: `isFinal=true` appendea, `isFinal=false` reemplaza hipótesis parcial dentro de la misma ventana.
- ✅ Si tocas el streaming cache-aware (`run_stream`/`transcribe_stream_shared` en `engine.rs`), preserva: (a) el retry de `att_context_right` fuera del menú del GGUF con el default del modelo (`Error::InvalidArgument` → reintento con `None`), (b) la degradación de hint de idioma, y (c) la telemetría de sesión en el log de Stop (`rtf`, nº de parciales, cadencia media, `att_context_right`) — es la única forma de medir el trade-off WER vs fluidez en dispositivo real vía logcat. Referencia del trade-off publicado: chunks {1120, 560, 320, 160, 80} ms con WER FLEURS a 1.12 s de en 7.91 / es 4.11 / fr 9.03 / it 4.25 / pt 5.48 / de 8.31; la card afirma precisión "competitiva" incluso a 80 ms.
- ✅ No añadas `unsafe` Rust innecesario fuera de los entry points JNI (`#[no_mangle] pub unsafe extern "system" fn Java_…`).

### 5.3 Plantilla para tests / nuevos componentes (resumen rápido)

Crear un nuevo módulo Rust con JNI → requiere:

1. **Rust** (`src/<mod>.rs`):
   ```rust
   use jni::objects::{JClass, JObject};
   use jni::JNIEnv;
   use once_cell::sync::Lazy;
   use std::sync::Mutex;

   pub struct FooState { /* JVM + GlobalRef al target */ }

   static STATE: Lazy<Mutex<Option<FooState>>> = Lazy::new(|| Mutex::new(None));

   #[no_mangle]
   pub unsafe extern "system" fn Java_dev_notune_transcribe_FooService_initNative(
       env: JNIEnv, _class: JClass, target: JObject,
   ) { /* new_global_ref, attach thread, etc. */ }
   ```
2. **Java** (paralelo):
   ```java
   public class FooService extends Service {
       static {
           try { System.loadLibrary("c++_shared"); } catch (UnsatisfiedLinkError e) { }
           System.loadLibrary("android_transcribe_app");
       }
       public void onStatusUpdate(String s) { /* runOnUiThread o mainHandler */ }
       private native void initNative(FooService self);
       private native void cleanupNative();
   }
   ```
3. **Manifest**: añadir `<service android:name=".FooService" .../>` (y `android:process=":foo"` si debe ser proceso aislado).
4. **lib.rs**: `pub mod foo;`.

Crear un nuevo ajuste toggle en UI → marker file en `filesDir()` con un nombre `snake_case`; bindearlo con `bindMarkerSwitch(...)` en `MainActivity` (patrón ya existente). Propagar cadena a los 7 locales.

---

## 6. Convenciones de commits

- Estilo observado en `git log`:
  - `feat: <qué>`, `fix: <qué>`, `chore: <qué>`, `ci: <qué>`, `docs: <qué>`, `refactor: <qué>`, `build: <qué>`.
  - Lenguaje de los mensajes: inglés, frases imperativas concisas, sin punto final.
- Para cambios visibles al usuario que merezcan release, **primero el bloque en `RELEASE_NOTES.md`**, luego el commit, luego el tag `vX.Y.Z` (el CI dispara el build firmado y crea el GitHub Release con las notas).

---

## 7. TL;DR para una IA nueva

- Lee `src/engine.rs` y `Cargo.toml` primero.
- Respeta nombres JNI (`Java_dev_notune_transcribe_<Class>_<method>`) y firmas de call-backs en §4.4.
- Ajustes = marker files en `filesDir`. Clave API también en marker file (Base64).
- Post-procesado: opcional, *safe-fallback* obligatorio al texto crudo.
- Idioma se re-lee en cada `Engine::run` (no cachear en memoria dentro del engine).
- Subtítulos usan un modelo de partial/final con lag-policies calibradas; subir esos números rompe el contrato.
- Bug histórico a evitar: v0.1.20 → v0.1.21 (idioma cacheado en `:ime`). La regla "re-read on every run" está escrita en muchos comentarios a propósito.
- Antes de PR: `./gradlew assembleDebug` debe pasar limpio sin warnings nuevos y las cadenas nuevas deben estar en los 7 locales.
- **Validar builds por entorno (2026-08-06):** en un host tipo dispositivo móvil/embebido NUNCA compiles localmente — `git push` a `main` y lee el workflow debug de GitHub Actions con `gh run list`/`gh run view`. En un equipo físico (portátil/ordenador) sí puedes compilar local.
