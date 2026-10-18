# Aura Transcribe

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--37)-blue.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](#要求与兼容性)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

[English](README.md) · [Español](README_es.md) · [Deutsch](README_de.md) · [Français](README_fr.md) · [Italiano](README_it.md) · 中文

Aura Transcribe 是一款设备端语音转文字应用与 Android 语音输入服务。基于 Rust、ARM NEON SIMD 优化与 GGML 推理内核，在设备 CPU 上本地完成语音识别，无需联网。

应用覆盖多个 Android 集成点：标准键盘语音输入、独立输入法（IME）、悬浮听写 overlay、系统音频实时字幕以及离线音频文件转写。

## 截图

| 主页 | 录音 | 实时字幕 |
| --- | --- | --- |
| ![主页](.screenshots/screenshot_home.png) | ![录音页](.screenshots/screenshot_recording.png) | ![实时字幕](.screenshots/screenshot_subtitles.png) |

---

## 主要功能

- **设备端语音识别：**转写 100% 本地运行，使用量化 [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) 模型（Q5_K_M GGUF）。录音永不离开你的设备。
- **系统级语音输入：**响应 Android `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`，在兼容键盘（如 Microsoft SwiftKey）与浏览器语音输入框中弹出底部听写面板。
- **专用语音键盘（IME）：**内置独立语音键盘，经标准输入法切换，在任意应用中一触听写。
- **语音识别服务：**实现系统 `SpeechRecognizer` 服务 API，供第三方键盘与应用集成。
- **悬浮听写 Overlay：**可拖动的麦克风悬浮球（`SYSTEM_ALERT_WINDOW`），可在任何界面听写。启用后，可选的无障碍服务（`FloatingDictationAccessibilityService`）将转写文本直接插入当前应用的焦点输入框。未授予无障碍权限时，文本复制到剪贴板。
- **实时直播字幕：**经 MediaProjection 在本地为设备内部音频流（播客、媒体播放、视频通话）生成字幕，并可用 Google ML Kit 对定稿字幕做设备端翻译。
- **音频文件转写：**直接在设备上转写预录制的音频文件。
- **多设备音频路由：**动态检测并路由内部麦克风、USB 音频接口与蓝牙耳机（SCO 与 BLE Audio），自动切换并支持手动覆盖。
- **语言覆盖：**
  - **用户界面：**支持 8 种语言（英语基础、德语、西班牙语、法语、意大利语、葡萄牙语、俄语与简体中文）。
  - **语音识别：**内置 Nemotron 模型原生支持 40 种语言 locale 并流式自动检测；33 种地区 locale 可直接选择。用户导入的 GGUF 模型（如 Whisper）最多支持 99 种语言。
- **可选文本后处理：**经外部 OpenAI 兼容 API 提供方（OpenAI、Groq、Cerebras、OpenRouter、Mistral、Together、Ollama）可配置格式化与清理，或使用原样 *Verbatim* 预设。

---

## 隐私与处理行为

- **语音识别 100% 本地：**所有声学模型推理、音频处理与转写完全在你的设备上执行。麦克风输入与音频文件永不上传到远程服务器。
- **可选远程文本后处理：**AI 后处理默认**关闭**。若启用外部 API 提供方（如 Groq 或 OpenAI），仅将识别出的**文本**（绝非音频）发送到你配置的端点（远程提供方使用 HTTPS；仅允许到 loopback localhost/127.0.0.1 的明文 HTTP，用于本地 Ollama 服务器）。API 密钥存于应用私有存储。
- **Verbatim 与离线模式：**关闭后处理或设为 *Verbatim* 时，没有任何文本经过网络。
- **发布版遥测与日志：**发布构建中已去除转写原文与敏感错误详情的日志记录。

---

## 要求与兼容性

### 受支持的 Android 设备
- **操作系统：**Android 8.0（API 26）至 Android 17（API 37）。
- **目标 ABI：**仅 `arm64-v8a`，按 16 KB 内存页对齐。
- **处理器：**ARMv8.2-A CPU，带 `dotprod` 与 `fp16` 向量扩展。不支持 32 位 ARM 或 x86/x86_64。

### 构建工具链
- **JDK：**17（LTS）
- **Android SDK：**平台 `android-37`（Android 17）
- **Android NDK：**`28.2.13676358`
- **Rust 工具链：**Rust `1.78.0+`（2021 edition），目标 `aarch64-linux-android`
- **cargo-ndk：**最新发布版（`cargo install cargo-ndk`）
- **CMake 与 Ninja：**原生构建依赖所需

交叉编译支持 `linux-x86_64`、`linux-aarch64`、`darwin-x86_64`、`darwin-arm64`（Apple Silicon）与 Windows 宿主。

---

## 基本用法

### 1. 语音输入弹窗
1. 打开 Aura Transcribe 并授予麦克风权限。
2. 在任意应用中，点击兼容键盘的麦克风键或语音搜索输入框。
3. 底部对话框实时转写，完成后插入最终文本。

### 2. 专用语音键盘（IME）
1. 在**安卓设置 → 系统 → 语言和输入法 → 屏幕键盘**中启用 Aura Transcribe。
2. 用当前键盘的地球/切换键切换到 Aura Transcribe。
3. 点击录音区说话；转写文本直接写入当前输入框。

### 3. 悬浮听写 Overlay
1. 从主页启动 overlay 并授予悬浮窗权限（`SYSTEM_ALERT_WINDOW`）。
2. 点击悬浮麦克风球开始/停止听写。
3. 要向其他应用的输入框直接插入文本，在**安卓设置 → 无障碍**中开启可选无障碍服务。应用会先显示说明再跳转系统设置。若关闭，转写文本改为复制到剪贴板。

### 4. 实时字幕
1. 在主页点击**开始实时字幕**。
2. 同意标准的 Android MediaProjection 系统弹窗（*“开始录制或投射？”*）即开始为内部音频生成字幕。

---

## 键盘互操作

| 键盘应用 | 兼容模式 | 说明 |
| :--- | :--- | :--- |
| **Microsoft SwiftKey** | Speech Intent Popup | 直接打开底部面板。*（在 SwiftKey 富输入设置中关闭“多模态语音输入”）* |
| **AnySoftKeyboard** | Speech Intent Popup | 麦克风键打开底部面板。 |
| **HeliBoard / FlorisBoard** | IME Switcher | 麦克风键直接切换到 Aura Transcribe 语音键盘。 |
| **FUTO Keyboard** | IME Switcher | 用地球/输入切换键切换到 Aura Transcribe。 |
| **Fossify / OpenBoard** | IME Switcher | 切换到 Aura Transcribe 语音键盘。 |
| **Gboard** | System IME Switcher | Gboard 麦克风按钮绑定 Google；用系统键盘切换器切换到 Aura Transcribe。 |

---

## 从源码构建

### 1. 克隆仓库
```bash
git clone https://github.com/marodriguezd/Aura-Transcribe.git
cd Aura-Transcribe
```

### 2. 下载并验证内置模型
打包前按 SHA-256 验证捆绑的 Nemotron GGUF 模型：
```bash
./gradlew downloadModels
./gradlew checkModels
```

### 3. 构建原生 Rust 库
为 `arm64-v8a` 编译原生 `cdylib` 与 JNI 绑定：
```bash
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release
```
*（注：若缺失 `.so` 库，`./gradlew assembleDebug` 会自动调用。）*

### 4. 组装 Debug APK
```bash
./gradlew assembleDebug
```
输出 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 5. 运行验证门禁
```bash
./gradlew :app:testDebugUnitTest        # JVM 单元测试（不构建原生库）
./gradlew :app:lintDebug                # Android Lint 检查
./gradlew :app:checkModels              # 内置模型的 SHA-256
./gradlew :app:checkNativeAlignment     # 每个 .so 的 16 KB 页对齐
./gradlew :app:verifyPackagingDecision  # APK/AAB 决策 + 模型门禁（合成归档）
./gradlew :benchmark:assembleBenchmark  # 编译 Macrobenchmark 模块（无需设备）

python3 scripts/check_translations.py          # 8 个 locale 的翻译 parity
python3 scripts/test_verify_release_artifact.py # 发布产物验证器的自测
python3 scripts/bench_performance.py           # 参考算法微基准
cargo fmt --all -- --check                     # Rust 格式
```

`verifyPackagingDecision` 与 `test_verify_release_artifact.py` 是保护内置模型交付的两道门禁：
它们无需下载 560 MB 模型即可运行，因为它们针对合成归档演练决策逻辑与验证器。
`bench_performance.py` 计时音频/纠错热路径的纯 Python 替身，并**断言**每条快速路径仍与
朴素参考一致——它是正确性门禁，而非设备端性能测量。

### 6. 构建发布产物
**分开调用**构建发布产物，以保证正确的 asset 打包：
```bash
# 构建独立发布 APK（模型捆绑于 base 归档）
./gradlew assembleRelease

# 构建发布 App Bundle（模型经 install-time asset 包交付）
./gradlew bundleRelease
```

> [!WARNING]
> 没有授权的 `release.keystore`（及 `STORE_PASS`、`KEY_ALIAS`、`KEY_PASS`）时，
> `assembleRelease` 能成功但生成**未签名**的 `app-release-unsigned.apk`。这是源码构建，
> 不是可分发的发布版：F-Droid 风格的构建服务器从源码编译并用自己的密钥签名。若有
> keystore 但缺三项凭证之一，构建立即失败，而不会回退到 debug 或默认密钥。

---

## 持续集成与发布

| 工作流 | 触发 | 产物 | 发布 |
|---|---|---|---|
| [`debug_telegram.yml`](.github/workflows/debug_telegram.yml) | 推送 `main`/`feat/**`、任何 PR、手动 | 用仓库 debug 密钥签名的 Debug APK | 每次运行留工作流产物；仅推送与手动运行时发送 APK 到 Telegram bot——PR 只跑门禁并保留产物，绝不发通知 |
| [`android_release.yml`](.github/workflows/android_release.yml) | 标签 `v*`、手动 | 签名的发布 **APK 与 AAB** | 在全部通过后发布 GitHub Release，含两个文件 |

### 为什么 APK 与 AAB 分开构建

APK 与 AAB 对同一发布变体需要不同的 asset 布局。AGP 打包 APK 时忽略 asset 包，
因此 APK 必须把 GGUF 放在 base 模块；AAB 则只能放在 `:model_assets` 安装期 asset 包，
否则 Play 会收到约 560 MB 的两份。`app/build.gradle.kts` 仅在 APK 路径下把
`model_assets/src/main/assets/` 加入 release source set。一次 Gradle 调用同时索取两种产物
会在配置期被拒绝，并指明违规任务。

### CI 自动强制的检查

每次推送 `main`/`feat/**` 与每个 PR，debug 工作流运行 `cargo fmt --all -- --check`、
`scripts/check_translations.py`、`scripts/test_send_telegram.py`、
`scripts/test_verify_release_artifact.py`、`scripts/bench_performance.py`、
`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`、`:app:checkModels`、
`:app:checkNativeAlignment`、`:app:verifyPackagingDecision` 与
`:benchmark:assembleBenchmark`。凡经 `tee` 的步骤都设置 `-o pipefail`，
过滤器无法把红色门禁报成绿色。

发布工作流先跑同样的测试、翻译、格式与打包决策门禁，此外：

- 四项签名 secret 缺一即在昂贵构建前拒绝运行；
- 工作流文件或 `ORG_GRADLE_PROJECT_*` 环境变量中出现无模型发布逃生口即拒绝构建；
- 打包前运行 `:app:lintDebug` 与 `:app:checkNativeAlignment`，依赖未对齐快速失败；
- 用 `zipalign -c -P 16 -v 4`（Android 17 的 16 KB 要求）与 `apksigner verify --print-certs` 验证打包 APK，签名者 SHA-256 等于仓库 debug 密钥即失败；
- 用 `jarsigner -verify -certs` 与 `keytool -printcert -jarfile` 验证打包 AAB（检查输出，因为 `jarsigner -verify` 对未签名包退出 `0`），同样拒绝 debug 密钥；
- 用 `scripts/verify_release_artifact.py` 按内容哈希对照源 asset 复验每个产物内的模型，缺失、重复、错位、截断或损坏一律拒绝。

发布（`Upload`/`Create Release`）仅在上述全部通过后发生，因此签名或模型失败绝不可能发布产物。

### 尚未运行的检查

插桩测试、Macrobenchmark 测量、Baseline Profile 生成以及平板/折叠屏运行时验证都需要
设备或模拟器。本环境无 arm64 设备或模拟器，因此均未执行；`:benchmark:assembleBenchmark`
**能编译**不是测得运行时性能的证据。此处不发布任何 Macrobenchmark 计时或 Baseline
Profile，也不应假设其存在。

发布工作流的签名路径同样未在本环境用真实生产凭证端到端演练（本地无
`release.keystore`、`KEYSTORE_BASE64`、`STORE_PASS`、`KEY_ALIAS`、`KEY_PASS`）。
本地验证的是失败行为：无凭证时输出未签名、不可发布；用仓库 debug 密钥签名的产物
会被拒绝。

---

## 模型打包说明

发布 APK 与 Android App Bundle（AAB）使用不同的模型交付策略：
- **发布 APK**将模型文件捆绑于 base 模块（`assets/builtin-model/`）。
- **发布 AAB**经 `:model_assets` 安装期 asset 包交付模型。

为防止打包错误，构建配置故意拒绝在单条命令中同时运行两项任务（如
`./gradlew assembleRelease bundleRelease`）。务必每条产物单独一条命令构建。

两项产物在打包后还会打开复验：`verifyReleaseApkModel`（`packageRelease` 后）与
`verifyReleaseBundleModel`（`signReleaseBundle` 后）要求 GGUF **恰好出现一次**——
bundle→APK 重复布局会被检出，因为 AAB 按 `assets/builtin-model/…` 后缀匹配，
`base/` 下一份加 asset 包一份即计为重复。`scripts/verify_release_artifact.py`
对已构建产物独立执行同样检查，按内容哈希对照源 asset。

> [!CAUTION]
> 不带模型生成发布 APK 的唯一方式是传
> `-Pauratranscribe.allowModelLessReleaseApk=true`，它会声明结果不可分发。仅供本地实验；
> 发布工作流会 grep 到它并拒绝构建。AAB 无此选项。

---

## 已知限制

- **设备端 S1-mini 后处理不可用：**尽管代码中存在 S1-mini 的配置项、模型下载路径与提示模板，
  本构建未实现设备端文本生成。当前推理后端（`transcribe-cpp`）专精语音识别，不支持因果
  语言模型生成。本地 S1-mini 提供方在设置 UI 中已禁用。
- **硬件架构限制：**应用仅运行于 64 位 ARM（`arm64-v8a`），需 ARMv8.2 dotprod/fp16 支持。
  不运行于 x86/x86_64 模拟器或设备。
- **无障碍插入支持：**悬浮 overlay 的直接文本插入依赖 Android 无障碍框架。不暴露可编辑
  无障碍节点的非标准或 Web 输入框可能需要从剪贴板手动粘贴。

---

## 署名

### 上游项目

本仓库是 [Offline Voice Input](https://github.com/notune/android_transcribe_app)
（`notune/android_transcribe_app`）的独立 fork，原作者为 Noah Mühl，基于上游发布
[v0.1.18](https://github.com/notune/android_transcribe_app/commit/d95e3a7e5555fa7309975751dc790e2c96387a28)。

本 fork 后续的增补与架构变更——包括多设备音频路由、悬浮听写 overlay、可选远程文本
后处理与多 Surface 集成——均为独立开发，与原作者无关。

### 第三方致谢

- **语音模型架构：**NVIDIA [Nemotron 3.5 ASR Streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b)，由 [handy-computer](https://huggingface.co/handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf) 量化为 GGUF（Q5_K_M）（CC-BY 4.0 许可）。
- **推理内核：**CJ Pais 与 Georgi Gerganov 的 [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp) 与 [ggml](https://github.com/ggerganov/ggml)。
- **音频采集：**[cpal](https://github.com/RustAudio/cpal)（跨平台音频库）。
