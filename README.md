<div align="center">
  <img src="docs/assets/app_icon.png" width="100" alt="RaikaAI Logo" />
  <h1>RaikaAI</h1>

  <p>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=for-the-badge&logo=apache&logoColor=white" alt="License: Apache 2.0"></a>
    <a href="https://github.com/kouzen-neo/raikaai/releases"><img src="https://img.shields.io/github/v/release/kouzen-neo/raikaai?style=for-the-badge&color=teal" alt="Latest Release"></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white" alt="Android SDK"></a>
    <a href="https://ai.google.dev/edge/litert"><img src="https://img.shields.io/badge/Google_LiteRT-GPU_Accelerated-4285F4.svg?style=for-the-badge&logo=google&logoColor=white" alt="LiteRT"></a>
    <a href="https://ktor.io/"><img src="https://img.shields.io/badge/Ktor-CIO_Server-7F52FF.svg?style=for-the-badge&logo=ktor&logoColor=white" alt="Ktor"></a>
  </p>

  <p>
    <a href="README.md"><img src="https://img.shields.io/badge/EN-0078D4.svg?style=for-the-badge" alt="English"></a>
    <a href="README.id.md"><img src="https://img.shields.io/badge/ID-6e7681.svg?style=for-the-badge" alt="Bahasa Indonesia"></a>
  </p>
</div>

RaikaAI is an open-source, 100% on-device AI chat application and local inference server for Android powered by **Google AI Edge LiteRT-LM** (`com.google.ai.edge.litertlm`). It runs large language models (Gemma 4, SmolLM2, Llama 3.2, Qwen) directly on smartphone hardware with Qualcomm Adreno / ARM Mali GPU acceleration (OpenCL) and CPU fallback.

RaikaAI also features an embedded **OpenAI-compatible HTTP server (`/v1`)** built with **Ktor CIO**, turning your Android smartphone into a private LLM backend for desktop tools, IDEs (VS Code Continue), OpenWebUI, and SillyTavern over your local Wi-Fi network.

<div align="center">
  <table>
    <tr>
      <td align="center" width="20%"><b>Chat Screen</b></td>
      <td align="center" width="20%"><b>Models Hub</b></td>
      <td align="center" width="20%"><b>Characters</b></td>
      <td align="center" width="20%"><b>Skills & Tools</b></td>
      <td align="center" width="20%"><b>Chat History</b></td>
    </tr>
    <tr>
      <td align="center" width="20%"><img src="docs/screenshots/chat.png" width="100%" alt="Chat Screen"></td>
      <td align="center" width="20%"><img src="docs/screenshots/models.png" width="100%" alt="Models Hub"></td>
      <td align="center" width="20%"><img src="docs/screenshots/characters.png" width="100%" alt="Characters"></td>
      <td align="center" width="20%"><img src="docs/screenshots/skills.png" width="100%" alt="Skills & Tools"></td>
      <td align="center" width="20%"><img src="docs/screenshots/history.png" width="100%" alt="Chat History"></td>
    </tr>
  </table>
</div>

---

## Highlights & Features

- **100% Offline On-Device AI (Google LiteRT-LM)**: Execute LLM inference locally without internet or cloud subscriptions using native OpenCL GPU acceleration (Qualcomm Adreno / ARM Mali) and CPU fallback (Arm NEON / XNNPack).
- **Embedded OpenAI-Compatible Server**: High-throughput Ktor CIO server hosting `/v1/chat/completions` (SSE streaming + JSON), `/v1/completions`, and `/v1/models`. Connect VS Code Continue, OpenWebUI, or desktop scripts to your phone over Wi-Fi.
- **Custom Character Cards & Personas (v2)**: Create and switch AI personas with custom profile avatars, greetings, scenarios, system directives, and example dialogues. Full import/export support via standard JSON.
- **On-Device RAG & Document Chat**: Import PDF, TXT, MD, CSV, and source code files into a local knowledge base. The model references and cites excerpts on-device (`[Doc1]`, `[Doc2]`).
- **Skills & MCP Client**: Expand the assistant with modular skills (Web-Dev, Kotlin Specialist) and connect to external Model Context Protocol (MCP) servers with explicit user-approval safety prompts.
- **Multimodal Vision & Audio**: Send images directly to vision-capable local models, record 16kHz PCM audio clips, and transcribe voice inputs with speech-to-text.
- **LM Studio Speed Benchmark Tool (`⚡`)**: Test on-device hardware performance with standardized metrics: Time to First Token (TTFT ms), decode speed (tok/s), duration, and GPU vs CPU comparison.
- **Model Comparison Mode**: Run any prompt simultaneously across two models (on-device vs on-device, or on-device vs remote) with swipeable variant branches.
- **Hugging Face Model Manager**: Search, discover, and download `.litertlm` model weights directly from Hugging Face with resumeable multi-threaded downloads, or import existing files via Android Storage Access Framework (SAF).
- **Web Search & Link Summarizer**: Live internet research synthesis with numbered factual citations, plus one-tap web article extraction and summarization.
- **Material 3 Expressive & Pure Black OLED**: Dynamic theming powered by MaterialKolor with dynamic accent colors, smooth animations, and true 100% OLED dark mode.

---

## Download & Installation

Download the latest signed release APK directly from **[GitHub Releases](https://github.com/kouzen-neo/raikaai/releases/latest)**.

### Which APK should I download?

| APK Variant | Recommended Device |
|---|---|
| **`RaikaAI-arm64-v8a-*.apk`** | **Recommended for all modern smartphones** (Snapdragon, Dimensity, Tensor, Exynos 64-bit). Smallest download size (~24 MB) with optimized native libraries. |
| **`RaikaAI-universal-*.apk`** | Universal package containing all Android ABIs (larger file size). |

---

## Architecture & Inference Flow

```text
[ User Prompt / Image / Audio / Documents ]
                     │
                     ▼
       [ Context Assembler & RAG ]
                     │
                     ▼
  [ LiteRT-LM Engine (OpenCL GPU / CPU) ]  ◄──►  [ Embedded Ktor Server (/v1) ]
                     │                                         ▲
                     ▼                                         │
     [ Streaming Response & Thinking ]             [ VS Code / OpenWebUI / LAN ]
                     │
                     ▼
    [ Branching Tree Chat / Saved Chats ]
```

- For full architecture breakdown and data flows, see **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.
- For embedded server endpoints, authentication, and examples, see **[docs/API.md](docs/API.md)**.
- For build steps, unit tests, and signing setup, see **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)**.

---

## Quick Build Guide

Requirements: JDK 17+, Android SDK (compileSdk 37, targetSdk 36), and an ARM64 Android device (API 26+).

```bash
# Run Unit Tests
./gradlew testDebugUnitTest

# Build Debug APK
./gradlew assembleDebug

# Build Optimized Release APK (arm64-v8a)
./gradlew assembleRelease -PabiFilter=arm64-v8a
```

*Output: `app/build/outputs/apk/release/app-arm64-v8a-release.apk`*

---

## Privacy & Security

- **100% Local Inference**: When using on-device models, all prompts, images, attachments, and generated responses never leave your physical device. Zero tracking, zero telemetry sent to third parties.
- **Encrypted Secret Storage**: Remote API keys and Hugging Face tokens are encrypted at rest using Android Keystore and hardware-backed AES-GCM (EncryptedSharedPreferences).
- **LAN Server Sandbox**: Embedded Ktor server blocks cross-origin browser requests (`Origin` header) with `403 Forbidden` unless a secret API bearer token is configured, preventing rogue websites from querying your device.

---

## Open-Source Acknowledgments

RaikaAI is built on top of outstanding open-source projects:

- [Google AI Edge LiteRT](https://ai.google.dev/edge/litert) for on-device LLM inference acceleration.
- [Ktor](https://ktor.io/) for high-performance embedded HTTP/CIO server.
- [Jetpack Compose](https://developer.android.com/compose) for modern Android UI.
- [MaterialKolor](https://github.com/jordond/MaterialKolor) for dynamic Material 3 color harmonizing.
- [OkHttp](https://square.github.io/okhttp/) & [Gson](https://github.com/google/gson) for network downloads and serialization.

---

## License

This project is licensed under the [Apache License 2.0](LICENSE).
