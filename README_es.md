# Aura Transcribe

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--37)-blue.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](#requisitos-y-compatibilidad)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

[English](README.md) · Español · [Deutsch](README_de.md) · [Français](README_fr.md) · [Italiano](README_it.md) · [中文](README_zh.md)

El código fuente de la aplicación se distribuye bajo la licencia MIT. Los componentes de terceros mantienen sus propias condiciones y el modelo de voz Nemotron incluido **no** es MIT: se distribuye bajo el OpenMDW License Agreement 1.1 (OpenMDW-1.1). Consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) y [MODEL_LICENSE.md](MODEL_LICENSE.md).

Aura Transcribe es una aplicación de voz a texto en el dispositivo y un servicio de entrada de voz para Android. Impulsada por Rust, optimizaciones SIMD ARM NEON y kernels de inferencia GGML, realiza el reconocimiento de voz localmente en la CPU del dispositivo sin necesidad de conexión de red activa.

La aplicación funciona en varios puntos de integración de Android: dictado por voz en teclados estándar, un editor de método de entrada (IME) independiente, una.overlay flotante de dictado, subtítulos en vivo del audio del sistema y transcripción offline de archivos de audio.

## Capturas

| Inicio | Grabación | Subtítulos en vivo |
| --- | --- | --- |
| ![Pantalla de inicio](.screenshots/screenshot_home.png) | ![Pantalla de grabación](.screenshots/screenshot_recording.png) | ![Subtítulos en vivo](.screenshots/screenshot_subtitles.png) |

---

## Características principales

- **Reconocimiento de voz en el dispositivo:** la transcripción corre 100% en local con un modelo cuantizado [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) (Q5_K_M GGUF). Las grabaciones de audio nunca salen de tu dispositivo.
- **Dictado por voz en todo el sistema:** responde a `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` de Android para mostrar un panel de dictado inferior en teclados compatibles (como Microsoft SwiftKey) y campos de búsqueda por voz del navegador.
- **Teclado de voz dedicado (IME):** incluye un teclado de voz independiente para dictar con un toque en cualquier app mediante el cambio estándar de método de entrada.
- **Servicio de reconocimiento de voz:** implementa la API del sistema `SpeechRecognizer` para integraciones de teclados y apps de terceros.
- **Overlay flotante de dictado:** burbuja de micrófono arrastrable (`SYSTEM_ALERT_WINDOW`) para dictar desde cualquier pantalla. Si se activa, un Servicio de Accesibilidad opcional (`FloatingDictationAccessibilityService`) inserta el texto transcrito directamente en el campo enfocado de la app activa. Sin acceso de accesibilidad, el texto se copia al portapapeles.
- **Subtítulos en vivo en tiempo real:** subtitula streams de audio interno del dispositivo (pódcasts, multimedia, videollamadas) en local vía MediaProjection, con traducción opcional en el dispositivo de los subtítulos finales usando Google ML Kit.
- **Transcripción de archivos de audio:** transcribe archivos de audio pregrabados directamente en el dispositivo.
- **Enrutado de audio multidispositivo:** detecta y enruta audio de micrófonos internos, interfaces USB y auriculares Bluetooth (SCO y BLE Audio) con cambio automático y overrides manuales.
- **Cobertura de idiomas:**
  - **Interfaz de usuario:** disponible en 8 idiomas (inglés base, alemán, español, francés, italiano, portugués, ruso y chino simplificado).
  - **Reconocimiento de voz:** el modelo Nemotron integrado soporta 40 locales de idioma nativamente con autodetección en streaming; 33 locales regionales son seleccionables directamente. Modelos GGUF importados por el usuario (p. ej. Whisper) pueden soportar hasta 99 idiomas.
- **Post-procesado de texto opcional:** formateo y limpieza configurables con proveedores externos compatibles con la API de OpenAI (OpenAI, Groq, Cerebras, OpenRouter, Mistral, Together, Ollama) o el preset intacto *Verbatim*.

---

## Privacidad y comportamiento del procesamiento

- **El reconocimiento de voz es 100% local:** toda la inferencia acústica, el procesado de audio y la transcripción se ejecutan íntegramente en tu dispositivo. El micrófono y los archivos de audio nunca se suben a servidores remotos.
- **Post-procesado remoto opcional:** el post-procesado por IA está **desactivado por defecto**. Si activas un proveedor externo (como Groq u OpenAI), solo el **texto** reconocido (nunca el audio) se transmite a tu endpoint configurado (HTTPS para proveedores remotos; HTTP en claro solo a loopback localhost/127.0.0.1 para un servidor Ollama local). Las API keys se guardan localmente en el almacenamiento privado de la app.
- **Modos Verbatim y offline:** con el post-procesado apagado o en *Verbatim*, ningún texto sale por la red.
- **Telemetría y logs en release:** en builds release, el registro del texto transcrito y de detalles sensibles de errores está eliminado.

---

## Requisitos y compatibilidad

### Dispositivos Android soportados
- **Sistema operativo:** Android 8.0 (API 26) hasta Android 17 (API 37).
- **ABI objetivo:** solo `arm64-v8a`, alineado a páginas de memoria de 16 KB.
- **Procesador:** CPU ARMv8.2-A con extensiones vectoriales `dotprod` y `fp16`. No soportado en ARM de 32 bits ni x86/x86_64.

### Toolchain de compilación
- **JDK:** 17 (LTS)
- **Android SDK:** plataforma `android-37` (Android 17)
- **Android NDK:** `28.2.13676358`
- **Rust Toolchain:** Rust `1.78.0+` (edición 2021) con target `aarch64-linux-android`
- **cargo-ndk:** última release (`cargo install cargo-ndk`)
- **CMake y Ninja:** requeridos para dependencias nativas

La compilación cruzada funciona en hosts `linux-x86_64`, `linux-aarch64`, `darwin-x86_64`, `darwin-arm64` (Apple Silicon) y Windows.

---

## Uso básico

### 1. Dictado por voz emergente
1. Abre Aura Transcribe y concede el permiso de micrófono.
2. En cualquier app, toca la tecla de micrófono de un teclado compatible o un campo de búsqueda por voz.
3. El diálogo inferior transcribe en tiempo real e inserta el texto final al completar.

### 2. Teclado de voz dedicado (IME)
1. Activa Aura Transcribe en **Ajustes de Android → Sistema → Idiomas e introducción → Teclados en pantalla**.
2. Cambia a Aura Transcribe con la tecla de globo/cambio de tu teclado activo.
3. Toca la superficie de grabación para hablar; el texto se escribe directamente en el campo activo.

### 3. Overlay flotante de dictado
1. Inicia el overlay desde la pantalla principal y concede el permiso de overlay (`SYSTEM_ALERT_WINDOW`).
2. Toca la burbuja flotante para iniciar y detener el dictado.
3. Para inserción directa en inputs de otras apps, activa el Servicio de Accesibilidad opcional en **Ajustes de Android → Accesibilidad**. La app muestra una explicación antes de llevarte a ajustes del sistema. Si está desactivado, el texto se copia al portapapeles.

### 4. Subtítulos en vivo
1. Toca **Iniciar subtítulos en vivo** en la pantalla principal.
2. Acepta el diálogo estándar de MediaProjection (*"¿Empezar a grabar o transmitir?"*) para subtitular el audio interno.

---

## Compatibilidad con teclados

| Teclado | Modo de compatibilidad | Notas |
| :--- | :--- | :--- |
| **Microsoft SwiftKey** | Speech Intent Popup | Abre el panel inferior directamente. *(Desactiva "Escritura de voz multimodal" en ajustes de SwiftKey)* |
| **AnySoftKeyboard** | Speech Intent Popup | La tecla de micrófono abre el panel inferior. |
| **HeliBoard / FlorisBoard** | IME Switcher | La tecla de micrófono cambia directo al teclado de voz de Aura Transcribe. |
| **FUTO Keyboard** | IME Switcher | Cambia a Aura Transcribe con la tecla de globo. |
| **Fossify / OpenBoard** | IME Switcher | Cambia al teclado de voz de Aura Transcribe. |
| **Gboard** | System IME Switcher | El botón de micrófono de Gboard está ligado a Google; cambia a Aura Transcribe con el conmutador del sistema. |

---

## Compilar desde el código

### 1. Clonar el repositorio
```bash
git clone https://github.com/marodriguezd/Aura-Transcribe.git
cd Aura-Transcribe
```

### 2. Descargar y verificar los modelos incluidos
El modelo Nemotron GGUF incluido se verifica por SHA-256 antes de empaquetar:
```bash
./gradlew downloadModels
./gradlew checkModels
```

### 3. Compilar las librerías nativas Rust
Compila el `cdylib` y los bindings JNI para `arm64-v8a`:
```bash
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release
```
*(Nota: `./gradlew assembleDebug` lo invoca automáticamente si falta la librería `.so`.)*

### 4. Ensamblar el APK de debug
```bash
./gradlew assembleDebug
```
El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

### 5. Ejecutar las puertas de verificación
```bash
./gradlew :app:testDebugUnitTest        # Tests unitarios JVM (no compila la librería nativa)
./gradlew :app:lintDebug                # Checks de Android Lint
./gradlew :app:checkModels              # SHA-256 del modelo incluido
./gradlew :app:checkNativeAlignment     # Alineación a páginas de 16 KB de cada .so
./gradlew :app:verifyPackagingDecision  # Decisión APK/AAB + puertas del modelo, en archivos sintéticos
./gradlew :benchmark:assembleBenchmark  # Compila el módulo Macrobenchmark (sin dispositivo)

python3 scripts/check_translations.py          # Paridad de traducciones en los 8 locales
python3 scripts/test_verify_release_artifact.py # Autotest del verificador del artefacto release
python3 scripts/bench_performance.py           # Micro-benchmarks de algoritmos de referencia
cargo fmt --all -- --check                     # Formato Rust
```

`verifyPackagingDecision` y `test_verify_release_artifact.py` son las dos puertas
que protegen la entrega del modelo incluido: corren sin descargar el modelo de
560 MB, porque ejercitan la lógica de decisión y el verificador contra archivos
sintéticos. `bench_performance.py` mide stand-ins Python puros de las rutas
calientes de audio/corrector y **afirma** que cada ruta rápida coincide con su
referencia ingenua — es una puerta de corrección, no una medición de rendimiento
en dispositivo.

### 6. Compilar artefactos release
Compila los artefactos release en **invocaciones separadas** para un empaquetado correcto:
```bash
# APK release standalone (modelo incluido en el archivo base)
./gradlew assembleRelease

# App Bundle release (modelo vía asset pack de instalación)
./gradlew bundleRelease
```

> [!WARNING]
> Sin un `release.keystore` autorizado (más `STORE_PASS`, `KEY_ALIAS`,
> `KEY_PASS`), `assembleRelease` funciona pero produce un
> `app-release-unsigned.apk` **sin firmar**. Es una compilación desde el código,
> no una release distribuible: los buildservers estilo F-Droid compilan desde el
> código y firman con su propia clave. Si hay keystore pero falta alguna de las
> tres credenciales, la compilación falla de inmediato en lugar de usar una clave
> de debug o por defecto.

---

## Integración continua y publicación de releases

| Workflow | Disparador | Produce | Publica |
|---|---|---|---|
| [`debug_telegram.yml`](.github/workflows/debug_telegram.yml) | Push a `main`/`feat/**`, cualquier pull request, manual | APK de debug firmado con la clave de debug del repo | Un artefacto en cada run; el APK se envía al bot de Telegram solo en pushes y runs manuales — los PR reciben las puertas y el artefacto, nunca notificación |
| [`android_release.yml`](.github/workflows/android_release.yml) | Tag `v*`, manual | **APK y AAB** release firmados | Una GitHub Release con ambos ficheros, y solo tras pasar todas las puertas |

### Por qué el APK y el AAB se compilan por separado

Un APK y un AAB necesitan layouts de assets distintos para la misma variante release. AGP
ignora los asset packs al empaquetar un APK, así que el APK debe llevar el GGUF en el
módulo base; el AAB debe llevarlo solo en el asset pack de instalación `:model_assets`,
o Play recibiría ~560 MB dos veces. `app/build.gradle.kts` añade
`model_assets/src/main/assets/` al source set release solo en la ruta APK. Una invocación
Gradle que pida ambos artefactos a la vez se rechaza en configuración con un mensaje que
nombra las tareas infractoras.

### Checks que CI exige automáticamente

En cada push a `main`/`feat/**` y en cada pull request el workflow de debug
ejecuta `cargo fmt --all -- --check`, `scripts/check_translations.py`,
`scripts/test_send_telegram.py`, `scripts/test_verify_release_artifact.py`,
`scripts/bench_performance.py`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
`:app:lintDebug`, `:app:checkModels`, `:app:checkNativeAlignment`,
`:app:verifyPackagingDecision` y `:benchmark:assembleBenchmark`. Cada paso que
usa `tee` además fija `-o pipefail`, para que un filtro no convierta una puerta
roja en verde.

El workflow de release ejecuta primero las mismas puertas de tests, traducción,
formato y decisión de empaquetado, y además:

- se niega a correr si falta alguno de los cuatro secretos de firma, antes de la compilación costosa;
- se niega a compilar si el escape de release sin modelo aparece en un workflow o en una variable `ORG_GRADLE_PROJECT_*`;
- ejecuta `:app:lintDebug` y `:app:checkNativeAlignment` antes de empaquetar, para fallar rápido ante una dependencia desalineada;
- verifica el APK con `zipalign -c -P 16 -v 4` (el requisito de 16 KB de Android 17) y `apksigner verify --print-certs`, y falla si el SHA-256 del firmante coincide con la clave de debug del repo;
- verifica el AAB con `jarsigner -verify -certs` y `keytool -printcert -jarfile` (se inspecciona la salida, porque `jarsigner -verify` sale `0` ante un bundle sin firmar) con el mismo rechazo de clave de debug;
- re-verifica el modelo dentro de cada artefacto con `scripts/verify_release_artifact.py`, que compara los bytes empaquetados contra el asset fuente por hash de contenido y rechaza un modelo ausente, duplicado, mal ubicado, truncado o corrupto.

La publicación (`Upload`/`Create Release`) ocurre solo tras todo lo anterior, así que
un fallo de firma o del modelo no puede publicar un artefacto.

### Checks que no se han ejecutado

Tests de instrumentación, mediciones Macrobenchmark, generación de Baseline Profile y
validación en tablets/plegables requieren dispositivo o emulador. No hay dispositivo
arm64 ni emulador disponible en este entorno, así que ninguno se ha ejecutado;
que `:benchmark:assembleBenchmark` **compile** no es evidencia de rendimiento medido.
No se publica aquí ningún timing Macrobenchmark ni Baseline Profile, y ninguno debe
asumirse.

La ruta de firma del workflow de release tampoco se ha ejercitado end to end con
credenciales reales de producción en este entorno (sin `release.keystore`,
`KEYSTORE_BASE64`, `STORE_PASS`, `KEY_ALIAS` ni `KEY_PASS` disponibles en local).
Lo verificado en local es el comportamiento de fallo: sin credenciales la salida
es sin firmar y no publicable, y un artefacto firmado con la clave de debug del
repo es rechazado.

---

## Notas de empaquetado del modelo

El APK release y el App Bundle (AAB) usan estrategias distintas de entrega del modelo:
- El **APK release** incluye el modelo dentro del módulo base (`assets/builtin-model/`).
- El **AAB release** lo entrega vía el asset pack de instalación `:model_assets`.

Para evitar errores de empaquetado, la configuración rechaza deliberadamente correr ambas tareas en un solo comando (p. ej. `./gradlew assembleRelease bundleRelease`). Compila siempre cada artefacto en su propio comando.

Ambos artefactos se verifican además abriéndolos tras empaquetar:
`verifyReleaseApkModel` (tras `packageRelease`) y `verifyReleaseBundleModel`
(tras `signReleaseBundle`) exigen el GGUF presente **exactamente una vez** — el
layout duplicado bundle→APK se detecta porque el AAB se empareja por el sufijo
`assets/builtin-model/…`, así que una copia en `base/` más otra en el asset pack
cuenta como duplicado. `scripts/verify_release_artifact.py` realiza el mismo check
de forma independiente sobre un artefacto ya compilado, comparando hashes de
contenido contra el asset fuente.

> [!CAUTION]
> La única forma de producir un APK release sin el modelo es pasar
> `-Pauratranscribe.allowModelLessReleaseApk=true`, que avisa de que el resultado no
> es distribuible. Existe para experimentos locales; el workflow de release lo detecta
> y se niega a compilar. Un AAB no tiene tal opt-out.

---

## Limitaciones conocidas

- **Post-procesado S1-mini en dispositivo no disponible:** aunque existen opciones de configuración, rutas de descarga y plantillas de prompt para S1-mini, la generación de texto en dispositivo no está implementada en esta build. El backend de inferencia actual (`transcribe-cpp`) está especializado en reconocimiento de voz y no soporta generación causal de lenguaje. El proveedor local S1-mini está desactivado en los ajustes.
- **Restricción de arquitectura:** la app solo corre en ARM de 64 bits (`arm64-v8a`) con soporte dotprod/fp16 de ARMv8.2. No corre en emuladores o dispositivos x86/x86_64.
- **Inserción por accesibilidad:** la inserción directa vía overlay depende del framework de Accesibilidad de Android. Campos no estándar o web que no expongan nodos editables pueden requerir pegar manualmente desde el portapapeles.

---

## Atribución

### Proyecto upstream

Este repositorio es un fork independiente de [Offline Voice Input](https://github.com/notune/android_transcribe_app) (`notune/android_transcribe_app`), creado originalmente por Noah Mühl y basado en la release upstream [v0.1.18](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28).

Las adiciones y cambios arquitectónicos posteriores de este fork —incluidos enrutado de audio multidispositivo, overlays flotantes de dictado, post-procesado remoto opcional e integraciones multisuperficie— se desarrollaron de forma independiente y no están asociados al autor original.

### Créditos de terceros

- **Arquitectura del modelo de voz:** [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) de NVIDIA, cuantizado a GGUF (Q5_K_M) por [handy-computer](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf) (licencia del modelo: OpenMDW-1.1; consulta [MODEL_LICENSE.md](MODEL_LICENSE.md)).
- **Kernels de inferencia:** [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) y [ggml](https://github.com/ggerganov/ggml) de CJ Pais y Georgi Gerganov.
- **Captura de audio:** [cpal](https://github.com/RustAudio/cpal) (Cross-Platform Audio Library).
