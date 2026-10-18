# Aura Transcribe

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--37)-blue.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](#exigences-et-compatibilité)
[![License: GPL-3.0-only](https://img.shields.io/badge/License-GPL--3.0--only-blue.svg)](LICENSE)

[English](README.md) · [Español](README_es.md) · [Deutsch](README_de.md) · Français · [Italiano](README_it.md) · [中文](README_zh.md)

Le code source de l’application est distribué sous GPL-3.0-only. Les composants tiers et le modèle vocal inclus conservent leurs propres conditions ; voir [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) et [MODEL_LICENSE.md](MODEL_LICENSE.md).

Aura Transcribe est une application de voix vers texte sur l'appareil et un service de saisie vocale pour Android. Propulsée par Rust, des optimisations SIMD ARM NEON et des noyaux d'inférence GGML, elle effectue la reconnaissance vocale localement sur le CPU de l'appareil sans nécessiter de connexion réseau active.

L'application fonctionne sur plusieurs points d'intégration Android : dictée vocale dans les claviers standard, un éditeur de méthode de saisie (IME) autonome, une surcouche flottante de dictée, des sous-titres en direct de l'audio système et la transcription hors ligne de fichiers audio.

## Captures d'écran

| Accueil | Enregistrement | Sous-titres en direct |
| --- | --- | --- |
| ![Écran d'accueil](.screenshots/screenshot_home.png) | ![Écran d'enregistrement](.screenshots/screenshot_recording.png) | ![Sous-titres en direct](.screenshots/screenshot_subtitles.png) |

---

## Fonctionnalités clés

- **Reconnaissance vocale sur l'appareil :** la transcription tourne à 100 % en local avec un modèle quantisé [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) (Q5_K_M GGUF). Les enregistrements audio ne quittent jamais votre appareil.
- **Dictée vocale à l'échelle du système :** répond à `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` d'Android pour afficher un panneau de dictée inférieur dans les claviers compatibles (comme Microsoft SwiftKey) et les champs de recherche vocale du navigateur.
- **Clavier vocal dédié (IME) :** inclut un clavier vocal autonome pour dicter d'un toucher dans toute app via le changement standard de méthode de saisie.
- **Service de reconnaissance vocale :** implémente l'API système `SpeechRecognizer` pour les intégrations de claviers et apps tiers.
- **Surcouche flottante de dictée :** bulle de microphone déplaçable (`SYSTEM_ALERT_WINDOW`) pour dicter depuis tout écran. Si activé, un service d'accessibilité optionnel (`FloatingDictationAccessibilityService`) insère le texte transcrit directement dans le champ focalisé de l'app active. Sans accès d'accessibilité, le texte est copié dans le presse-papiers.
- **Sous-titres en direct temps réel :** sous-titre les flux audio internes de l'appareil (podcasts, lecture média, appels vidéo) en local via MediaProjection, avec traduction optionnelle sur l'appareil des sous-titres finalisés via Google ML Kit.
- **Transcription de fichiers audio :** transcrit les fichiers audio préenregistrés directement sur l'appareil.
- **Routage audio multi-appareils :** détecte et route l'audio des micros internes, interfaces USB et casques Bluetooth (SCO et BLE Audio) dynamiquement, avec bascule automatique et surcharges manuelles.
- **Couverture de langues :**
  - **Interface utilisateur :** disponible en 8 langues (anglais de base, allemand, espagnol, français, italien, portugais, russe et chinois simplifié).
  - **Reconnaissance vocale :** le modèle Nemotron intégré prend en charge 40 locales de langue nativement avec autodétection en streaming ; 33 locales régionales sont directement sélectionnables. Des modèles GGUF importés par l'utilisateur (p. ex. Whisper) peuvent prendre en charge jusqu'à 99 langues.
- **Post-traitement de texte optionnel :** formatage et nettoyage configurables avec des fournisseurs externes compatibles API OpenAI (OpenAI, Groq, Cerebras, OpenRouter, Mistral, Together, Ollama) ou le preset intact *Verbatim*.

---

## Confidentialité et comportement de traitement

- **La reconnaissance vocale est 100 % locale :** toute l'inférence acoustique, le traitement audio et la transcription s'exécutent entièrement sur votre appareil. Le micro et les fichiers audio ne sont jamais téléversés vers des serveurs distants.
- **Post-traitement distant optionnel :** le post-traitement IA est **désactivé par défaut**. Si vous activez un fournisseur externe (comme Groq ou OpenAI), seul le **texte** reconnu (jamais l'audio) est transmis vers votre endpoint configuré (HTTPS pour les fournisseurs distants ; HTTP en clair uniquement vers le loopback localhost/127.0.0.1 pour un serveur Ollama local). Les clés API sont stockées localement dans le stockage privé de l'app.
- **Modes Verbatim et hors ligne :** avec le post-traitement coupé ou sur *Verbatim*, aucun texte ne sort sur le réseau.
- **Télémétrie et logs en release :** dans les builds release, la journalisation du texte transcrit et des détails d'erreur sensibles est supprimée.

---

## Exigences et compatibilité

### Appareils Android pris en charge
- **Système d'exploitation :** Android 8.0 (API 26) à Android 17 (API 37).
- **ABI cible :** `arm64-v8a` **uniquement**, aligné sur pages mémoire de 16 Ko.
- **Processeur :** CPU ARMv8.2-A avec extensions vectorielles `dotprod` et `fp16`. Non pris en charge sur ARM 32 bits ni x86/x86_64.

### Chaîne de compilation
- **JDK :** 17 (LTS)
- **Android SDK :** plateforme `android-37` (Android 17)
- **Android NDK :** `28.2.13676358`
- **Toolchain Rust :** Rust `1.78.0+` (édition 2021) avec cible `aarch64-linux-android`
- **cargo-ndk :** dernière release (`cargo install cargo-ndk`)
- **CMake et Ninja :** requis pour les dépendances natives

La compilation croisée fonctionne sur hôtes `linux-x86_64`, `linux-aarch64`, `darwin-x86_64`, `darwin-arm64` (Apple Silicon) et Windows.

---

## Utilisation de base

### 1. Dictée vocale popup
1. Ouvrez Aura Transcribe et accordez la permission micro.
2. Dans toute application, touchez la touche micro d'un clavier compatible ou un champ de recherche vocale.
3. Le dialogue inférieur transcrit en temps réel et insère le texte final à la fin.

### 2. Clavier vocal dédié (IME)
1. Activez Aura Transcribe dans **Paramètres Android → Système → Langues et saisie → Claviers à l'écran**.
2. Basculez vers Aura Transcribe avec la touche globe/bascule de votre clavier actif.
3. Touchez la surface d'enregistrement pour parler ; le texte est écrit directement dans le champ actif.

### 3. Surcouche flottante de dictée
1. Lancez la surcouche depuis l'écran d'accueil et accordez la permission d'overlay (`SYSTEM_ALERT_WINDOW`).
2. Touchez la bulle flottante pour démarrer et arrêter la dictée.
3. Pour l'insertion directe dans les saisies d'autres apps, activez le service d'accessibilité optionnel sous **Paramètres Android → Accessibilité**. L'application affiche une explication avant de vous mener aux paramètres système. Si désactivé, le texte est copié dans le presse-papiers.

### 4. Sous-titres en direct
1. Touchez **Démarrer les sous-titres en direct** sur l'écran d'accueil.
2. Approuvez le dialogue standard MediaProjection (*« Commencer à enregistrer ou caster ? »*) pour sous-titrer l'audio interne.

---

## Interopérabilité des claviers

| Clavier | Mode de compatibilité | Notes |
| :--- | :--- | :--- |
| **Microsoft SwiftKey** | Speech Intent Popup | Ouvre le panneau inférieur directement. *(Désactivez « Saisie vocale multimodale » dans les paramètres SwiftKey)* |
| **AnySoftKeyboard** | Speech Intent Popup | La touche micro ouvre le panneau inférieur. |
| **HeliBoard / FlorisBoard** | IME Switcher | La touche micro bascule directement vers le clavier vocal Aura Transcribe. |
| **FUTO Keyboard** | IME Switcher | Basculez vers Aura Transcribe avec la touche globe. |
| **Fossify / OpenBoard** | IME Switcher | Bascule vers le clavier vocal Aura Transcribe. |
| **Gboard** | System IME Switcher | Le bouton micro de Gboard est lié à Google ; basculez vers Aura Transcribe avec le sélecteur système. |

---

## Compiler depuis les sources

### 1. Cloner le dépôt
```bash
git clone https://github.com/marodriguezd/Aura-Transcribe.git
cd Aura-Transcribe
```

### 2. Télécharger et vérifier les modèles embarqués
Le modèle Nemotron GGUF embarqué est vérifié par SHA-256 avant empaquetage :
```bash
./gradlew downloadModels
./gradlew checkModels
```

### 3. Compiler les bibliothèques natives Rust
Compile la `cdylib` native et les bindings JNI pour `arm64-v8a` :
```bash
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release
```
*(Note : `./gradlew assembleDebug` l'invoque automatiquement si la bibliothèque `.so` manque.)*

### 4. Assembler l'APK de debug
```bash
./gradlew assembleDebug
```
L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

### 5. Exécuter les portes de vérification
```bash
./gradlew :app:testDebugUnitTest        # Tests unitaires JVM (ne compile pas la lib native)
./gradlew :app:lintDebug                # Contrôles Android Lint
./gradlew :app:checkModels              # SHA-256 du modèle embarqué
./gradlew :app:checkNativeAlignment     # Alignement pages 16 Ko de chaque .so
./gradlew :app:verifyPackagingDecision  # Décision APK/AAB + portes du modèle, sur archives synthétiques
./gradlew :benchmark:assembleBenchmark  # Compile le module Macrobenchmark (sans appareil)

python3 scripts/check_translations.py          # Parité des traductions sur les 8 locales
python3 scripts/test_verify_release_artifact.py # Auto-test du vérificateur d'artefact release
python3 scripts/bench_performance.py           # Micro-benchmarks d'algorithmes de référence
cargo fmt --all -- --check                     # Formatage Rust
```

`verifyPackagingDecision` et `test_verify_release_artifact.py` sont les deux portes
qui protègent la livraison du modèle embarqué : elles tournent sans télécharger le modèle de
560 Mo, car elles exercent la logique de décision et le vérificateur contre des archives
synthétiques. `bench_performance.py` mesure des substituts Python purs des chemins chauds
audio/correcteur et **assert** que chaque chemin rapide coïncide avec sa référence naïve —
c'est une porte de correction, pas une mesure de performance sur appareil.

### 6. Compiler les artefacts release
Compilez les artefacts release en **invocations séparées** pour un empaquetage correct :
```bash
# APK release standalone (modèle embarqué dans l'archive de base)
./gradlew assembleRelease

# App Bundle release (modèle via asset pack d'installation)
./gradlew bundleRelease
```

> [!WARNING]
> Sans `release.keystore` autorisé (plus `STORE_PASS`, `KEY_ALIAS`,
> `KEY_PASS`), `assembleRelease` réussit mais produit un
> `app-release-unsigned.apk` **non signé**. C'est une compilation depuis les sources,
> pas une release distribuable : les serveurs de build façon F-Droid compilent depuis les
> sources et signent avec leur propre clé. Si un keystore est présent mais qu'une des
> trois credentials manque, le build échoue aussitôt au lieu d'utiliser une clé
> debug ou par défaut.

---

## Intégration continue et publication des releases

| Workflow | Déclencheur | Produit | Publie |
|---|---|---|---|
| [`debug_telegram.yml`](.github/workflows/debug_telegram.yml) | Push sur `main`/`feat/**`, toute pull request, manuel | APK de debug signé avec la clé debug du dépôt | Un artefact sur chaque run ; l'APK est envoyé au bot Telegram seulement sur pushes et runs manuels — les PR reçoivent les portes et l'artefact, jamais de notification |
| [`android_release.yml`](.github/workflows/android_release.yml) | Tag `v*`, manuel | **APK et AAB** release signés | Une GitHub Release avec les deux fichiers, et seulement après passage de toutes les portes |

### Pourquoi l'APK et l'AAB sont compilés séparément

Un APK et un AAB exigent des layouts d'assets distincts pour la même variante release. AGP
ignore les asset packs en empaquetant un APK, donc l'APK doit porter le GGUF dans le
module de base ; l'AAB doit le porter uniquement dans l'asset pack d'installation `:model_assets`,
sinon Play recevrait ~560 Mo deux fois. `app/build.gradle.kts` ajoute
`model_assets/src/main/assets/` au source set release uniquement sur le chemin APK. Une invocation
Gradle qui demande les deux artefacts à la fois est rejetée en configuration avec un message
nommant les tâches fautives.

### Contrôles que la CI exige automatiquement

À chaque push sur `main`/`feat/**` et à chaque pull request le workflow debug
exécute `cargo fmt --all -- --check`, `scripts/check_translations.py`,
`scripts/test_send_telegram.py`, `scripts/test_verify_release_artifact.py`,
`scripts/bench_performance.py`, `:app:testDebugUnitTest`, `:app:assembleDebug`,
`:app:lintDebug`, `:app:checkModels`, `:app:checkNativeAlignment`,
`:app:verifyPackagingDecision` et `:benchmark:assembleBenchmark`. Chaque étape qui
passe par `tee` fixe aussi `-o pipefail`, pour qu'un filtre ne transforme pas une porte
rouge en verte.

Le workflow release exécute d'abord les mêmes portes de tests, traduction,
formatage et décision d'empaquetage, puis en plus :

- refuse de tourner si l'un des quatre secrets de signature manque, avant le build coûteux ;
- refuse de compiler si l'échappatoire release sans modèle apparaît dans un workflow ou une variable `ORG_GRADLE_PROJECT_*` ;
- exécute `:app:lintDebug` et `:app:checkNativeAlignment` avant empaquetage, pour échouer vite sur dépendance désalignée ;
- vérifie l'APK avec `zipalign -c -P 16 -v 4` (l'exigence 16 Ko d'Android 17) et `apksigner verify --print-certs`, et échoue si le SHA-256 du signataire égale la clé debug du dépôt ;
- vérifie l'AAB avec `jarsigner -verify -certs` et `keytool -printcert -jarfile` (la sortie est inspectée, car `jarsigner -verify` sort `0` pour un bundle non signé) avec le même rejet de clé debug ;
- re-vérifie le modèle dans chaque artefact avec `scripts/verify_release_artifact.py`, qui compare les octets empaquetés à l'asset source par hash de contenu et rejette un modèle manquant, dupliqué, mal placé, tronqué ou corrompu.

La publication (`Upload`/`Create Release`) n'arrive qu'après tout ce qui précède, donc
un échec de signature ou du modèle ne peut pas publier d'artefact.

### Contrôles qui n'ont pas tourné

Tests d'instrumentation, mesures Macrobenchmark, génération de Baseline Profile et
validation sur tablettes/pliables exigent tous un appareil ou un émulateur. Aucun
appareil arm64 ni émulateur n'est disponible dans cet environnement, donc aucun n'a
été exécuté ; que `:benchmark:assembleBenchmark` **compile** n'est pas une preuve de
performance mesurée. Aucun timing Macrobenchmark ni Baseline Profile n'est publié ici,
et aucun ne doit être supposé.

Le chemin de signature du workflow release n'a pas non plus été exercé de bout en bout
avec de vraies credentials de production dans cet environnement (pas de `release.keystore`,
`KEYSTORE_BASE64`, `STORE_PASS`, `KEY_ALIAS` ni `KEY_PASS` disponibles en local).
Ce qui est vérifié en local, c'est le comportement d'échec : sans credentials la sortie
est non signée et non publiable, et un artefact signé avec la clé debug du dépôt
est rejeté.

---

## Notes d'empaquetage du modèle

L'APK release et l'App Bundle (AAB) utilisent des stratégies distinctes de livraison du modèle :
- L'**APK release** embarque le fichier modèle dans le module de base (`assets/builtin-model/`).
- L'**AAB release** le livre via l'asset pack d'installation `:model_assets`.

Pour éviter les erreurs d'empaquetage, la configuration rejette délibérément l'exécution des deux tâches en une seule commande (p. ex. `./gradlew assembleRelease bundleRelease`). Compilez toujours chaque artefact dans sa propre commande.

Les deux artefacts sont en outre vérifiés en les ouvrant après empaquetage :
`verifyReleaseApkModel` (après `packageRelease`) et `verifyReleaseBundleModel`
(après `signReleaseBundle`) exigent le GGUF présent **exactement une fois** — le
layout dupliqué bundle→APK est détecté car l'AAB est apparié par le suffixe
`assets/builtin-model/…`, donc une copie sous `base/` plus une dans l'asset pack
compte comme doublon. `scripts/verify_release_artifact.py` effectue le même contrôle
indépendamment sur un artefact déjà compilé, en comparant les hashes de
contenu à l'asset source.

> [!CAUTION]
> La seule façon de produire un APK release sans le modèle est de passer
> `-Pauratranscribe.allowModelLessReleaseApk=true`, qui avertit que le résultat n'est pas
> distribuable. Il existe pour les expériences locales ; le workflow release le détecte
> et refuse de compiler. Un AAB n'a pas un tel opt-out.

---

## Limitations connues

- **Post-traitement S1-mini sur appareil indisponible :** bien que des options de configuration, chemins de téléchargement et templates de prompt pour S1-mini existent dans le code, la génération de texte sur appareil n'est pas implémentée dans ce build. Le backend d'inférence actuel (`transcribe-cpp`) est spécialisé en reconnaissance vocale et ne prend pas en charge la génération causale de langage. Le fournisseur local S1-mini est désactivé dans les paramètres.
- **Restriction d'architecture :** l'app ne tourne que sur ARM 64 bits (`arm64-v8a`) avec support dotprod/fp16 ARMv8.2. Elle ne tourne ni sur émulateurs ni appareils x86/x86_64.
- **Insertion par accessibilité :** l'insertion directe via l'overlay dépend du framework d'accessibilité d'Android. Les champs non standard ou web qui n'exposent pas de nœuds éditables peuvent exiger un collage manuel depuis le presse-papiers.

---

## Attribution

### Projet amont

Ce dépôt est un fork indépendant de [Offline Voice Input](https://github.com/notune/android_transcribe_app) (`notune/android_transcribe_app`), créé à l'origine par Noah Mühl et basé sur la release amont [v0.1.18](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28).

Les ajouts et changements architecturaux ultérieurs de ce fork — incluant routage audio multi-appareils, surcouches flottantes de dictée, post-traitement distant optionnel et intégrations multisurfaces — ont été développés indépendamment et ne sont pas associés à l'auteur original.

### Crédits tiers

- **Architecture du modèle vocal :** [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) de NVIDIA, quantisé en GGUF (Q5_K_M) par [handy-computer](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf) (licence du modèle : OpenMDW-1.1 ; voir [MODEL_LICENSE.md](MODEL_LICENSE.md)).
- **Noyaux d'inférence :** [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) et [ggml](https://github.com/ggerganov/ggml) de CJ Pais et Georgi Gerganov.
- **Capture audio :** [cpal](https://github.com/RustAudio/cpal) (Cross-Platform Audio Library).
