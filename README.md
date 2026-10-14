<div align="center">
  <img src="docs/assets/app_icon.png" width="100" alt="RaikaAI Logo" />
  <h1>RaikaAI</h1>

  <p>
    <a href="https://github.com/kouzen-neo/raikaai/actions/workflows/build-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/kouzen-neo/raikaai/build-apk.yml?style=for-the-badge&logo=githubactions&logoColor=white" alt="Build"></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white" alt="Android SDK"></a>
    <a href="https://ai.google.dev/edge/litert"><img src="https://img.shields.io/badge/Google_LiteRT-GPU_Accelerated-4285F4.svg?style=for-the-badge&logo=google&logoColor=white" alt="LiteRT"></a>
  </p>
</div>

100% on-device AI chat for Android, powered by **Google AI Edge LiteRT-LM**.
Runs Gemma, SmolLM, Llama and Qwen locally with GPU acceleration — plus an
embedded **OpenAI-compatible `/v1` server** so your phone works as an LLM
backend for VS Code Continue, OpenWebUI and SillyTavern over Wi-Fi.

## Features

- Offline inference, no account — OpenCL GPU with CPU fallback
- OpenAI-compatible server (`/v1/chat/completions` streaming, `/v1/models`, `/health`) — see [docs/API.md](docs/API.md)
- Characters & personas (import/export, example dialogues), skills, MCP tools, RAG docs, reminders
- Image generation via remote providers, TTS/STT, speed benchmark
- Material 3 Expressive, OLED black theme, Hugging Face model manager

## Quick start

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug                      # debug APK
./gradlew assembleRelease -PabiFilter=arm64-v8a   # optimized release APK
```

Docs: [Architecture](docs/ARCHITECTURE.md) · [Development & signing](docs/DEVELOPMENT.md) · [Embedded API](docs/API.md)

## License

Apache License 2.0 — see [LICENSE](LICENSE).
