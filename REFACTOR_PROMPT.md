# PROMPT: Structural Refactor — RaikaAI Chat Module

> Copy-paste dokumen ini sebagai instruksi kerja agen (atau checklist manusia).
> Estimasi total: 5 fase. Kerjakan BERURUTAN, satu fase = satu commit.
> Bahasa kode & komentar: **English only** (kecuali functional language patterns:
> regex reminder/search Indonesia, stopword RAG, contoh multilingual di prompt).

## 0. Konteks & Aturan Global

- Repo: `/home/kouzen/Documents/Projects/litechat`, package `com.raikaai.app`.
- Baseline (diukur 2026-10-14):
  - `ui/chat/ChatViewModel.kt` — **3304 baris**, ~165 fungsi.
  - `ui/chat/ChatScreen.kt` — **2068 baris**, 1 composable raksasa (`ChatScreen`) + helper `shortModelLabel`.
  - 3 jalur pipeline generasi: `sendMessage` (:1648), `editUserMessageAndRegenerate` (:2135), `regenerateInternal` (:2281, dipakai `regenerateLastResponse` + compare pass-2).
  - Sudah ada (JANGAN diulang): `EngineParams.engineParamsFromSettings`, `SamplingDefaults`, `AppLimits`, `String?.orFallback`, thinking-fallback retry di 2 jalur.
- **Aturan keras:**
  1. NOL perubahan perilaku user-visible. Refaktor murni: pindah/ekstrak/rename internal.
  2. Setiap fase WAJIB hijau: `./gradlew :app:compileDebugKotlin`, `./gradlew :app:testDebugUnitTest`, `./gradlew assembleRelease -PabiFilter=arm64-v8a`.
  3. Setiap fase = 1 commit `refactor(scope): ...`. Jangan campur fitur/fix.
  4. Public API yang dipakai layar lain JANGAN dihapus tanpa migrasi call-site (cek via `grep -rn`).
  5. Test yang ada tidak boleh dilemahkan. Tambah test bila ekstraksi menghasilkan unit murni (pure function).
  6. Maksimal ~400 baris per file baru. Komentar menjelaskan WHY, bukan WHAT/history.

---

## FASE 1 — Unifikasi 3 Jalur Pipeline Generasi (kerjakan PERTAMA)

**Tujuan:** satu fungsi `runGenerationPipeline(ctx)` dipakai `sendMessage`, `editUserMessageAndRegenerate`, `regenerateInternal`. Menghapus ~300 baris duplikasi + menyatukan thinking-fallback retry yang kini disalin 2x (`:1822–1846` dan `:2405–2455`).

**Langkah:**
1. Definisikan di `ChatViewModel.kt` (atau file baru `ui/chat/GenerationPipeline.kt` bila >150 baris):
   ```kotlin
   private data class GenerationCtx(
       val ragQuery: String,
       val imagePath: String?,
       val imageBytes: ByteArray?,
       val audioPath: String?,
       val audioBytes: ByteArray?,
       val targetChatId: String?,
       val historyMode: HistoryMode, // TRIM_DROP_LAST_1 (send/edit) vs TRIM_DROP_LAST_1 + sources3 label (regen)
       val onMetrics: (LiteRtEngineManager.InferenceMetrics) -> Unit,
   )
   ```
   Catatan: tiga jalur berbeda HANYA di: (a) sumber ragQuery (`promptText` / `newText.trim()` / `lastUser.content`), (b) sumber image/audio bytes, (c) label sources (`sources1/2/3` — samakan jadi satu nama), (d) `history` mapping identik (`windowed.dropLast(1).map { ChatMessage(it.role, it.content) }`).
2. Ekstrak blok bersama menjadi:
   ```kotlin
   private suspend fun runGenerationPipeline(ctx: GenerationCtx, useThinking: Boolean)
       : Triple<String, List<PromptBuilder.ChatMessage>, List<String>?>
   // returns (effectiveSystem, history, ragSources); menulis ke trailing assistant message.
   ```
   Isi: `ensureContextFit → promptWindowMessages → withRagSystem → injectSummary → PromptBuilder.build → engine.streamResponse(engineParamsFromSettings(...)) → collectStreamIntoMessage`.
3. Ekstrak guard retry menjadi:
   ```kotlin
   private suspend fun streamWithThinkingFallback(settings-enableThinking: Boolean, targetChatId: String?, pass: suspend (Boolean) -> Unit): Boolean
   // menjalankan pass(true); bila body terlihat kosong (stripThinking blank) → reset konten → pass(false).
   // return true bila hasil akhir blank (caller set errorMessage + flag prune).
   ```
4. `sendMessage`: hapus `runLocalPass` lokal, panggil pipeline + fallback helper. `regenerateInternal` dan `editUserMessageAndRegenerate`: sama. Flag `localBlankFailed`/`regenBlankFailed` diganti SATU return value helper.
5. `handleAutonomousToolCall` tetap dipanggil SEKALI setelah pipeline final (jangan pindah ke dalam helper).

**Acceptance:** diff `git diff --stat` menunjukkan −250 s/d −400 baris bersih di ChatViewModel; semua test hijau; uji manual: send, regen, edit-regen, compare mode, stop di tengah.

**Risiko:** alur inti. Mitigasi: tanpa logika baru — pindah kode verbatim dulu, commit, BARU rapikan nama di commit terpisah.

---

## FASE 2 — Pecah ChatViewModel (3304 baris)

**Tujuan:** tidak ada file >600 baris; ViewModel tinggal orkestrasi state.

**Langkah (per file baru di `ui/chat/`, ViewModel mendelegasikan):**
1. `PersonaStore.kt` — custom personas load/save/import/export/normalize + `resolvePersona` (≈ `addCustomPersona`, `upsertCustomPersona`, `importPersonaJson`, `loadCustomPersonasFromJson`).
2. `GenerationPipeline.kt` — hasil FASE 1 pindah ke sini sebagai class dengan dependensi eksplisit (engine, rag, settings) bila sudah stabil.
3. `AttachmentPipeline.kt` — `readBytesOffMain(path)`, `firstImageBytes`, docBlocks/unsupportedNote builder (duplikasi 4x di `:2202,2207,2354,2359` + send path).
4. `BenchmarkRunner.kt` — `runBenchmark` + `detectRemoteContextWindow` + `fetchRemoteModels`.
5. `ServerControls.kt` — `toggleServer`, `setServerPort/BindAll/AuthToken`, `recordRemoteCost/resetRemoteSpend`.
6. Sisakan di ViewModel: state (messages/nodes/settings/UI flags), tree/branching, persist, compare orchestration.

**Acceptance:** tiap file ≤600 baris; nol `fun` duplikat antar file; test hijau.

---

## FASE 3 — Pecah ChatScreen (2068 baris)

**Tujuan:** composable kecil yang bisa direview & di-preview terpisah.

**Langkah:**
1. `ChatTopBar.kt` — header (avatar + nama karakter + tombol new chat).
2. `ComposerBar.kt` — composer + model pill + RAG pill + mic/send + attachment strip.
3. `ChatDialogsHost.kt` — image-gen dialog, URL dialog, RAG sheet, compare dialog, web preview sheet, image lightbox.
4. `rememberImagePickers(onPick: …)` — hoist `launchImagePicker` (kini fungsi di DALAM composable `:296`, direkreasi tiap rekomposisi) + photo-picker launcher.
5. `MessageListHost` sudah ada di `ChatComponents.kt:209` — pakai langsung, hapus list duplikat bila ada.
6. `ChatComponents.kt` (1097) → `MessageBubble.kt` + `AudioPlayerUi.kt` + `PersonaAvatar.kt` (pindah verbatim dulu).

**Acceptance:** `ChatScreen.kt` ≤400 baris dan hanya wiring state; tidak ada fungsi di dalam composable; test hijau.

---

## FASE 4 — AttachmentType enum

**Tujuan:** hilangkan magic string `"image"`/`"file"` (~14 situs).

**Langkah:**
1. `enum class AttachmentType { IMAGE, FILE }` di `data/ChatRepository.kt` (sebelah `ChatAttachment`).
2. Ganti field `ChatAttachment.type: String` → `attachmentType: AttachmentType`? **STOP — itu mengubah skema JSON tersimpan.** Pendekatan aman: PERTAHANKAN field `type: String` untuk Gson, tambah:
   ```kotlin
   val ChatAttachment.kind: AttachmentType get() = if (type == "image") IMAGE else FILE
   ```
   dan migrasi SEMUA pembanding `== "image"` / `== "file"` ke `.kind ==`. Konstruksi `"image"/"file"` di `AttachmentProcessor` tetap (skema disk tidak berubah).
3. Situs: `ChatViewModel.kt:339,340,343,1681,1691,1974`, `ChatComponents.kt:263,365,432,438`, `ChatRepository.kt:19` (komentar), `McpClient.kt:195` (string "[image:…]" biarkan — teks user-facing).

**Acceptance:** `grep -rn '"image"'` hanya tersisa di konstruksi + skema + teks; test hijau.

---

## FASE 5 — i18n strings.xml

**Tujuan:** semua copy user-facing searchable & siap lokalisasi.

**Langkah:**
1. Audit: `grep -rn 'Text("' ui/ | wc -l` sebagai baseline jumlah.
2. Pindah bertahap per layar (satu commit per layar!): hardcode → `stringResource(R.string.*)`. Prioritas: `ChatScreen` (26 buah) → `SettingsScreen` → `SkillsScreen` → lainnya.
3. String DINAMIS (stats badge `"%.1f tok/s"`, error dengan parameter) → `strings.xml` dengan `%1$f` placeholders.
4. JANGAN sentuh: regex/pattern fungsional, contoh multilingual di system prompt, stopword, nama file/ekstensi, key DataStore/prefs.
5. Tambah `lintOptions { disable 'MissingTranslation' }`? TIDAK — biarkan lint mengingatkan; default `values/` = English.

**Acceptance:** `grep -rn 'Text("[A-Z]' ui/chat/ChatScreen.kt` kosong; build release + `lint` tanpa error baru.

---

## Protokol Verifikasi Tiap Fase

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew assembleRelease -PabiFilter=arm64-v8a
git add -A && git commit -m "refactor(<scope>): <apa>"
```

STOP dan minta review bila: butuh mengubah perilaku, menyentuh skema JSON tersimpan, atau test gagal >30 menit.
