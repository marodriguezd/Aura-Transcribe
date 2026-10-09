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

**Arquitectura general: UN SOLO PROCESO.** Toda la app (MainActivity, IME, servicio de reconocimiento, overlay, subtítulos) corre en el proceso por defecto: `AndroidManifest.xml` **no declara ningún `android:process`** (la afirmación histórica de un proceso `:ime` aislado era falsa y nunca se implementó — corregido 2026-10). Toda la lógica pesada (ASR, captura audio, segmentación, JNI) está en **Rust** compilado como `cdylib` (`Cargo.toml`) y enlazado por JNI desde Java. Los ajustes se guardan en ficheros *marker* en `getFilesDir()` (sin `SharedPreferences`) porque el motor Rust solo lee del *filesystem* y deben ser legibles sin importar qué componente arrancó primero.

> ⚠️ **NO añadir `android:process=":ime"`.** Estaría tentador para "aislar" el teclado, pero rompería dos invariantes reales: `voice_session.rs` **comparte deliberadamente un único `cpal::Stream`** entre el IME y el popup, y el engine vive en un `Arc<Mutex<Engine>>` global. Separar procesos duplicaría el engine y rompería esa compartición.

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
| Lenguaje UI | **Java** (sin Kotlin) | Java 17 source/target |
| Android Gradle Plugin | `com.android.application` | **9.4.1** — obligatorio para API 37, ver nota |
| Build tool | Gradle wrapper | **9.8.0** (ver `gradle/wrapper/gradle-wrapper.properties`) |
| Toolchain humano | JDK 17 · Android NDK **28.2.13676358** (unificado en Gradle = CI = README) · Rust `aarch64-linux-android` · [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) | Host soportados: linux-x86_64, darwin-x86_64, darwin-arm64, windows (ver `ndkPrebuiltDir()` en `app/build.gradle.kts`); un host sin prebuilt NDK falla con mensaje claro |
| Target NDK ABIs | **`arm64-v8a` únicamente** (`abiFilters += "arm64-v8a"`) | excluidas x86 / armeabi-v7a; 16 KB page size aligned (gate `checkNativeAlignment`) |
| `compileSdk` / `targetSdk` / `minSdk` | Gradle efectivo: `37 / 37 / 26` | **Android 17 = API 37**; Android 15 = API 35. Cubre Android 8.0 (API 26) hasta Android 17 |
| Optimización de release | R8 (minify) + resource shrinking | `isMinifyEnabled = true`, `isShrinkResources = true`; keep rules en `app/proguard-rules.pro` (JNI + callbacks por nombre). Build type `benchmark` + módulo `:benchmark` para Macrobenchmark / Baseline Profile |
| Material | Material Components for Android | `1.14.0` (Material 3 + Material You) |
| HTTP (post-procesado) | OkHttp | `4.12.0` |
| Almacenamiento clave API | marker `pp_api_key_enc` cifrado AES-256-GCM (formato `v1:`) con clave en AndroidKeyStore | `SecureCredentialStore` + `javax.crypto` del SDK; `androidx.security.crypto` solo para leer el formato legado |
| Alineación kotlin-stdlib | Forzadas a `1.8.22` (vacías) | ver bloque `constraints` en `app/build.gradle.kts` |

> ⚠️ **Por qué AGP 9.x y no 8.x:** AGP **8.13.x no puede resolver la plataforma de API 37**. Está probado hasta compile SDK 36.1 y falla con `Failed to find target with hash string 'android-37'` porque instala la plataforma como `android-37.0` pero busca `android-37`. La migración a API 37 exige la línea 9.x. No bajar AGP/Gradle sin bajar también `compileSdk`.

> ℹ️ **R8 / non-transitive R classes:** con `android.nonTransitiveRClass=true` (y AGP 9) el `R` de Material **no** contiene atributos definidos en AppCompat. Usa `androidx.appcompat.R.attr.colorPrimary` / `colorError`, pero `com.google.android.material.R.attr.colorOnError` / `colorPrimaryContainer` (esos sí son de Material). Mezclarlos mal rompe la compilación.

**Permisos críticos** (`AndroidManifest.xml`):
- `RECORD_AUDIO`, `INTERNET` (post-procesado), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_MICROPHONE`,
  `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`.
- ❌ **`READ_USER_DICTIONARY` fue eliminado** (2026-10): la plataforma lo mantiene fuera del SDK público (`android.Manifest.permission` no expone esa constante), así que una app con SDK moderno **nunca puede obtenerlo**. Declararlo solo anunciaba un acceso imposible y la consulta al `ContentProvider` siempre lanzaba `SecurityException`. **No reintroducirlo.**
- Backup: `allowBackup="false"` **más** `android:dataExtractionRules` + `fullBackupContent`, que excluyen todo — en Android 12+ `allowBackup=false` por sí solo no impide la transferencia dispositivo-a-dispositivo.

**i18n:** 7 locales paralelos (EN `values/`, ES `values-es/`, DE `values-de/`, FR `values-fr/`, IT `values-it/`, PT `values-pt/`, RU `values-ru/`) + `values-night/` (estilos dark).

---

## 3. Comandos y wiring

> Comandos user-facing y prerequisitos viven en [`README.md` §Building](README.md#building)
> y [`README.md` §Prerequisites](README.md#prerequisites). Esta sección sólo
> lista las **decisiones de wiring** que un agente debe respetar y los puntos
> donde divergen de la regla general.

### Wiring Gradle ↔ cargo-ndk (AGENT-ONLY)

- `cargoNdkBuild` está registrada como dependencia de `preBuild` y **sólo se ejecuta
  cuando falta el `.so`** (`onlyIf { !soFile.exists() }`, con
  `app/src/main/jniLibs/arm64-v8a/libandroid_transcribe_app.so` como referencia).
  Consecuencia práctica: si sólo cambian ficheros Rust y el `.so` ya existe,
  Gradle **no** recompila. Para forzar un rebuild nativo, borra ese `.so`
  (o ejecuta `cargo ndk -t arm64-v8a build --release` a mano).
- `app/build.gradle.kts` fuerza `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` vía
  env en `cargoNdkBuild`. **No bajar** este flag: ggml cross-compila con kernels
  baseline y la inferencia cuantizada cae a ≥4× más lenta. `check_cpu_features`
  en `engine.rs` aborta al cargar si la CPU no tiene dotprod+fp16 (ARMv8.2 ~2018+).
- `libc++_shared.so` se copia tras cada build desde el NDK a
  `app/src/main/jniLibs/<abi>/` (Bionic no la trae integrada y `transcribe-cpp`
  la enlaza dinámicamente).
- **Entrega del modelo bundled (APK vs AAB):** son mecanismos mutuamente
  excluyentes para el mismo variant `release`.
  - El **APK** tiene que llevar el GGUF dentro del módulo base, porque AGP ignora
    los asset packs al empaquetar un APK por eso el bloque `if (!isBundle)` en
    `app/build.gradle.kts` añade `model_assets/src/main/assets/` a
    `assets.srcDirs` del source set `release` (solo ese source set: el APK de
    debug se queda sin modelo a propósito).
  - El **AAB** debe llevar el GGUF **solo** en el asset pack `:model_assets`
    (`dynamicDelivery = "install-time"`), o Play recibiría 750 MB duplicados.
  - Una invocación que pida **ambos** falla al configurar con un mensaje
    accionable (`bundleTaskNames` y `apkTaskNames` no vacíos). Antes prefería
    silenciosamente la rama bundle y producia un APK de 32 MB sin modelo que
    compilaba "bien". **Construye cada artefacto en su propia invocación.**
  - Dos gates de artefacto lo verifican sobre el fichero real, no sobre flags:
    `verifyReleaseApkModel` (dependencia de `assembleRelease`) y
    `verifyReleaseBundleModel` (dependencia de `signReleaseBundle`). Ambos abren
    el zip y exigen que el modelo esté **exactamente una vez y en la ruta exacta**
    del tipo de artefacto (APK → `assets/builtin-model/<nombre>`, AAB →
    `model_assets/assets/builtin-model/<nombre>`); un camino anidado o de otro
    módulo que solo *termine* con ese sufijo se rechaza, y cualquier copia extra
    del mismo fichero en cualquier otra ruta cuenta como duplicado.
    `verifyReleaseApkModel` solo se puede saltar con
    `-Pauratranscribe.allowModelLessReleaseApk=true`, que imprime un WARNING y
    solo cubre artefactos **sin** modelo (una ruta mal ubicada nunca se salta);
    el AAB no tiene opt-out.
  - La decisión APK/AAB vive en dos funciones puras de `app/build.gradle.kts`
    (`packagingTaskSets(taskNames)` y `packagingDecision(taskNames)` → modo
    `apk|bundle|ambiguous|neither`), y la tarea `verifyPackagingDecision` las
    ejerce junto con los gates de modelo sobre **archivos sintéticos**
    (17 invocaciones representativas × 15 archivos: acepta y rechaza en ambas
    direcciones, incluidos rutas anidadas con el sufijo correcto, módulo
    equivocado, duplicados con copia extra y el escape hatch con modelo mal
    ubicado). Es gate duro en ambos workflows y dependencia de `check`, así
    que un cambio en la heurística de nombres de tarea falla antes de que nadie
    publique un artefacto sin modelo. No lo sustituyas por un test que recalcule
    la respuesta esperada por su cuenta: tiene que llamar al mismo código.
  - `scripts/verify_release_artifact.py` es la verificación **independiente**
    del artefacto ya construido (`--kind apk|aab`, hash de contenido contra el
    asset fuente, rechazo de ausente/duplicado/mal ubicado/truncado/corrupto).
    Sus 21 tests (`scripts/test_verify_release_artifact.py`) corren como gate en
    ambos workflows porque son Python puro, sin SDK ni modelo descargado; exigen
    la ruta exacta por tipo de artefacto y que un error al leer una entrada del
    zip falle cerrado con diagnóstico (no con un traceback sin capturar).
  - `downloadModels` se cablea a `preBuild` cuando la invocación produce un
    artefacto de release (y no es una corrida lint/test/`checkModels`), no
    cuando "no contiene la palabra Debug" — que era la condición anterior y
    dejaba sin modelo el APK de release de `assembleDebug assembleRelease`.

### Build Pipeline & Continuous Integration (REGLA GRABADA A FUEGO)

- 🤖 **Compilaciones de Depuración (Debug):** Se realizan SIEMPRE a través de GitHub Actions (`Build Debug APK` workflow), el cual recompila y envía automáticamente el archivo APK de depuración al usuario a través del bot de Telegram.
- 🚀 **Compilaciones de Lanzamiento (Release):** Se realizan SIEMPRE a través de GitHub Actions (`Build Signed Release APK` workflow / `android_release.yml`) mediante tags `vX.Y.Z` o disparadores manuales.
- 🛠️ **Disparador Manual Obligatorio (`workflow_dispatch`):** Todos los workflows de release (`android_release.yml`) deben mantener habilitado `workflow_dispatch` en su bloque `on:` y `tag_name: v${{ env.VERSION_NAME }}` en el paso de publicación. Esto garantiza resiliencia si GitHub Actions sufre un outage o pierde un evento de tag.

### Regla de validación por entorno (2026-08-06, actualizada 2026-10 — REGLA GRABADA A FUEGO)

La regla depende del **tipo de host**, no del proyecto. Averigua primero dónde estás ejecutando:

- 📱 **Host tipo dispositivo móvil / embebido (p. ej. un Android userspace ARM64 sin KVM): NUNCA ejecutar compilaciones pesadas locales.** `./gradlew assembleDebug`, `cargo build`, `cargo ndk ...`, `cargo check` con NDK, `assembleRelease`, etc. sobrecargan el sistema y pueden dejarlo inoperativo (verificado 2026-08-06). En ese host toda validación de compilación se hace **de forma dinámica vía GitHub**: `git commit` + `git push` a `main` dispara el workflow debug (`debug_telegram.yml`), que corre todos los gates — `cargo fmt --check`, `check_translations.py`, `test_send_telegram.py`, `test_verify_release_artifact.py`, `bench_performance.py`, `testDebugUnitTest`, `assembleDebug` (compila el Rust vía cargo-ndk), `lintDebug`, `checkModels`, `checkNativeAlignment`, `verifyPackagingDecision` y `:benchmark:assembleBenchmark` — y envía el APK a Telegram. Se lee el resultado con `gh run list` / `gh run view` y se **itera: arreglar → push → leer CI → repetir**. Se permiten gates ligeros: lectura estática, `git diff`, `cargo fmt --check`, `python3 scripts/check_translations.py`, edición de strings XML.
- 💻 **Equipo físico (portátil/escritorio con SDK Android, NDK, Rust y red): compilar en local es lo esperado y lo preferido.** Aquí la validación real es local, en este orden: `./gradlew :app:testDebugUnitTest` (rápido; no dispara `cargoNdkBuild`) → `python3 scripts/check_translations.py` → `./gradlew :app:lintDebug` → `./gradlew :app:assembleDebug` (usa el `.so` ya construido) → `./gradlew :app:assembleRelease` si el cambio toca R8. En este tipo de host **no** hace falta esperar a CI para saber si compila.
- 🛠️ `gh` está autenticado como el mantenedor; úsalo para inspeccionar runs del CI cuando estés en el host embebido.

### Signing (AGENT-ONLY)

Los tres casos están explícitos en `app/build.gradle.kts` y verificados
(2026-10, host x86_64):

| Estado | `./gradlew :app:assembleRelease` |
|---|---|
| Keystore **+** las 3 env vars (`KEY_ALIAS`, `KEY_PASS`, `STORE_PASS`) | Firma con `signingConfigs.release` → `app-release.apk` firmado (verificado con `apksigner verify`) |
| Keystore **pero** falta alguna env var, en una tarea que construye release | **Falla con error claro** (hardening 2026-08-06): nunca se firma con credenciales por defecto |
| **Sin** keystore | Build correcto → `app-release-unsigned.apk` sin firmar |

**Un build correcto NO es un release publicable.** El caso 3 es el que produce un
artefacto sin firmar: sirve para F-Droid/buildserver, que recompila desde fuentes
y firma él mismo, pero **nunca** se publica desde CI. La separación es explícita:

| Workflow | Qué produce | ¿Publica? |
|---|---|---|
| `debug_telegram.yml` | APK de **debug** firmado con la debug key del repo | No publica un release: sube el APK como *workflow artifact* y lo envía al bot de Telegram |
| `android_release.yml` | APK **y** AAB de release firmados con la clave del proyecto | Sí: GitHub Release con ambos ficheros, solo si todos los gates pasan |
| Build desde fuentes (sin keystore, p. ej. F-Droid) | `app-release-unsigned.apk` sin firmar | No es un canal de este repo; lo firma quien lo consume |

Garantías del workflow de release (no las relajes):

1. **Preflight de credenciales antes de compilar.** Los cuatro secretos
   (`KEYSTORE_BASE64`, `STORE_PASS`, `KEY_ALIAS`, `KEY_PASS`) se comprueban por
   presencia y el job sale `1` si falta alguno. Se comprueba la *presencia*: el
   valor no se imprime jamás (`"${!name:-}"`, nunca `echo $secret`).
2. **Se rechaza el escape hatch del modelo.** El paso `Reject the model-less
   release escape hatch` hace `grep` de `allowModelLessReleaseApk=true` en
   `.github/workflows/` y revisa las `ORG_GRADLE_PROJECT_*` del entorno. La aguja
   se arma con dos mitades (`"allowModelLess""ReleaseApk=true"`) a propósito:
   escrita como un literal, el propio paso se auto-detectaría.
3. **Nunca se firma con la debug key.** Tras `assembleRelease`, el APK se valida
   con `apksigner verify --print-certs` y el paso compara el SHA-256 del firmante
   contra el de `keystore/debug.keystore` (`androiddebugkey`); si coinciden,
   `::error::` y salida `1`. El AAB pasa por el equivalente con `jarsigner -verify
   -certs` + `keytool -printcert -jarfile`.
4. **Un artefacto sin firmar no se publica.** Se rechaza explícitamente un
   `*-unsigned.apk` por nombre, y en el AAB se inspecciona la **salida** de
   `jarsigner` (`jar is unsigned` → error) porque `jarsigner -verify` sale `0`
   incluso con un bundle sin firmar.
5. **Orden correcto.** `zipalign -c -P 16 -v 4` (16 KB de Android 17, no el
   `-c 4` histórico) y `apksigner verify` corren sobre el APK ya empaquetado y
   **antes** de `Upload`/`Create Release`; cualquier fallo corta el job, así que
   el Release no llega a crearse.

> ⚠️ **`set -e -o pipefail` en todo paso que use `| tee`.** Sin `pipefail` el
> estado del paso es el de `tee` (siempre 0): el gate de tests "pasaba" siempre.
> Hubo un caso real de esto en `debug_telegram.yml` (2026-10-09).

- ⚠️ **El `signingConfig` de release sólo se adjunta al build type si el keystore
existe** (`hasReleaseKeystore`). Adjuntar un `signingConfig` cuyo `storeFile` nunca
se asignó hace que AGP aborte en `validateSigningRelease` con *"Keystore file not
set for signing config release"*: eso rompía `assembleRelease` en un clon limpio
**y en el buildserver de F-Droid**, que compila desde fuentes y firma él mismo.
No volver a atar `signingConfigs.getByName("release")` incondicionalmente.
- En CI la keystore es base64-decoded desde un repo secret (`KEYSTORE_BASE64`)
  **después** del preflight y antes de cualquier tarea de release; ver
  `.github/workflows/android_release.yml` §`Decode Keystore`. El fichero
  `release.keystore` resultante ya está en `.gitignore`.
- La keystore de **debug** (`keystore/debug.keystore`, alias `androiddebugkey`,
  password `android`) sí está versionada a propósito: la usa la firma de debug y
  es contra la que el workflow de release compara para rechazar un APK firmado
  con la debug key. Su SHA-256 es
  `437be41f5719ee1e8c75fb2d005683bd97ce3daccaa615fa53eca89e7ef6e46d`
  (verificado contra `apksigner verify --print-certs` de `app-debug.apk`,
  2026-10). No la sustituyas ni la uses para un artefacto distribuible.

### Validación y estilo (Ley de Arneses / Gauntlet de Uncle Bob)

- 🛡️ **La Regla del Guantelete (*The Gauntlet Validation Rule*):** De acuerdo con la filosofía de arnés de pruebas (*Test Harness*) postulada por Robert C. Martin ("Uncle Bob") para el desarrollo con IA, los asistentes de código **no** deben solicitar revisiones manuales línea por línea al usuario. Toda modificación debe someterse a portones automatizados de prueba/compilación y presentar evidencia empírica (`BUILD SUCCESSFUL`) en los registros de salida antes de declarar completada la tarea.
- 🛡️ **Invariantes Algorítmicos y Pruebas Negativas Obligatorias (2026-08-14):** Cualquier optimización o cambio heurístico en motores de coincidencia (corrector fonético, VAD, tokenización, segmentación de audio) debe incluir obligatoriamente suites de pruebas negativas (validar que oraciones/entradas cotidianas no relacionadas produzcan 0 falsos positivos y 0 sustituciones espurias). Se priorizan librerías canónicas auditadas (`strsim`, etc.) con filtros de orden $O(1)$ (e.g. poda rápida por longitud) frente a reimplementaciones matriciales ad-hoc sin cobertura exhaustiva.
- **Soporte Multi-Arquitectura (ARM64 + x86_64):** La aplicación y sus pipelines de construcción deben ser compatibles y validables tanto en dispositivos/máquinas anfitrionas ARM64 como en portátiles/emuladores x86_64.
- **Protección Térmica y de CPU en Móviles:** Las pruebas unitarias locales (`testDebugUnitTest`) se ejecutan aisladas en la JVM sin desencadenar la compilación pesada nativa en Rust (`cargoNdkBuild`), protegiendo el procesador del dispositivo. La generación de binarios APK/AAB completos se realiza en CI/CD (GitHub Actions). **En hosts tipo dispositivo móvil/embebido (regla 2026-08-06, ver "Regla de validación por entorno"), el agente no compila nada localmente: valida únicamente push a `main` + resultado del workflow debug de GitHub Actions.**
- **Anulación de AAPT2 del Sistema (solo host ARM64 Linux):** `android.aapt2FromMavenOverride` está **comentado** en `gradle.properties`. Descoméntalo (apuntando a un `aapt2` real) **únicamente** si estás en un host ARM64 Linux donde el AAPT2 de Maven no puede ejecutarse. Nunca lo dejes activo con una ruta fija: históricamente apuntaba a `/data/data/com.termux/files/usr/bin/aapt2` y en cualquier otro host rompía todas las tareas de recursos.
- **Validación automatizada:** Ejecutar `./gradlew assembleDebug` o suites de unit test sin nuevos advertencias o fallos + smoke test funcional en dispositivo/emulador.
- **Java:** Sigue las convenciones de §4.
- **Rust:** Sigue el estilo inline existente (4 espacios, `rustfmt` por defecto).
- **Gates configurados / evidencia (Guantelete ABIERTO):**
  - `testDebugUnitTest`: **269 tests JVM verdes, 0 fallos, 0 errores** (`BUILD SUCCESSFUL`, medido 2026-10-09 en host x86_64): `ModelAvailabilityTest` (sondeo de modelo fuera del hilo principal: marker vacío, nombre que apunta a un fichero inexistente, nombre con espacios, fichero homónimo en el directorio equivocado, `AssetManager` nulo, `isModelReady` con la rama importada) + markers/subtitle prefs + `CallRegistryTest` (cancelación por owner) + `MarkerAtomicityTest` (lecturas concurrentes) + `FileSha256Test` + `AccessibilityInsertionTest` (inserción de dictado en la posición real del cursor/selección, incluidos selección desconocida `-1`, fuera de rango, invertida y multilínea) + `FileAudioChunksTest` (remuestreo acotado a la región válida, **upsampling** y ratios no enteros 44.1/48/32 kHz → 16 kHz, **garantía de que la salida no aliasa el buffer de chunk reutilizado**, longitudes 2:1/identidad/degeneradas, clamping de `length` fuera de rango, ausencia de NaN/Inf; **`exceedsDurationCap` con el límite exacto, un frame por encima, escalado por sample rate y argumentos inválidos**; unión de transcripciones sin espacios sobrantes, saltando fragmentos vacíos, preservando el orden en 60 chunks y con un solo chunk) + `PostProcessorTest` (suite HTTP P1.3 — payload, `stream:false`, `${output}` una vez, errores, fallback, timeout real de OkHttp por seam con valores escalados — **más DNS fail real con host `.invalid` y connect timeout por seam contra `192.0.2.1`**; los 30 s/60 s de producción se asertan, no se esperan en wall-clock) + `SecureCredentialStoreTest` (crypto, formato `v1:`, tag GCM, verificación de la escritura, borrado que **reporta** si el fichero sobrevivió, y las rutas de fallo de un `KeyProvider` que lanza `RuntimeException`: se convierte en `CredentialStoreException` tipada, sin escribir nada ni caer en texto plano, y el diagnóstico lleva solo el nombre de la clase — nunca el mensaje del provider ni material de la clave) + `CredentialMigrationTest` (orquestación de la migración de la API key: precedencia entre las tres fuentes legadas, write-before-delete, verificación del resultado de `commit()`, recuperación tras Keystore no disponible, sentinel, borrado explícito y **borrado que no se puede completar**: devuelve `false` en vez de mentir y deja la lápida; corre contra `CredentialMigration` con el `SecureCredentialStore` real y dobles en memoria solo para las fuentes prefs) + `CredentialOperationsTest` (la **lane** que serializa toda mutación de credencial: una importación en curso no resucita una clave borrada, subida y borrado concurrentes dejan un estado determinista, un fallo de migración no revive la credencial, una tarea que lanza `RuntimeException` se contiene, se reporta saneada y no bloquea la lane, y — lado lector — encolar una subida y luego leer observa la subida, encolar un borrado deja las lecturas vacías, una lectura con la importación pausada ve el estado *previo* a la mutación y el valor importado después, y las finalizaciones llegan una vez por envío y en orden de envío, incluidos los caminos de fallo) + `CredentialReadTest` (contrato de lectura: una lápida de borrado **oculta todas las fuentes** — store y copia legada incluidas —; un store ilegible se sirve como vacío y **nunca** cae al marker en texto plano; el marker solo se sirve mientras no exista store; el resultado nunca es null). No citar `34`, `109`, `123`, `176`, `234` ni `250` tests: esos números son históricos.
  - **Privacidad (2026-08-04):** el transcript crudo y los detalles de error del post-procesado **no se loguean en release** (gating con `BuildConfig.DEBUG`). No reintroducir logs de texto transcrito sin gate de debug.
  - **Gates CI (actualizado 2026-10-09):**
    - *Ambos workflows, job `validate` (barato, sin descargar el modelo):* `cargo fmt --all -- --check`, `scripts/check_translations.py`, `scripts/test_verify_release_artifact.py`, `testDebugUnitTest` y `:app:verifyPackagingDecision` (este último ejerce la decisión de packaging y los gates de modelo sobre archivos sintéticos, sin descargar un byte del GGUF).
    - *Solo release:* preflight de los cuatro secretos de firma, rechazo del escape hatch del modelo, `lintDebug`, `checkNativeAlignment` (antes del paso de 750 MB, para fallar rápido), `assembleRelease` + `zipalign -c -P 16` + `apksigner verify` + rechazo de debug key + `verify_release_artifact.py --kind apk`, luego `bundleRelease` + `jarsigner`/`keytool` + rechazo de debug key + `verify_release_artifact.py --kind aab`, y sólo después `Upload`/`Create Release`. APK y AAB van en **invocaciones separadas**.
    - *Solo debug (`build`):* `assembleDebug`, `lintDebug`, `checkModels`, `checkNativeAlignment`, `verifyPackagingDecision`, `:benchmark:assembleBenchmark` — todo con `set -e -o pipefail`.
    - Todo paso con `| tee` lleva `pipefail`; sin él un gate rojo se reporta verde (era el estado del gate de tests de `debug_telegram.yml`).
    - Acciones pinneadas a las mismas major que ya usaba el repo (`actions/*@v4`, `dtolnay/rust-toolchain@stable`, `gradle/actions/setup-gradle@v4`, `Swatinem/rust-cache@v2`); `permissions: contents: read` a nivel de workflow y `contents: write` sólo en el job que publica.
  - `checkModels` verifica SHA-256 del asset bundled presente en
    `model_assets/src/main/assets/builtin-model/`; sigue siendo no-op cuando el
    asset falta (para que `check` no dependa de tener 750 MB descargados). El
    no-op es solo sobre el *fuente*: la garantía sobre el artefacto la dan
    `verifyReleaseApkModel` y `verifyReleaseBundleModel`, que sí fallan si el
    APK/AAB no lleva el modelo (ver §3 "Entrega del modelo bundled"). La descarga runtime debug **también** verifica SHA-256 antes de activar `active_model` (P0.3, `FileSha256`), pendiente de smoke en dispositivo.
  - `scripts/check_translations.py` comprueba paridad de nombres en 6 locales alternativos; no detecta todas las cadenas hardcodeadas en Java. P2.4 (2026-08-03): las strings visibles de Java/layouts están migradas a recursos (44 nuevas, gate PASS); excepción documentada para los detalles de error de `PostProcessor` (sin `Context`, seam JVM) y las strings de protocolo JNI que usa la máquina de estados como comparadores.
  - `lintDebug`: es hard gate. **2026-10 (host x86_64, AGP 9.4.1): 0 errores, 16 warnings** (partiendo de 3 errores / 205 warnings). Los 16 restantes son **deliberados y están documentados**; no se han silenciado con `tools:ignore` ni `@SuppressLint` (dos `ApplySharedPref` menos que en el inventario original: los `commit()` síncronos de la migración de la API key ahora comprueban su resultado, que es justo lo que ese aviso pide, así que ya no se dispara). Re-triados contra el código en 2026-10 (mismo conjunto; recuento actualizado tras el pase de la migración de credenciales). Inventario para no volver a investigarlos desde cero: 5 `InflateParams` (vistas cuyo padre real es `WindowManager`, o la vista devuelta por `onCreateInputView()`), 3 `UselessParent` + 1 `MergeRootFrame` (el contenedor «sobrante» es el tap target de pantalla completa, el gutter del scrim o el target de insets al que el listener aplica `setPadding`), 1 `ObsoleteSdkInt` (`mipmap-anydpi-v26`), 2 `ButtonOrder` (una barra de herramientas de IME/overlay, no un diálogo), 2 `UnusedAttribute` (`enableOnBackInvokedCallback` y `isAccessibilityTool`: solo tienen efecto en API 33+/31+ y ahí sí importan), 1 `ChromeOsAbiSupport` (arm64-only por diseño, §5.1) y 1 `TooManyViews` (la pantalla de ajustes). Cada uno lleva su razón en el propio fichero que lo dispara (comentario junto al `<Button>` de Cancel, junto al `LinearLayout` del disclosure, junto a `abiFilters`, en la cabecera de `recognize_activity.xml`/`service_subtitle.xml`, en el doc de `isAccessibilityTool` y en el XML de `mipmap-anydpi-v26`), **excepto los dos `UnusedAttribute` y el `TooManyViews`**: un atributo dentro de la etiqueta `<application>` no admite comentario XML en línea y el recuento de vistas es del layout entero, así que su justificación vive aquí (y en `CHANGELOG.md`). El `ObsoleteSdkInt` es deliberado: `mipmap-anydpi-v26` **no** puede renombrarse a `mipmap-anydpi` — el calificador `-v26` es obligatorio para los XML de adaptive icon y renombrarlo rompe AAPT con `resource mipmap/ic_launcher not found` (re-verificado 2026-10). La configuración de `lint` en `app/build.gradle.kts` se limpió (2026-10) con `abortOnError = true`, `ignoreWarnings = false` y `checkReleaseBuilds = true`. Antes tenía `ignoreWarnings = true` y ~46 checks desactivados en bloque, lo que ocultaba problemas reales (`NewApi`, `MissingPermission`, receivers sin registrar, componentes exportados, texto hardcodeado, accesibilidad de vistas clicables…). Ahora sólo quedan desactivados un puñado de falsos positivos **documentados** en el propio bloque (`GradleDependency`, `AndroidGradlePluginVersion`, `VectorPath`, `AppLinkUrlError` — este último por los filtros `audio/*`, que son mime handlers y no app links — y `Overdraw`). **No añadir un `disable` nuevo sin justificarlo junto al código.**
  - Tests Rust `#[cfg(test)]` en `audio` y `corrector` tienen cobertura de lógica pura mediante crate espejo; el crate cdylib completo sigue bloqueado por `transcribe-cpp-sys v0.1.3` y no debe describirse como ejecutado satisfactoriamente en CI hasta existir un workflow/log reproducible.
  - El plan, los bloqueadores P0 y los criterios de cierre viven en [`GAUNTLETE_PLAN.md`](GAUNTLETE_PLAN.md) y [`.agents/memory/static-audit-debt-2026-08-03.md`](.agents/memory/static-audit-debt-2026-08-03.md).

---

## 4. Convenciones de Código y Estructura

### 4.1 Arquitectura

- **Patrón:** **Puente JNI por módulo Rust ↔ Java/Kotlin Service**. Cada servicio Java (`RecognizeActivity`, `RustInputMethodService`, `LiveSubtitleService`, `VoiceRecognitionService`) tiene su homólogo en `src/<mod>.rs` y expone al menos `initNative` / `cleanupNative` más acciones de alto nivel (`startRecording`, `stopRecording`, `pushAudio`, ...).
- **Estado global:** Rust usa `Lazy<Mutex<..>>` (de `once_cell`) para singletons (engine, sesión IME, sesión recog, estado de subtítulos).
- **Engine compartido:** `Arc<Mutex<Engine>>` con `Condvar` para coordinar cargas concurrentes. Ver patrón completo en `src/engine.rs` (`ensure_loaded_from_thread`).
- **Modelo de procesos: UNO SOLO.** No existe ningún `android:process` en el manifest. Comparte **marcadores en `getFilesDir()`** — *no* `SharedPreferences` — porque el motor Rust sólo lee del *filesystem* y los ajustes deben estar disponibles sin importar qué componente arrancó primero (el IME puede existir antes que `MainActivity`).

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
| `hardware_backend` | contenido = `cpu` **siempre**. `src/engine.rs` lee el marker y **sólo lo loguea**: no hay ninguna ruta NNAPI/Vulkan en el motor nativo. `ModelsActivity` ya no ofrece NPU/GPU y normaliza a `cpu` cualquier valor antiguo — no reintroducir opciones de UI sin implementación real detrás |
| `custom_words` | contenido = términos correctos, uno por línea (líneas `#` = comentarios); ausente/vacío = corrección fonética desactivada |
| `subtitle_translation_target` | contenido = `auto` (idioma original, sin traducción — decisión de producto) o un locale BCP-47 (`es-ES`, `en-US`, `fr-FR`, `de-DE`, `it-IT`, `pt-PT`, `ru-RU`) → traducción on-device de subtítulos **finalizados** (ML Kit, paquetes vía Google Play Services; fallback siempre al texto original). Lo lee `LiveSubtitleService` al iniciar sesión; no requiere recarga del engine |

Bindings típicos (ver `MainActivity.bindMarkerSwitch`): un `CompoundButton` cuja presencia del file representa el estado.

La **clave de API del post-procesado** es la única excepción a los markers planos: se almacena cifrada en reposo (AES-256-GCM, formato versionado `v1:<iv>:<ciphertext>` en `pp_api_key_enc`) con una clave generada y retenida en **AndroidKeyStore** (`SecureCredentialStore` + `AndroidCredentialKeyProvider`), nunca en texto plano ni en Base64. El marker legado `pp_api_key` (Base64) y las copias antiguas de SharedPreferences/EncryptedSharedPreferences se migran al arrancque con *verify-before-delete*: primero se escribe y verifica el cifrado, y solo entonces se borra la copia legada; si la migración falla (p. ej. Keystore no disponible) la credencial legada se conserva y se reintenta en un arranque posterior, sin degradar nunca a almacenamiento en claro. Esa orquestación (precedencia entre las tres fuentes, write-before-delete, sentinel y reintentos) vive en `CredentialMigration` sobre un seam `LegacySources` inyectable — cubierta por tests JVM (`CredentialMigrationTest`); `SettingsManager` solo aporta la implementación Android. El sentinel `pp_api_key_migrated` **vacío** significa «comprobado, nada que importar» y **no** bloquea una copia legada que aparezca después (p. ej. escrita por un build downgraded); el contenido `deleted` es una lápida que solo escribe el borrado explícito cuando alguna copia —el store cifrado incluido— no se pudo eliminar de verdad, y prohíbe reimportarla. **Serialización (2026-10):** toda mutación de la credencial (importación de arranque, guardado y borrado) pasa por una única *lane* (`CredentialOperations`, hilo `credential-ops`), nunca por el hilo llamante ni por el main thread: el orden de operaciones es el de envío, así que un borrado enviado después gana sobre una importación ya en curso (antes podía resucitar la clave) y un guardado enviado después gana sobre el valor importado. `SettingsManager.setApiKey(key, executor, callback)` es por eso **asíncrono**: entrega el resultado durable por callback y la pantalla de ajustes solo canta «guardado» y cierra cuando la operación ha terminado de verdad (si falla, muestra el error y no cierra). Un fallo de Keystore estático (p. ej. `IllegalStateException` de un provider en mal estado) se convierte en `CredentialStoreException` en la frontera cripto del store, no con un `catch (Throwable)` global; un guardado fallido **no** se reporta como correcto ni deja la clave en claro, y un borrado que no pudo eliminar el fichero devuelve `false` en vez de decir que borró. **Lecturas (2026-10):** la lectura es **asíncrona y fuera del hilo llamante**. `SettingsManager.readApiKey(executor, callback)` encola en la lane `credential-ops` (`CredentialOperations.submitValue`) el mismo camino que antes hacía `getApiKey()` — estado en disco + `CredentialRead` — y entrega un `ApiKeyRead` (`LOADED` / `ABSENT` / `UNREADABLE`) por callback; `readPostProcessEnabled(executor, callback)` hace lo propio con el switch. Ningún llamante hace I/O de credencial, lectura de la lápida ni descifrado con AndroidKeyStore en su propio hilo (el main thread para todas las superficies de UI). Los accesores síncronos (`getApiKey`, `isPostProcessEnabled`, `isPostProcessConfigured`) se eliminaron; queda `isPostProcessSwitchedOn()` como sonda **barata** (marker + provider disponible, sin I/O de credencial) para decidir si merece la pena pedir el post-procesado. `PostProcessor.process()`/`fetchModels()` hacen la lectura ordenada y **solo después** construyen la petición: una importación legada aún en curso nunca se lee como «no configurado», y una credencial sin leer (UNREADABLE) se reporta como tal en vez de como ausencia — en ambos casos sin tocar la red (la transcripción cruda se entrega igual, exactamente una vez). La lectura va **antes** de la petición, así que ninguna llamada de red usa una credencial de otro estado de la cola, y una superficie destruida durante la lectura (el callback asíncrono llega tarde) ya no lanza la petición: `PostProcessor` comprueba el validador de ciclo de vida antes de encolar en OkHttp. `CredentialRead` (puro, cubierto por `CredentialReadTest`) no decide nada por su cuenta: solo recibe el estado en disco. La lápida `deleted` **manda sobre todas las fuentes**, así que un borrado es visible para cualquier lector desde el instante en que se escribe — `forget()` la escribe **antes** de borrar nada (antes la escribía después, y en esa ventana el store ya no estaba pero el marker Base64 sí: una lectura devolvía la clave que el usuario acababa de borrar); y como la lápida también manda cuando una copia no se pudo eliminar, una clave borrada no se puede leer ni desde el store que sobrevivió ni desde una copia legada. El orden entre lecturas y mutaciones es **por finalización correcta, no por encolado**: una mutación en curso aún no es visible (estado definido y documentado), y por eso todo llamante que deba observar su propia mutación espera el callback de finalización — la pantalla de ajustes lo hace al guardar, al refrescar la lista de modelos y al probar la conexión — en vez de releer; un llamante que espera una lectura concreta la encola **después** de su mutación, de modo que la lane la ejecuta detrás y observa su resultado; a la inversa, `store()` limpia la lápida en la misma operación que la escritura verificada, para que una clave nueva no quede oculta por un borrado anterior, y `migrate()` que encuentra una lápida **termina el borrado** en vez de considerar el store descifrable como "el usuario guardó una clave nueva". Solo Java lee la clave (cabecera `Authorization` de OkHttp); **Rust no tiene ninguna referencia a ella**. El cifrado en reposo protege ante extracción offline de los datos de la app; NO protege en un dispositivo rooteado/comprometido mientras la app la usa (la clave debe existir en memoria para construir la petición) — ver el threat model en la cabecera de `SecureCredentialStore`. Borrar el marker no garantiza el borrado físico en flash (FTL/wear-levelling).

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
- **Cancelación/lifecycle:** la cancelación es **por propietario** (`PostProcessor.cancelAllFor(owner)`, identidad de la Activity/Service dueña de la llamada; registro en `CallRegistry`): destruir una superficie o cancelar un reconocimiento nunca cancela una petición legítima de otra superficie. `PostProcessor.cancelAll()` (global) queda reservado para eventos realmente globales: toggle PP-off (con broadcast `CANCEL_ACTION`, que el IME escucha) y muerte del servicio IME. Los validadores de Activity/IME impiden callbacks tardíos sobre componentes destruidos, y el `Response` de OkHttp se cierra siempre, incluidos errores y parseos fallidos.
- **Traducción de subtítulos (fork addition, contrato AGENT-ONLY):** el modelo bundled (Nemotron) **no traduce**; la traducción es una etapa Java-side exclusiva de subtítulos sobre segmentos **finalizados** (`LiveSubtitleService` + `OnDeviceSubtitleTranslator` con ML Kit). El destino vive en el marker `subtitle_translation_target` (`auto` = idioma original; locale fijo = traducir) y lo lee el servicio al iniciar sesión — **no** reutilizar `model_translate` (eso es ASR-global y **nunca** debe aplicar a subtítulos: `engine::transcribe_subtitle` fuerza `Task::Transcribe`). Cola FIFO serial (máx. 8), resultados aplicados en orden en main thread, fallback **siempre** al texto original (sin Play Services, sin paquete, error, saturación), generación de sesión para callbacks tardíos, y origen automático resuelto por script del texto (`SourceLanguageResolver`) cuando `model_language` no es fijo.

### 4.7 Rust: contratos del singleton Engine (no romper)

- **`engine::get_engine()` → `Option<Arc<Mutex<Engine>>>`**: compartido por todos los componentes Java (un único proceso). Garantías: panic-catching, recovery de Mutex poison.
- **`engine::ensure_loaded*`**: idempotente, multi-thread safe, espera via `Condvar` si otra thread está cargando, reintenta tras `Failed(_)`.
- **`Engine::transcribe`** divide audio largo en el punto más silencioso (`audio::find_quietest_split`); une los textos con un espacio.
- **`Engine::run` re-lee `model_language` en CADA llamada** (issue v0.1.20→21). Esto es lo que hace que el cambio de idioma en el spinner de `ModelsActivity` aplique al instante en todas las superficies (incluido el IME, que puede estar vivo antes que la Activity) sin recarga manual del modelo. **No cachear el idioma dentro del Engine** — ese fue el bug original.

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

- **Tema base:** `Theme.Material3.DayNight.NoActionBar` + `DynamicColors.applyToActivitiesIfAvailable(this)` en `App.onCreate` (Material You desde Android 12+). El `AppTheme` **no sobrescribe ningún color**: se usa el esquema tonal de Material 3 tal cual (antes se pisaban sólo `colorPrimary`/`colorOnPrimary`, lo que dejaba el resto de roles en la baseline y producía una paleta a medias). Todas las superficies usan atributos `?attr/color*`; **no añadir literales hex** a layouts ni drawables. Las únicas excepciones documentadas son los tokens semánticos sin rol M3 (`status_ok`) y los scrims de los overlays que se dibujan sobre contenido de terceros (`subs_scrim*`, `overlay_scrim*`), que a propósito mantienen el mismo valor en claro y oscuro.
- **Targets táctiles:** mínimo **48dp × 48dp** efectivos en todo control interactivo (las teclas del IME, cerrar, cancelar, overlay, subtítulos). El glifo puede seguir a 24dp; el contenedor no. No usar `android:minWidth/minHeight="0dp"` para encoger un botón por debajo del mínimo.
- **IME:** `RustInputMethodService` no es `AppCompatActivity` (es un `InputMethodService`, en el mismo proceso que el resto de la app). Construye su propio contexto theme-aware con `ThemePrefs.wrapForNight` + `DynamicColors.wrapContextIfAvailable` para que la vista coincida con el ajuste de tema de la app principal.
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
- **APK y AAB se publican los dos, pero en invocaciones separadas.** El workflow de release construye ambos (`assembleRelease` → `Aura_Transcribe_vX.Y.Z.apk`; `bundleRelease` → `Aura_Transcribe_vX.Y.Z.aab`) y crea un único GitHub Release con los dos ficheros. Lo que **no** puede hacerse es pedirlos en la misma llamada de Gradle: el APK necesita el GGUF en el módulo base y el AAB sólo en el asset pack `:model_assets` (`dynamicDelivery` install-time, ver `model_assets/build.gradle.kts`), así que `app/build.gradle.kts` hace fallback añadiendo `model_assets/src/main/assets/` a `assets.srcDirs` solo cuando NO es bundle, y una invocación ambigua falla al configurar. Ver "Entrega del modelo bundled" en §3.

---

## 5. Reglas para Agentes de IA

### 5.1 EVITAR (cosas que romperían el proyecto)

- ❌ **No cambiar la firma de los call-backs JNI** listados en §4.4 sin actualizar cada superficie Java (`RecognizeActivity`, `RustInputMethodService`, `LiveSubtitleService`, `VoiceRecognitionService`).
- ❌ **No cachear el `model_language`** dentro de `Engine` o entre llamadas. El comportamiento intencional es leer el marker file en cada `Engine::run`, de modo que cambiar el idioma en `ModelsActivity` aplique de inmediato en todas las superficies (IME, popup, subtítulos, servicio) sin recargar el modelo. Ese fue el bug crítico de v0.1.20 → v0.1.21.
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
- ❌ **No volver a leer la credencial de forma síncrona desde un hilo llamante (menos aún el main thread).** Nada de un `getApiKey()`/`isPostProcessConfigured()` síncrono: el I/O de fichero, la lectura de la lápida y el descifrado con AndroidKeyStore van en la lane `credential-ops` vía `SettingsManager.readApiKey`, que además es lo que ordena la lectura detrás de la importación legada de arranque. Una lectura **pendiente** no es una credencial ausente: no la reportes como «no configurado» ni lances la petición con una credencial de la nada.
- ❌ **No escribir `pp_default_prompt` (ni ningún `<string>` largo) con saltos de línea literales.** AAPT2 los colapsa a un solo espacio: rompe la UI del prompt y aplana el payload que guía al LLM a refinar. Usa escapes `\n` (ver §4.6).
- ❌ **No generar compilaciones de release firmadas fuera del pipeline oficial de GitHub Actions.** Las de depuración llegan al usuario vía GitHub Actions + bot de Telegram, y las firmadas vía `android_release.yml`. **Compilar en local para validar sí está permitido y es lo esperado** en un host de desarrollo real (portátil/escritorio), siempre que no se publique el artefacto ni se firmen releases a mano. Ver "Regla de validación por entorno" en §3.
- ❌ **No borrar el comentario explicativo en app/build.gradle.kts sobre `assetPacks` + bundle-vs-APK.** Es la trampa que rompió v0.1.x del upstream y se documentó específicamente para evitar.
- ❌ **No introducir tests automatizados que importen el módulo entero** a través de JNI en CI: no hay emulador arm64 en el runner de GitHub Actions. La validación en CI es build + lint + tests JVM; el smoke test funcional es manual en dispositivo. Los tests de instrumentación (si se añaden) se compilan pero **no** se ejecutan en CI.

### 5.2 HACER (reglas positivas al añadir código)

- ✅ Si añades un call-back JNI nuevo, documéntalo con la firma exacta en este `AGENTS.md` (§4.4) y añade un stub no-op en Java por defecto para no romper builds en los que aún no has cableado el lado Java.
- ✅ Cada nuevo ajuste del usuario va como **marker file en `filesDir()`**, sin excepción. **La API key es la única excepción al formato plano**: se persiste cifrada vía `SecureCredentialStore` (AES-256-GCM + AndroidKeyStore); no usar Base64 como "protección" ni escribir jamás la clave en claro como fallback si el Keystore falla (`setApiKey` entrega `false` por callback y la UI lo muestra, sin cerrar la pantalla ni fingir que guardó). Toda escritura de la clave va por la lane `CredentialOperations` (§4.5); no escribir el marker cifrado desde fuera de esa lane. Un ajuste nuevo no sensible sigue siendo un marker simple.
- ✅ Toda escritura de markers pasa por `MarkerFileHelper` (temp **único por escritura** + fsync + rename; delete en vacío). Nunca `FileOutputStream` directo sobre el nombre final: escritores concurrentes que compartan el mismo `*.tmp` pueden exponer contenido parcial (cazado por `MarkerAtomicityTest`). Hay varios componentes (Activity, IME, servicios) que escriben los mismos ajustes, así que sigue siendo obligatorio aunque todo corra en un solo proceso.
- ✅ Antes de añadir cadenas visibles, duplicarlas en los 7 locales. Si añades una cadena que **no** quieres traducir, márcala con `translatable="false"`.
- ✅ Si tocas el `Engine`, lee primero `src/engine.rs` completo (~370 líneas, muy comentado): decisiones sobre warm-up, re-read de idioma, fallback a modelo bundled, warnings de capabilities (`supports_translate`/`variant().contains("turbo")`), Whisper `temperature_inc`, todo está allí por una razón documentada.
- ✅ Si añades un nuevo surface (Activity/Service), recuerda:
  1. Bloque `static` con `c++_shared` + `android_transcribe_app`.
  2. Método nativo declarado en Java + implementación en Rust con prefijo `Java_dev_notune_transcribe_<ClassName>_`.
  3. Llamadas JNI con `get_java_vm`, `new_global_ref` y release apropiado en `cleanupNative`.
  4. Los ajustes van por marker files (nunca `SharedPreferences`), porque el motor Rust los lee del *filesystem*. **No** añadas `android:process`: la app es de un solo proceso a propósito (§1).
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
3. **Manifest**: añadir `<service android:name=".FooService" .../>`. **No** añadas `android:process` — la app es de un solo proceso a propósito (§1). Si el servicio captura audio, declara `android:foregroundServiceType="microphone"` **y** pide `RECORD_AUDIO` antes de llamar a `startForeground`.
4. **lib.rs**: `pub mod foo;`.

Crear un nuevo ajuste toggle en UI → marker file en `filesDir()` con un nombre `snake_case`; bindearlo con `bindMarkerSwitch(...)` en `MainActivity` (patrón ya existente). Propagar cadena a los 7 locales.

### 5.4 Known limitations (leer antes de "arreglar" o "documentar" estas cosas)

Estado real y verificado, con la evidencia que lo respalda. **No** describas
ninguno de estos puntos como si funcionara, y no los "arregles" a medias.

1. **El post-procesado on-device (S1-mini) NO está implementado y, además, no
   es alcanzable desde la UI.** Doble barrera, ambas verificadas:
   - **No hay motor de inferencia.** El único backend del árbol es
     `transcribe-cpp 0.1.3`, cuya API pública es *speech recognition*
     (`Model::load` / `Session::run(pcm: &[f32], RunOptions{ task: Transcribe |
     Translate, … })`): no existe ningún entry point de generación de texto (ni
     decode con prompt, ni sampler, ni encoder de tokenizer). S1-mini es un LM
     causal tipo Qwen y necesitaría llama.cpp (o equivalente) + tokenizer BPE +
     KV cache, nada de lo cual envía el proyecto. Por eso
     `src/post_processor.rs::normalize_text_on_device` es un **placeholder**:
     valida que el GGUF exista, construye el prompt real con
     `aura_core::normalizer::build_s1_prompt` y lo **descarta**.
   - **La UI ya no lo ofrece como si funcionara.**
     `SettingsManager.LOCAL_S1_INFERENCE_AVAILABLE = false` hace que
     `isProviderAvailable("local_s1")` sea `false`: el provider se muestra como
     *«unavailable in this build»*, el switch no se puede activar, el botón de
     descarga se oculta y un marker obsoleto se responde con
     `PostProcessor.LOCAL_S1_UNAVAILABLE_ERROR` en vez de devolver el texto
     simulado. `pack_ultralight_desc` se reescribió en los 7 locales para dejar
     de prometer el refinado con S1-mini.
   Si algún día se implementa la inferencia, hay que voltear **juntos**
   `LOCAL_S1_INFERENCE_AVAILABLE`, el doc-comment de `normalize_text_on_device`
   y este punto. `is_loaded` significa "se vio una ruta válida", no "los pesos
   están en RAM".
2. **Con la puerta cerrada (`LOCAL_S1_INFERENCE_AVAILABLE = false`), R8 elimina
   del release TODA la superficie JNI del provider on-device:**
   `nativeNormalizeOnDevice`, `nativeSetS1ModelPath`, `nativeUnloadS1` y
   `nativeIsS1Loaded`. Verificado leyendo la tabla de strings de
   `app/build/outputs/mapping/release/mapping.txt` y del `classes.dex` del APK
   de release (2026-10): la constante `static final false` pliega la rama, y R8
   borra también sus call sites (`processOnDeviceInternal`), así que no queda
   ninguna llamada viva que pueda lanzar `UnsatisfiedLinkError` — ese
   emparejamiento (caller y callee fuera a la vez) es la garantía real, no la
   presencia del símbolo. `nativeTrimMemory` **sí** sobrevive, porque
   `App.onTrimMemory` lo llama de verdad. Las cuatro funciones Rust siguen
   existiendo en el cdylib y las declaraciones Java siguen en el código: son la
   API del ciclo de vida para cuando exista motor de inferencia (punto 1). No las
   borres por estar "sin usar" en el DEX de release.
3. **Instrumentación, Macrobenchmark y Baseline Profile NO se han ejecutado**
   nunca: no hay dispositivo/emulador arm64. El módulo `:benchmark` **compila**
   (`:benchmark:assembleBenchmark`) pero sus tests son de dispositivo y están
   fuera de CI a propósito. No presentes una Baseline Profile como generada.
4. **`assembleRelease` y `bundleRelease` ya no se pueden pedir juntos** (antes
   producían en silencio un APK de 32 MB sin modelo; ver §3 "Entrega del modelo
   bundled"): la invocación combinada falla al configurar, y los gates
   `verifyReleaseApkModel`/`verifyReleaseBundleModel` abren el artefacto real y
   se niegan (verificado 2026-10 en las cuatro combinaciones: solo APK, solo AAB,
   combinada y modelo ausente). No lo "arregles" volviendo a un único source set
   para ambos: entregaría el modelo dos veces en el AAB.
5. **Los tests Rust `#[cfg(test)]` de `audio`/`corrector` corren sólo en el crate
   espejo**; el cdylib completo sigue bloqueado por `transcribe-cpp-sys v0.1.3`.
6. **Resuelto (2026-10): las dos lecturas de disco del arranque de
   `MainActivity` ya NO están en el hilo principal.** Antes
   `maybeDownloadDebugModel()` (invocado desde `onCreate`/`onResume`) llamaba a
   `hasImportedModel()` (marker `active_model`) y a
   `hasBundledModel()` (`getAssets().list("builtin-model")`) de forma síncrona, y
   StrictMode en debug lo reportaba. Ahora los dos sondeos viven en
   `ModelAvailability` (sin `Context`, sin vistas) y corren en un hilo
   `"model-probe"`; el resultado vuelve por `runOnUiThread` con
   comprobación de `isFinishing()/isDestroyed()` y de una **generación**
   (`modelProbeGeneration`), de modo que un resultado obsoleto no puede
   sobrescribir el estado nuevo, y un flag `modelProbeInFlight` evita repetir el
   sondeo en el par `onCreate`+`onResume` del arranque en frío. Mientras corre,
   el `TextView` de estado muestra `status_checking` ("Checking assets…", que ya
   era su valor por defecto en el layout) y el motor lo sobrescribe con "Ready"
   al terminar `initNative`. Cobertura: `ModelAvailabilityTest` (10 tests JVM,
   incluidos marker vacío, nombre apuntando a un fichero inexistente y nombre en
   el directorio equivocado). El comportamiento de detección es el mismo que
   antes (`exists()`, no `isFile()`); lo único que cambió es el hilo desde el que
   se ejecuta. **No** volver a llamar a los sondeos directamente desde el hilo
   principal.
7. **Orden obligatorio del FGS `mediaProjection`** (Android 14+): el Activity
   llama a `createScreenCaptureIntent()` y el usuario concede → el servicio llama
   a `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` → **sólo
   después** `MediaProjectionManager.getMediaProjection()`. Al revés lanza
   `SecurityException` ("Media projections require a foreground service of type
   …") y `LiveSubtitleService` lo convertía en un `stopSelf()` silencioso.
   Documentado en `developer.android.com/about/versions/14/changes/fgs-types-required`.

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
- Ajustes = marker files en `filesDir`. La clave API NO va en claro: se guarda cifrada (`pp_api_key_enc`, AES-256-GCM + AndroidKeyStore); el marker `pp_api_key` en Base64 es solo el formato legado que la migración reemplaza (verify-before-delete, sin fallback a texto plano).
- **La credencial se lee de forma asíncrona en la lane `credential-ops`** (`SettingsManager.readApiKey` / `readPostProcessEnabled`): ningún componente hace I/O de credencial ni descifrado en el hilo principal, y «aún no leída» (importación legada pendiente) **nunca** significa «no configurado». Al decidir si post-procesar usa `isPostProcessSwitchedOn()` (barato) y deja el veredicto de la credencial a `PostProcessor`, que lee ordenado antes de la petición.
- Post-procesado: opcional, *safe-fallback* obligatorio al texto crudo.
- Idioma se re-lee en cada `Engine::run` (no cachear en memoria dentro del engine).
- Subtítulos usan un modelo de partial/final con lag-policies calibradas; subir esos números rompe el contrato.
- Bug histórico a evitar: v0.1.20 → v0.1.21 (idioma cacheado en el engine). La regla "re-read on every run" está escrita en muchos comentarios a propósito.
- **La app es de UN SOLO PROCESO.** No hay `android:process` y no debe haberlo (§1): el IME y el popup comparten un mismo `cpal::Stream` y el engine global.
- **Android 17 es API 37** (Android 15 es API 35). `compileSdk`/`targetSdk` = 37, AGP 9.4.1, Gradle 9.8.0, NDK 28.2.13676358. No describas API 35 como "Android 17".
- Release con R8 + resource shrinking activos; si tocas reflexión, JNI o nombres invocados por cadena, actualiza `app/proguard-rules.pro`.
- Targets táctiles ≥ 48dp y colores siempre vía `?attr/color*` (sin literales hex en layouts/drawables).
- Antes de PR: `./gradlew :app:testDebugUnitTest`, `python3 scripts/check_translations.py` y `./gradlew :app:assembleDebug` deben pasar limpios, y las cadenas nuevas deben estar en los 7 locales.
- **Validar builds por entorno (2026-08-06, actualizado 2026-10):** en un host tipo dispositivo móvil/embebido NUNCA compiles localmente — `git push` a `main` y lee el workflow debug de GitHub Actions con `gh run list`/`gh run view`. En un equipo físico real (portátil/escritorio con SDK, NDK, Rust y red) **sí debes compilar en local**; es la vía de validación preferida.
