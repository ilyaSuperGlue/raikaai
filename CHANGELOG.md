# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.1.0] - 2026-10-14

### Initial Release — RaikaAI (On-Device AI Engine & Local LLM Server)

This is the first official open-source release of **RaikaAI**, a standalone Android on-device LLM workstation and OpenAI-compatible local inference server powered by Google AI Edge LiteRT-LM.

---

### Features & Capabilities

#### 🧠 On-Device LiteRT-LM Inference Engine
- **Hardware Acceleration**: GPU acceleration via OpenCL (Qualcomm Adreno & ARM Mali) with automatic CPU fallback (Arm NEON / XNNPack).
- **Supported Models**: Native execution of `.litertlm` compiled weights including Gemma 2B / 3 / 4, SmolLM 135M / 360M, Llama 3.2 1B / 3B, Qwen 2.5, DeepSeek R1 Distill, and user-compiled custom models.
- **Real-Time Streaming**: Token-by-token generation streaming with sub-millisecond latency.
- **Generation Controls**: Instant prompt cancellation via `Conversation.cancelProcess()`, adjustable temperature, Top-K, Top-P, and max output tokens.
- **Context Window Indicator**: Visual token usage meter tracking conversation capacity against model context limit.
- **Speed Benchmark Tool (⚡)**: Built-in benchmark suite reporting Time-To-First-Token (TTFT ms), generation speed (tok/s), latency, and GPU vs. CPU comparison.

#### 🌐 Embedded OpenAI-Compatible HTTP/CIO Server
- **Ktor Embedded Server**: Runs directly on device in an Android Foreground Service with sticky notification and CPU wake-lock.
- **API Endpoints**:
  - `POST /v1/chat/completions` (Server-Sent Events streaming `stream: true` and single JSON response).
  - `POST /v1/completions` (raw text completion for IDE extensions).
  - `GET /v1/models` and `GET /v1/models/{model}` (active model metadata).
  - `GET /health` (status check).
- **Universal Client Compatibility**: Works out of the box with VS Code (Continue, Roo Code), OpenWebUI, SillyTavern, Obsidian Local LLM plugins, LangChain, curl, and Python `openai` SDK.
- **Network & Security**: Automatic LAN / Wi-Fi IP detection (`http://<ip>:8080/v1`), global CORS preflight support, and configurable API Bearer token protection.

#### 📦 Model Hub & Storage Manager
- **Verified Preset Catalog**: One-click download for optimized mobile models (SmolLM, Gemma, Llama).
- **Hugging Face Hub Integration**: In-app search for `.litertlm` repositories with support for gated models via Hugging Face Access Tokens.
- **Resumeable Downloader**: Multi-part chunked HTTP downloader with pause, resume, progress bar, and storage space checks.
- **SAF Importer**: Import external `.litertlm` models directly from SD card, Downloads folder, or USB OTG via Android Storage Access Framework.
- **Disk Management**: Visual storage allocation breakdown with one-tap model deletion.

#### 🎭 Characters & Persona System
- **Custom AI Characters**: Create and configure unlimited personas with custom avatar emoji, name, description, and system prompt.
- **TopBar Carousel**: Instant persona switching during active conversation without resetting context.
- **DataStore Persistence**: Fully persistent personas saved across app restarts.

#### 🛠️ Agentic Skills & Web Tools
- **Skills Hub**: Extensible agent plugin architecture with capability discovery.
- **Live Web Search**: Tool calling integration for real-time web querying via SearXNG and DuckDuckGo.
- **Structured Tool Execution**: Agent loop with JSON schema function execution and synthesis.

#### 📎 Multimodal & Attachment Processing
- **Image Support**: Vision model inputs via camera capture and gallery selection (`image/*`).
- **Document Ingestion**: Read and parse text files (`.txt`, `.md`, `.json`, `.csv`, `.log`).
- **PDF Extraction**: Native on-device PDF text rendering and extraction via Android `PdfRenderer`.
- **Audio Support**: Attachment pipeline handling audio formats (`audio/*`).

#### 🎨 Material You Interface & Usability
- **Modern Jetpack Compose UI**: Built strictly with Material 3 Expressive components and fluid animations.
- **Dynamic Theming**: Palette harmonization powered by `MaterialKolor` with dynamic accent color picker.
- **Pure Black OLED Mode**: Deep contrast theme optimized for OLED/AMOLED battery saving.
- **Rich Markdown Viewer**: Code syntax formatting with one-click code copy feedback.
- **Offline TTS**: On-device Text-to-Speech playback using Android `TextToSpeech` engine.
- **Chat History**: Full session management with persistent JSON storage in app-private directory.

---

### Security & Privacy
- **100% Local Processing**: In on-device mode, zero telemetry, prompts, or model weights are ever sent to external cloud servers.
- **Encrypted Credentials**: API keys and Hugging Face tokens are secured at rest using Android Keystore-backed AES-GCM encryption.
- **Copyleft Licensing**: Fully licensed under the **GNU General Public License v3.0 (GPLv3)**.
