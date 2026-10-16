package com.raikaai.app.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.raikaai.app.attachments.AttachmentProcessor
import com.raikaai.app.attachments.PendingAttachment
import com.raikaai.app.attachments.PreparedAttachment
import com.raikaai.app.data.ChatAttachment
import com.raikaai.app.data.ChatConstants
import com.raikaai.app.web.UrlSummarizer
import java.io.File
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.raikaai.app.core.engine.LiteRtEngineManager
import com.raikaai.app.core.remote.RemoteAiClient
import com.raikaai.app.core.remote.RemoteChatMessage
import com.raikaai.app.core.remote.RemoteModelItem
import com.raikaai.app.core.remote.capMessagesToWindow
import com.raikaai.app.core.server.ChatServerService
import com.raikaai.app.core.server.OpenAiServer
import com.raikaai.app.core.server.PromptBuilder
import com.raikaai.app.data.ChatMessageEntry
import com.raikaai.app.data.ChatRepository
import com.raikaai.app.data.Conversation
import com.raikaai.app.data.ConversationHeader
import com.raikaai.app.data.DEFAULT_SYSTEM_PROMPT
import com.raikaai.app.data.AppLimits
import com.raikaai.app.data.AttachmentType
import com.raikaai.app.data.SamplingDefaults
import com.raikaai.app.data.Settings
import com.raikaai.app.data.SettingsRepository
import com.raikaai.app.data.effectiveRemoteContextWindow
import com.raikaai.app.data.parseRemoteModelContextWindows
import com.raikaai.app.data.parseRemoteModelPricing
import com.raikaai.app.data.remoteModelPricingToJson
import com.raikaai.app.reminder.ReminderParser
import com.raikaai.app.reminder.cancelAllReminders
import com.raikaai.app.reminder.scheduleReminder
import com.raikaai.app.artifacts.ArtifactStore
import com.raikaai.app.localai.LocalAiCatalog
import com.raikaai.app.localai.LocalModelDownloader
import com.raikaai.app.localai.LocalModelManager
import com.raikaai.app.rag.RagManager
import com.raikaai.app.util.CodeArtifacts
import com.raikaai.app.util.FileSaver
import com.raikaai.app.util.KLog
import com.raikaai.app.util.orFallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class PersonaPreset(
    val id: String,
    val name: String,
    val iconType: String = "general",
    val description: String = "",
    val greeting: String = "",
    val systemPrompt: String,
    val avatarUri: String = "",
    val temperature: Float? = null,
    val topK: Int? = null,
    val personality: String = "",
    val scenario: String = "",
    val exampleDialogues: String = "",
) {
    companion object {
        val DEFAULT_PRESETS: List<PersonaPreset> get() = PERSONA_PRESETS
    }
}

/** Max autonomous container rounds per remote turn (OpenCode-style agent loop). */
internal const val MAX_CONTAINER_ROUNDS = 6

val PERSONA_PRESETS =
    listOf(
        PersonaPreset(
            id = "general",
            name = "General",
            iconType = "general",
            description = "Local on-device AI assistant ready to help with various tasks and inquiries.",
            greeting = "Hello! I am your local AI assistant. How can I help you today?",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        PersonaPreset(
            id = "coder",
            name = "Coder",
            iconType = "coder",
            description = "Software engineering and system architecture expert.",
            greeting = "Hello! Programming assistant ready.",
            systemPrompt = "You are an expert programming assistant.",
            temperature = 0.1f,
            topK = 20,
        ),
        PersonaPreset(
            id = "translator",
            name = "Translator",
            iconType = "translator",
            description = "Professional linguist.",
            greeting = "Hello! Please enter text to translate.",
            systemPrompt = "You are a professional translator.",
            temperature = 0.2f,
            topK = 30,
        ),
        PersonaPreset(
            id = "summarizer",
            name = "Summarize",
            iconType = "summarizer",
            description = "Condenses text into key takeaways.",
            greeting = "Hello! Provide text to summarize.",
            systemPrompt = "You are an expert summarizer.",
            temperature = 0.3f,
            topK = 40,
        ),
    )

/**
 * Gson bypasses Kotlin constructors and default values: fields missing from
 * old JSON stay JVM-null even when declared as non-null `String`, which later
 * crashes string operations. Always pass Gson-parsed personas through here
 * (load from settings, import) before storing or using them.
 *
 * NOTE for future PersonaPreset fields: declare them nullable, or give a
 * safe default AND normalize them in this function.
 */
@Suppress("SENSELESS_COMPARISON")
internal fun PersonaPreset.normalize(): PersonaPreset {
    return PersonaPreset(
        id = (id as String?).orFallback("custom_" + System.currentTimeMillis()),
        name = (name as String?).orFallback("Custom Persona"),
        iconType = (iconType as String?).orFallback("custom"),
        description = (description as String?).orEmpty(),
        greeting = (greeting as String?).orEmpty(),
        systemPrompt = (systemPrompt as String?).orEmpty(),
        avatarUri = (avatarUri as String?).orEmpty(),
        temperature = (temperature as Float?)?.coerceIn(0f, 2f),
        topK = (topK as Int?)?.takeIf { it > 0 },
        personality = (personality as String?).orEmpty(),
        scenario = (scenario as String?).orEmpty(),
        exampleDialogues = (exampleDialogues as String?).orEmpty(),
    )
}

data class BenchmarkResult(
    val prompt: String,
    val modelName: String,
    val backend: String,
    val tokensGenerated: Int,
    val ttftMs: Long,
    val decodeSpeedTokPerSec: Float,
    val totalDurationSec: Float,
    val responseText: String,
    val rating: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Search-trigger keywords matched on word boundaries. Precompiled once:
 * contains() on substrings used to fire web searches for ordinary words
 * ("hasilnya", "dijadwalkan", "mencari").
 */
private val WEB_SEARCH_KEYWORD_REGEX =
    Regex(
        "\\b(terbaru|terkini|berita|skor|hasil|jadwal|harga|cuaca|cari|search|latest|news|today|score|weather)\\b",
        RegexOption.IGNORE_CASE,
    )

class ChatViewModel(
    appContext: Application,
) : AndroidViewModel(appContext) {
    private companion object {
        const val COMPRESS_KEEP_RECENT = 6
        const val COMPRESS_THRESHOLD = 0.75f
        /** Rough token cost of one attached image for remote requests. */
        const val REMOTE_IMAGE_TOKEN_ESTIMATE = 1500
        const val CODE_OUTPUT_RULES =
            "\n\n[RESPONSE GUIDELINES]\n" +
            "1. Provide a clear, helpful explanation or summary for the user.\n" +
            "2. When providing code, ALWAYS enclose each complete file inside markdown code blocks (e.g. ```html ... ```).\n" +
            "3. On the FIRST LINE INSIDE the code block, include a filename comment with a clear extension (e.g. <!-- file: index.html -->, /* file: style.css */, // file: app.js, # file: main.py).\n" +
            "4. Always provide the full working code without diffs or placeholders."
    }

    private val app: Application = appContext
    private val settingsRepo = SettingsRepository(app)
    private val chatRepo = ChatRepository(app)
    private val modelManager = LocalModelManager(app)
    private val downloader get() = LocalModelDownloader.getInstance(app)
    private val engine get() = LiteRtEngineManager.getInstance(app)
    private val rag get() = RagManager.getInstance(app)
    private val artifactStore get() = ArtifactStore.getInstance(app)
    private val skillsManager get() = com.raikaai.app.skills.SkillsManager.getInstance(app)
    private val webSearch get() = com.raikaai.app.web.WebSearchManager.getInstance(app)

    val skills: StateFlow<List<com.raikaai.app.skills.Skill>> get() = skillsManager.skills
    val artifacts: StateFlow<List<com.raikaai.app.artifacts.Artifact>> get() = artifactStore.artifacts
    val isCompressing = mutableStateOf(false)
    val compressionTick = mutableStateOf(0)
    val liveContextTokens = mutableIntStateOf(0)

    val ragDocuments: StateFlow<List<com.raikaai.app.rag.RagDocument>> get() = rag.documents

    val settings = MutableStateFlow(Settings())

    /** Estimated cumulative remote API spend in USD (owned by ServerControls). */
    val messages = mutableStateListOf<ChatMessageEntry>()
    val isGenerating = mutableStateOf(false)
    val errorMessage = mutableStateOf<String?>(null)
    val installedModels = mutableStateOf<List<com.raikaai.app.localai.InstalledModel>>(emptyList())
    val conversations = mutableStateOf<List<ConversationHeader>>(emptyList())

    // Character/persona state lives in PersonaStore; these accessors keep
    // UI and internal call sites unchanged.
    private val personaStore = PersonaStore(app, settingsRepo, viewModelScope)
    val customPersonas: SnapshotStateList<PersonaPreset> get() = personaStore.customPersonas
    // Benchmark + remote-model discovery live in BenchmarkRunner; these
    // accessors keep UI and internal call sites unchanged.
    private val benchmarkRunner = BenchmarkRunner(
        app = app,
        settingsRepo = settingsRepo,
        engine = engine,
        scope = viewModelScope,
        settings = settings,
        isGenerating = isGenerating,
        onError = { errorMessage.value = it },
    )
    val isBenchmarking: State<Boolean> get() = benchmarkRunner.isBenchmarking
    val latestBenchmark: State<BenchmarkResult?> get() = benchmarkRunner.latestBenchmark
    val remoteModels: State<List<RemoteModelItem>> get() = benchmarkRunner.remoteModels
    val isFetchingRemoteModels: State<Boolean> get() = benchmarkRunner.isFetchingRemoteModels
    val isDetectingContextWindow: State<Boolean> get() = benchmarkRunner.isDetectingContextWindow

    fun runBenchmark(
        prompt: String = "Explain in 35 words why on-device AI is private and fast.",
        backendOverride: String? = null,
        onComplete: ((BenchmarkResult) -> Unit)? = null,
    ) = benchmarkRunner.runBenchmark(prompt, backendOverride, onComplete)

    fun detectRemoteContextWindow(modelId: String = settings.value.remoteModelId) =
        benchmarkRunner.detectRemoteContextWindow(modelId)

    fun fetchRemoteModels(baseUrl: String, apiKey: String = "") =
        benchmarkRunner.fetchRemoteModels(baseUrl, apiKey)

    fun setRemoteModelId(id: String) = benchmarkRunner.setRemoteModelId(id)

    // Alpine PRoot container (Fase 1–4): single gateway owned here so chat,
    // Models Hub card, and Environment screen share one instance.
    val containerManager = com.raikaai.app.core.container.ContainerManager.getInstance(app, settingsRepo)
    val containerInstallState: StateFlow<com.raikaai.app.core.container.ContainerManager.InstallState>
        get() = containerManager.installState
    val containerServedPort: StateFlow<Int?> get() = containerManager.servedPort

    // Embedded-server controls + spend tracking live in ServerControls.
    private val serverControls = ServerControls(
        app = app,
        settingsRepo = settingsRepo,
        scope = viewModelScope,
        settings = settings,
    )
    val serverStatus: StateFlow<OpenAiServer.Status> get() = serverControls.serverStatus
    val remoteSpendUsd: State<Double> get() = serverControls.remoteSpendUsd

    fun toggleServer(context: android.content.Context = app) = serverControls.toggleServer(context)

    fun setServerPort(v: Int) = serverControls.setServerPort(v)

    fun setServerBindAll(v: Boolean) = serverControls.setServerBindAll(v)

    fun setServerAuthToken(v: String) = serverControls.setServerAuthToken(v)

    fun resetRemoteSpend() = serverControls.resetRemoteSpend()
    val downloadStates = downloader.downloadStates
    // Active Persona tracking (owned by PersonaStore).
    val activePersona: StateFlow<PersonaPreset> get() = personaStore.activePersona

    // Multimodal attachment state (images + documents)
    val selectedAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())

    // Multimodal native audio attachment state
    val selectedAudioFile = MutableStateFlow<File?>(null)

    val isAudioSupported: Boolean
        get() {
            if (engine.isAudioSupported) return true
            val s = settings.value
            if (s.modelSource == ChatConstants.SOURCE_REMOTE) return false
            val activeId = s.activeModelId.lowercase()
            val custom = s.customModelPath.lowercase()
            if (activeId.contains("e2b") || activeId.contains("e4b") ||
                activeId.contains("gemma-4") || activeId.contains("gemma4") ||
                activeId.contains("gemma-3n") || activeId.contains("gemma3n") ||
                activeId.contains("audio") || custom.contains("e2b") ||
                custom.contains("e4b") || custom.contains("gemma-4") ||
                custom.contains("gemma-3n") || custom.contains("audio")
            ) return true
            val modelPath = ChatServerService.resolveModelPath(app, s) ?: return false
            return engine.isAudioCandidate(modelPath)
        }

    val isVisionSupported: Boolean
        get() {
            if (engine.isVisionSupported) return true
            val s = settings.value
            if (s.modelSource == ChatConstants.SOURCE_REMOTE) return false
            val activeId = s.activeModelId.lowercase()
            val custom = s.customModelPath.lowercase()
            if (activeId.contains("e2b") || activeId.contains("e4b") ||
                activeId.contains("vision") || activeId.contains("vl-") ||
                activeId.contains("paligemma") || custom.contains("e2b") ||
                custom.contains("e4b") || custom.contains("vision") ||
                custom.contains("vl-") || custom.contains("paligemma")
            ) return true
            val modelPath = ChatServerService.resolveModelPath(app, s) ?: return false
            return engine.isVisionCandidate(modelPath)
        }

    val freeDiskSpaceBytes = mutableStateOf(modelManager.getFreeDiskSpace())

    // Delegated model state from engine
    val isModelLoaded: StateFlow<Boolean> get() = engine.isModelLoaded
    val isLoadingModel: StateFlow<Boolean> get() = engine.isLoadingModel
    val loadedModelPath: StateFlow<String?> get() = engine.loadedModelPath
    val activeBackendState: StateFlow<String?> get() = engine.activeBackendState
    val engineLogs: StateFlow<List<String>> get() = LiteRtEngineManager.engineLogs

    // Remote Provider state lives in BenchmarkRunner (accessors above).

    fun addAttachments(uris: List<Uri>, forceImage: Boolean = false) {
        if (uris.isEmpty()) return
        val fresh =
            uris.mapNotNull { uri ->
                try {
                    AttachmentProcessor.buildPending(app, uri, forceImage)
                } catch (t: Throwable) {
                    KLog.e("ChatVM", "Failed to stage attachment", t)
                    null
                }
            }
        if (fresh.isNotEmpty()) {
            selectedAttachments.value = (selectedAttachments.value + fresh).take(8)
        }
    }

    fun removeAttachment(uri: Uri) {
        selectedAttachments.value = selectedAttachments.value.filterNot { it.uri == uri }
    }

    private fun clearSelectedImage() {
        selectedAttachments.value = emptyList()
    }

    fun setSelectedAudio(file: File?) {
        selectedAudioFile.value = file
    }

    fun attachAudioUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val audioDir = File(app.filesDir, "chat_audio").apply { if (!exists()) mkdirs() }
            val dest = File(audioDir, "audio_${System.currentTimeMillis()}.wav")
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                selectedAudioFile.value = dest
            } catch (t: Throwable) {
                KLog.e("ChatVM", "Failed to copy audio URI", t)
            }
        }
    }

    fun clearSelectedAudio() {
        selectedAudioFile.value = null
    }

    // Image-generation state: non-null while an image is being generated.
    val imageGenState = mutableStateOf<String?>(null)

    /** Generates an image via the remote provider's /v1/images/generations endpoint. */
    fun generateImage(prompt: String, size: String = "1024x1024") {
        val s = effectiveSettings()
        if (s.modelSource != ChatConstants.SOURCE_REMOTE || s.remoteBaseUrl.isBlank()) {
            errorMessage.value = "Image generation needs Remote Provider mode (e.g. OpenRouter)."
            return
        }
        val imageModel = s.remoteImageModelId.ifBlank { s.remoteModelId }
        if (imageModel.isBlank()) {
            errorMessage.value = "Pick an image model in the Models tab first."
            return
        }
        if (isGenerating.value || imageGenState.value != null) return
        viewModelScope.launch {
            imageGenState.value = "Generating image…"
            try {
                val bytes =
                    RemoteAiClient.generateImage(
                        s.remoteBaseUrl,
                        s.remoteApiKey,
                        imageModel,
                        prompt,
                        size,
                    ).getOrThrow()
                val dir = File(app.filesDir, "chat_images").apply { if (!exists()) mkdirs() }
                val file = File(dir, "gen_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                if (conversation == null) conversation = Conversation().also { currentConversationId = it.id }
                val userEntry =
                    ChatMessageEntry(
                        role = ChatConstants.ROLE_USER,
                        content = "Generate image ($size): $prompt",
                        parentId = messages.lastOrNull()?.id,
                    )
                messages.add(userEntry)
                registerActiveChild(userEntry)
                val assistant =
                    ChatMessageEntry(
                        role = ChatConstants.ROLE_ASSISTANT,
                        content = "Generated with `$imageModel`:",
                        imagePath = file.absolutePath,
                        parentId = userEntry.id,
                    )
                messages.add(assistant)
                registerActiveChild(assistant)
                persist()
            } catch (t: Throwable) {
                KLog.e("ChatVM", "Image generation failed", t)
                errorMessage.value = "Image generation failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                imageGenState.value = null
            }
        }
    }

    // URL summarization state: non-null while a page is being fetched.
    val urlFetchState = mutableStateOf<String?>(null)

    /**
     * Pending composer prefill from the home-screen widget ("prefill_prompt"
     * intent extra). ChatScreen consumes this once via [consumePrefill].
     */
    val pendingPrefill = mutableStateOf<String?>(null)

    fun applyPrefill(text: String?) {
        if (!text.isNullOrBlank()) pendingPrefill.value = text
    }

    fun consumePrefill(): String? {
        val v = pendingPrefill.value
        pendingPrefill.value = null
        return v
    }

    /** Fetches a web page and asks the model to summarize it. */
    fun summarizeUrl(rawUrl: String) {
        if (isGenerating.value || urlFetchState.value != null) return
        if (UrlSummarizer.normalizeUrl(rawUrl) == null) {
            errorMessage.value = "Invalid URL. Example: https://example.com/article"
            return
        }
        viewModelScope.launch {
            urlFetchState.value = "Fetching page…"
            val page =
                try {
                    UrlSummarizer.fetchReadableText(app, rawUrl)
                } finally {
                    urlFetchState.value = null
                }
            if (page == null) {
                errorMessage.value = "Failed to fetch the page. Check the URL or your internet connection."
                return@launch
            }
            sendMessage(
                "Summarize the following web page.\nTitle: ${page.title}\nURL: ${page.url}\n\n" +
                    "Provide a summary in English with key bullet points:\n\n${page.text}",
            )
        }
    }

    var currentConversationId: String? = null
        private set
    /**
     * Conversation id whose persona still needs resolving because custom
     * personas hadn't loaded when it was opened. Retried after customs load.
     * (Owned by PersonaStore.)
     */
    private var conversation: Conversation? = null
    private var nodes = mutableListOf<ChatMessageEntry>() // flat tree storage for branching
    private var genJob: Job? = null

    /**
     * Compare mode: pass 2 runs the same prompt on a challenger model, then
     * its output is merged as a second variant of pass 1's assistant message.
     */
    private data class ComparePass(
        val settings: Settings,
        val labelA: String,
        val labelB: String,
    )

    /** Pass 2 waiting to start after pass 1's finally (cleared on stop/error). */
    private var pendingComparePass: ComparePass? = null

    /** Pass 2 finished; merge its assistant message as a variant (consumed in finally). */
    private var pendingCompareMerge: ComparePass? = null

    // ── Conversation Tree (Branching) Helpers ────────────────────────

    private fun registerActiveChild(child: ChatMessageEntry) {
        val conv = conversation ?: return
        val id = child.id ?: return
        val map = conv.branchActive ?: mutableMapOf<String, String>().also { conv.branchActive = it }
        map[child.parentId ?: ChatRepository.ROOT_KEY] = id
    }

    /**
     * Removes the trailing assistant message, if any. Callers must validate
     * first — removing before validation would wipe the visible response on
     * failure.
     */
    private fun removeTrailingAssistantMessage() {
        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
            messages.removeAt(messages.lastIndex)
        }
    }

    private fun rebuildFromTree() {
        val conv = conversation ?: return
        chatRepo.normalize(conv)
        nodes = conv.messages.toMutableList()
        messages.clear()
        messages.addAll(chatRepo.resolveActivePath(conv))
        updateContextTokens()
    }

    /** Position (1-based) and total of sibling branches at [index], or null when single. */
    fun branchInfoFor(index: Int): Pair<Int, Int>? {
        val conv = conversation ?: return null
        val target = messages.getOrNull(index) ?: return null
        val targetId = target.id ?: return null
        val sibs = chatRepo.siblingsOf(conv, target)
        if (sibs.size <= 1) return null
        val pos = sibs.indexOfFirst { it.id == targetId }
        if (pos < 0) return null
        return (pos + 1) to sibs.size
    }

    fun switchBranch(index: Int, delta: Int) {
        if (isGenerating.value) return
        val conv = conversation ?: return
        val target = messages.getOrNull(index) ?: return
        val targetId = target.id ?: return
        val sibs = chatRepo.siblingsOf(conv, target)
        val pos = sibs.indexOfFirst { it.id == targetId }
        val nextPos = pos + delta
        if (pos < 0 || nextPos !in sibs.indices) return
        val nextId = sibs[nextPos].id ?: return
        val map = conv.branchActive ?: mutableMapOf<String, String>().also { conv.branchActive = it }
        map[target.parentId ?: ChatRepository.ROOT_KEY] = nextId
        rebuildFromTree()
        persist()
    }

    /**
     * Forks the conversation from message 0 up to [upToIndex] into a brand-new
     * conversation session and opens it immediately. The original chat stays intact.
     */
    fun branchToNewChat(upToIndex: Int) {
        if (isGenerating.value) return
        val conv = conversation ?: return
        if (upToIndex !in messages.indices) return
        val selectedMessages = messages.subList(0, upToIndex + 1).toList()
        if (selectedMessages.isEmpty()) return

        viewModelScope.launch {
            stopGeneration()
            val newConvId = java.util.UUID.randomUUID().toString()
            var prevId: String? = null
            val clonedNodes = mutableListOf<ChatMessageEntry>()
            for (orig in selectedMessages) {
                val newId = java.util.UUID.randomUUID().toString()
                clonedNodes.add(
                    orig.copy(
                        id = newId,
                        parentId = prevId,
                    ),
                )
                prevId = newId
            }

            val baseTitle = conv.title.ifBlank { "Chat" }
            val newTitle = "$baseTitle (Branch)"
            val newConv =
                Conversation(
                    id = newConvId,
                    title = newTitle,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    messages = clonedNodes,
                    personaId = conv.personaId,
                    pinnedModelSource = conv.pinnedModelSource,
                    pinnedModelId = conv.pinnedModelId,
                    folder = conv.folder,
                    tags = conv.tags,
                )

            chatRepo.save(newConv)
            refreshConversations()

            conversation = newConv
            currentConversationId = newConv.id
            rebuildFromTree()
            val resolved = personaStore.resolvePersona(newConv.personaId)
            personaStore.applyPersona(resolved)
            personaStore.pendingPersonaRestoreId =
                if (newConv.personaId != null && resolved.id != newConv.personaId) newConv.id else null
            refreshPinState()
        }
    }

    // ── RAG (Knowledge Documents) ────────────────────────────────────

    private suspend fun ragContext(query: String): Pair<String, List<String>>? =
        if (settings.value.ragEnabled && query.isNotBlank()) {
            try {
                rag.retrieve(query, settings.value.useChatMemory)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

    private suspend fun withRagSystem(
        base: String,
        query: String,
        s: Settings? = null,
    ): Pair<String, List<String>?> {
        val ctx = ragContext(query)
        var system = if (base.isBlank()) CODE_OUTPUT_RULES.trimStart('\n') else base + CODE_OUTPUT_RULES
        val activeSkills = skillsManager.getActivePromptInstructions()
        if (activeSkills.isNotBlank()) {
            system = "$system$activeSkills"
        }
        s?.let { system += mcpToolsPromptBlock(it) }
        s?.let { system += containerToolsPromptBlock(it) }
        if (s != null && s.modelSource == ChatConstants.SOURCE_REMOTE && (s.remoteImageModelId.isNotBlank() || s.remoteModelId.isNotBlank())) {
            system += "\n\n[IMAGE GENERATION TOOL]\n" +
                "You have access to an image generator tool. When the user asks to draw, generate, visualize, or modify an image " +
                "(e.g. 'buat gambar...', 'jadikan kucing biru', 'ubah warna...', 'draw a...', 'create an image of...'), " +
                "output EXACTLY one tool call in this format and NO OTHER text:\n" +
                "<tool_call>{\"name\": \"generate_image\", \"arguments\": {\"prompt\": \"<detailed English prompt describing the complete visual scene>\"}}</tool_call>\n"
        }
        ctx?.first?.let { system = "$system\n\n$it" }

        val allSources = (ctx?.second ?: emptyList()).toMutableList()
        val isExplicitSearch = query.startsWith("/search", ignoreCase = true) || query.startsWith("/web", ignoreCase = true)
        val isWebSkillActive = skills.value.any { it.isEnabled && (it.id == "builtin_web_researcher" || it.iconCategory.equals("search", ignoreCase = true) || it.category.equals("Research", ignoreCase = true)) }
        // Word boundaries: plain contains() false-positives on words like
        // "hasilnya", "dijadwalkan", or "mencari".
        val hasSearchKeyword = WEB_SEARCH_KEYWORD_REGEX.containsMatchIn(query)

        val shouldSearch = settings.value.enableWebSearch || isWebSkillActive || isExplicitSearch || hasSearchKeyword

        if (shouldSearch && query.isNotBlank()) {
            val cleanQuery = query.removePrefix("/search").removePrefix("/Search").removePrefix("/web").removePrefix("/Web").trim()
            val searchResult = runCatching { webSearch.search(cleanQuery.ifBlank { query }) }.getOrNull()
            if (searchResult != null) {
                system = "$system${searchResult.promptContext}"
                searchResult.items.forEach { item ->
                    allSources.add("Web: ${item.title}")
                }
            }
        }

        val finalSources = if (allSources.isNotEmpty()) allSources else null
        return system to finalSources
    }

    fun importRagDocument(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = rag.importFromUri(uri)
            if (doc == null) {
                withContext(Dispatchers.Main) {
                    errorMessage.value = "Unsupported or empty document. Text files only (txt, md, csv, json, code)."
                }
            }
        }
    }

    fun deleteRagDocument(id: String) {
        viewModelScope.launch(Dispatchers.IO) { rag.delete(id) }
    }

    fun setRagEnabled(v: Boolean) = launchSetting { settingsRepo.setRagEnabled(v) }

    fun setUseChatMemory(v: Boolean) = launchSetting { settingsRepo.setUseChatMemory(v) }

    fun setAutoCompress(v: Boolean) = launchSetting { settingsRepo.setAutoCompress(v) }

    fun setCaptureArtifacts(v: Boolean) = launchSetting { settingsRepo.setCaptureArtifacts(v) }

    fun setEnableVision(v: Boolean) = launchSetting { settingsRepo.setEnableVision(v) }

    fun setEnableWebSearch(v: Boolean) = launchSetting { settingsRepo.setEnableWebSearch(v) }

    // ── Context Compression (rolling summary + chat memory archive) ──

    /**
     * Messages that should be sent verbatim to the model: with a rolling
     * summary, only the not-yet-summarized tail is sent.
     */
    private fun promptWindowMessages(): List<ChatMessageEntry> {
        val summary = conversation?.summary
        val base = if (summary.isNullOrBlank()) messages.toList() else unfoldedMessages()
        return base.filter {
            it.role != ChatConstants.ROLE_COMPACTION && it.role != ChatConstants.ROLE_SYSTEM
        }
    }

    /**
     * Returns messages that were not yet folded into the rolling summary.
     * If there is no summary or no summarizedUntilId, all messages are returned.
     */
    private fun unfoldedMessages(): List<ChatMessageEntry> {
        val untilId = conversation?.summarizedUntilId ?: return messages.toList()
        val idx = messages.indexOfFirst { it.id == untilId }
        if (idx < 0) return messages.toList()
        return messages.subList(idx + 1, messages.size)
    }

    /**
     * Resolves a raw search query by injecting recent conversation context.
     * Returns (resolvedQuery, trackedSubject?)
     */
    private fun resolveSmartSearchQuery(
        rawQuery: String,
        history: List<PromptBuilder.ChatMessage>,
    ): Pair<String, String?> {
        // Find the most recent user message for subject tracking
        val recentUserMsg = history.lastOrNull { it.role == ChatConstants.ROLE_USER }?.content.orEmpty()
        // Extract likely subject from recent context (simple heuristic)
        val subject = recentUserMsg.takeIf { it.length in 3..100 }?.trim()
        // If query is very short or generic, prepend context
        if (rawQuery.length < 20 && !recentUserMsg.isNullOrBlank()) {
            return "$rawQuery (context: $recentUserMsg)" to subject
        }
        return rawQuery to subject
    }

    /** Tokens the model effectively sees right now (summary + unfolded tail). */
    fun effectiveContextTokens(): Int {
        val summary = conversation?.summary
        val unfolded = unfoldedMessages().filter {
            it.role != ChatConstants.ROLE_COMPACTION && it.role != ChatConstants.ROLE_SYSTEM
        }
        return unfolded.sumOf { estimateTokens(it.content) } +
            (summary?.let { estimateTokens(it) } ?: 0)
    }

    fun updateContextTokens() {
        liveContextTokens.intValue = effectiveContextTokens()
    }

    /**
     * Backward-compatible variant of [liveContextTokens] for UI display.
     */
    fun effectiveContextTokensThrottled(): Int {
        return liveContextTokens.intValue
    }

    private fun injectSummary(
        system: String,
        summary: String?,
        appliedWindow: Boolean,
    ): String =
        if (appliedWindow && !summary.isNullOrBlank()) {
            "$system\n\n[SUMMARY OF EARLIER CONVERSATION]\n$summary"
        } else {
            system
        }

    /**
     * Ensures the working context fits the window. When usage crosses the
     * trigger ratio, evicted turns are archived into Chat Memory (RAG) and a
     * rolling summary is (re)generated. Never mutates the visible history.
     *
     * @param modelPath on-device model for summary generation; null in remote
     * mode (no local summarizer) — then only the existing summary is reused
     * and the caller hard-caps the request history instead.
     * @param windowTokens effective context window; defaults to the on-device
     * setting, pass the remote window in remote mode.
     */
    private suspend fun ensureContextFit(
        s: Settings,
        modelPath: String?,
        windowTokens: Int = s.contextWindowTokens,
    ): String? {
        val conv = conversation ?: return null
        if (!s.autoCompress) return conv.summary
        val summary = conv.summary
        val unfolded = unfoldedMessages()

        // Effective usage = what the model sees now (summary + unfolded tail).
        // Reserve headroom for the upcoming response so generation never dies
        // mid-output at a full window.
        val effTok =
            unfolded.sumOf { estimateTokens(it.content) } +
                (summary?.let { estimateTokens(it) } ?: 0)
        val headroom = s.maxTokens + 256
        val triggerTok = (windowTokens * COMPRESS_THRESHOLD).toInt() - headroom
        if (effTok < triggerTok) return summary

        // Fold everything older than the recent tail into the summary.
        val foldable =
            if (unfolded.size > COMPRESS_KEEP_RECENT) unfolded.dropLast(COMPRESS_KEEP_RECENT) else emptyList()
        if (foldable.isEmpty()) return summary

        // Remote mode without a local model or remote provider configured: cannot run the summarizer
        if (modelPath == null && s.modelSource != ChatConstants.SOURCE_REMOTE) return summary

        isCompressing.value = true
        try {
            val clean =
                foldable.mapNotNull { m ->
                    stripThinking(m.content).takeIf { it.isNotBlank() }?.let { "[${m.role}] $it" }
                }
            if (clean.isEmpty()) {
                conv.summarizedUntilId = foldable.last().id
                return summary
            }
            if (s.useChatMemory) {
                runCatching {
                    rag.addChatMemory(conv.id, conv.title.ifBlank { "chat" }, clean.joinToString("\n\n").take(200_000))
                }
            }
            val prev = conv.summary
            val prompt =
                buildString {
                    appendLine("You maintain a rolling memory summary of an ongoing conversation.")
                    if (!prev.isNullOrBlank()) {
                        appendLine("Current summary so far:")
                        appendLine(prev)
                        appendLine()
                    }
                    appendLine("Dialogue to fold into the summary:")
                    appendLine(clean.joinToString("\n"))
                    appendLine()
                    appendLine("Write ONE updated compact summary paragraph (max 180 words) preserving key facts, names, decisions, code/file topics, and open questions. Output ONLY the summary text.")
                }
            val generated =
                try {
                    stripThinking(generateCompactedSummary(s, prompt, windowTokens))
                } catch (t: Throwable) {
                    KLog.w("ChatVM", "Compression failed: ${t.message}")
                    ""
                }
            val merged = generated.ifBlank { prev.orEmpty() }
            conv.summary = merged.ifBlank { null }
            conv.summarizedUntilId = foldable.last().id
            compressionTick.value++
            updateContextTokens()
            viewModelScope.launch { runCatching { chatRepo.save(conv) } }
            return conv.summary
        } finally {
            isCompressing.value = false
        }
    }

    // ── Artifact capture & management ────────────────────────────────

    private fun captureArtifacts(
        messageId: String?,
        content: String,
    ) {
        if (!settings.value.captureArtifacts) return
        val convId = currentConversationId ?: return
        val mid = messageId ?: return
        if (content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val blocks = CodeArtifacts.extractFencedBlocks(content)
                if (CodeArtifacts.shouldCapture(blocks)) {
                    val files = CodeArtifacts.deriveProjectFiles(blocks)
                    artifactStore.capture("$convId/$mid", files, chatId = convId)
                }
            }
        }
    }

    fun deleteArtifact(id: String) {
        viewModelScope.launch(Dispatchers.IO) { artifactStore.delete(id) }
    }

    fun deleteArtifactProject(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) { artifactStore.deleteProject(projectId) }
    }

    fun exportArtifactProject(
        projectId: String,
        folderLabel: String,
        onDone: (Int) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = artifactStore.artifacts.value.filter { it.projectId == projectId }
            val map = LinkedHashMap<String, String>()
            files.forEach { a -> artifactStore.readContent(a)?.let { map[a.fileName] = it } }
            val n = FileSaver.exportFolder(app, folderLabel, map)
            withContext(Dispatchers.Main) { onDone(n) }
        }
    }

    /** Loads all files of a project for the in-app web preview. Entry = first .html file. */
    fun loadWebPreview(
        projectId: String,
        onReady: (String?, Map<String, String>) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = artifactStore.artifacts.value.filter { it.projectId == projectId }
            val map = LinkedHashMap<String, String>()
            var entry: String? = null
            files.forEach { a ->
                artifactStore.readContent(a)?.let {
                    map[a.fileName] = it
                    if (entry == null && a.fileName.endsWith(".html", ignoreCase = true)) entry = a.fileName
                }
            }
            withContext(Dispatchers.Main) { onReady(entry, map) }
        }
    }

    init {
        // Token migrations run eagerly (outside flow collection) so
        // settings/mcp collectors never re-enter DataStore.
        settingsRepo.runMigrationsEagerly(viewModelScope)
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { s ->
                settings.value = s
                serverControls.refreshSpend(s.remoteSpendMicros)
                personaStore.loadCustomPersonasFromJson(s.customPersonasJson)
                // Retry a persona restore that previously fell back to General
                // only because the custom list wasn't loaded yet.
                val pendingId = personaStore.pendingPersonaRestoreId
                val conv = conversation
                if (pendingId != null && conv != null && conv.id == pendingId && conv.id == currentConversationId) {
                    val resolved = personaStore.resolvePersona(conv.personaId)
                    if (resolved.id == conv.personaId) {
                        personaStore.applyPersona(resolved)
                        personaStore.pendingPersonaRestoreId = null
                    }
                }
            }
        }
        // MCP server configs with tokens re-hydrated from encrypted storage.
        viewModelScope.launch {
            settingsRepo.mcpServersFlow.collect { servers ->
                mcpManager.setServers(servers)
            }
        }
        viewModelScope.launch {
            downloader.downloadStates.collect {
                refreshInstalledModels()
                refreshFreeDiskSpace()
            }
        }
        refreshInstalledModels()
        refreshConversations()
        refreshFreeDiskSpace()
        updateContextTokens()
    }

    fun refreshFreeDiskSpace() {
        freeDiskSpaceBytes.value = modelManager.getFreeDiskSpace()
    }

    private fun stripThinking(text: String): String =
        text
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("<think>[\\s\\S]*"), "")
            .trim()

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return maxOf(1, Math.round(text.length / 3.5).toInt())
    }

    fun refreshInstalledModels() {
        viewModelScope.launch {
            installedModels.value = modelManager.getInstalledModels()
            refreshFreeDiskSpace()
        }
    }

    fun clearError() {
        errorMessage.value = null
    }

    fun refreshConversations() {
        viewModelScope.launch {
            conversations.value = chatRepo.listConversationHeaders()
        }
    }

    fun freeDiskGb(): Float = freeDiskSpaceBytes.value.toFloat() / (1024 * 1024 * 1024)

    fun isModelDownloaded(model: com.raikaai.app.localai.LocalAiModel): Boolean = modelManager.isModelDownloaded(model)

    fun setActiveCustomPath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setCustomModelPath(path)
            settingsRepo.setActiveModelId("")
            settingsRepo.setModelSource(ChatConstants.SOURCE_LOCAL)
            engine.unload()
            refreshInstalledModels()
        }
    }

    // Server start/stop lives in ServerControls (delegating accessor above).

    fun loadConversation(id: String) = selectConversation(id)

    /** Currently pinned model for the active conversation, as (source, id). Null = follow global. */
    val modelPinState = mutableStateOf<Pair<String, String>?>(null)

    private fun refreshPinState() {
        val conv = conversation
        val src = conv?.pinnedModelSource
        val id = conv?.pinnedModelId
        modelPinState.value =
            if (!src.isNullOrBlank() && !id.isNullOrBlank()) {
                src to id
            } else {
                null
            }
    }

    /**
     * Effective settings for generation in the current conversation: a pinned
     * model overrides the global source/model selection.
     */
    fun effectiveSettings(): Settings {
        val s = settings.value
        val pin = modelPinState.value ?: return s
        val (src, id) = pin
        return when (src) {
            ChatConstants.SOURCE_REMOTE ->
                s.copy(modelSource = src, remoteModelId = id)
            else ->
                if (id.startsWith("custom:")) {
                    s.copy(
                        modelSource = ChatConstants.SOURCE_LOCAL,
                        customModelPath = id.removePrefix("custom:"),
                        activeModelId = "",
                    )
                } else {
                    s.copy(
                        modelSource = ChatConstants.SOURCE_LOCAL,
                        activeModelId = id,
                        customModelPath = "",
                    )
                }
        }
    }

    /** Pins the currently selected global model to this conversation. */
    fun pinCurrentModel() {
        val s = settings.value
        if (conversation == null) conversation = Conversation().also { currentConversationId = it.id }
        val conv = conversation ?: return
        val (src, id) =
            when (s.modelSource) {
                ChatConstants.SOURCE_REMOTE -> s.modelSource to s.remoteModelId
                else ->
                    ChatConstants.SOURCE_LOCAL to
                        if (s.customModelPath.isNotBlank()) "custom:${s.customModelPath}" else s.activeModelId
            }
        if (id.isBlank()) {
            errorMessage.value = "Pick a model before pinning."
            return
        }
        conv.pinnedModelSource = src
        conv.pinnedModelId = id
        persist()
        refreshPinState()
    }

    /** Removes the model pin; the conversation follows the global model again. */
    fun clearModelPin() {
        conversation?.pinnedModelSource = null
        conversation?.pinnedModelId = null
        persist()
        refreshPinState()
    }

    /** Moves a conversation into a folder ("" = no folder). */
    fun setConversationFolder(id: String, folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val conv = chatRepo.get(id) ?: return@launch
            conv.folder = folder.trim().take(40)
            chatRepo.save(conv)
            refreshConversations()
        }
    }

    /** Distinct non-empty folder names across conversations. */
    fun conversationFolders(): List<String> =
        conversations.value.mapNotNull { it.folder.takeIf { f -> f.isNotBlank() } }.distinct().sorted()

    /** Replaces a conversation's tags (unlike folder, a chat can carry several). */
    fun setConversationTags(id: String, tags: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val conv = chatRepo.get(id) ?: return@launch
            conv.tags = com.raikaai.app.data.sanitizeTags(tags)
            chatRepo.save(conv)
            refreshConversations()
        }
    }

    /** Distinct tags across conversations. */
    fun conversationTags(): List<String> =
        conversations.value.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sorted()

    fun selectConversation(id: String) {
        if (currentConversationId == id) return
        stopGeneration()
        viewModelScope.launch {
            val conv = chatRepo.get(id)
            if (conv != null) {
                conversation = conv
                currentConversationId = conv.id
                rebuildFromTree()
                // Restore the chat's own character without touching global settings.
                // If custom personas haven't finished loading yet, retry once they're ready.
                val resolved = personaStore.resolvePersona(conv.personaId)
                personaStore.applyPersona(resolved)
                personaStore.pendingPersonaRestoreId =
                    if (conv.personaId != null && resolved.id != conv.personaId) conv.id else null
                refreshPinState()
            }
        }
    }

    fun newChat(withPersona: PersonaPreset? = null) {
        // Detach the old conversation BEFORE cancelling generation, so the
        // cancelled job's persist() can never resurrect a just-deleted chat.
        conversation = null
        stopGeneration()
        val p = withPersona ?: activePersona.value
        val newConv = Conversation().also { currentConversationId = it.id }
        newConv.personaId = p.id
        personaStore.pendingPersonaRestoreId = null
        conversation = newConv
        nodes.clear()
        messages.clear()
        refreshPinState()
        personaStore.applyPersona(p)
        if (p.greeting.isNotBlank()) {
            messages.add(
                ChatMessageEntry(
                    role = ChatConstants.ROLE_ASSISTANT,
                    content = p.greeting,
                ),
            )
        }
        updateContextTokens()
        // No immediate save: the session is written lazily on the first real
        // message, so Delete All / New Conversation leaves no ghost entries.
        refreshConversations()
    }

    fun clearCurrentChat() {
        stopGeneration()
        messages.clear()
        nodes.clear()
        updateContextTokens()
        val conv = conversation
        conv?.messages = emptyList()
        conv?.branchActive = null
        // Remove the on-disk file entirely instead of saving an empty shell,
        // so "Clear All Messages" never leaves a leftover entry in history.
        val id = conv?.id
        if (id != null) {
            viewModelScope.launch { chatRepo.delete(id) }
        }
        refreshConversations()
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatRepo.delete(id)
            rag.clearChatMemory(id)
            if (currentConversationId == id) {
                newChat()
            }
            refreshConversations()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            chatRepo.rename(id, newTitle)
            if (currentConversationId == id) {
                conversation?.title = newTitle
            }
            refreshConversations()
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            chatRepo.deleteAll()
            rag.clearAllMemories()
            newChat()
            refreshConversations()
        }
    }

    fun clearAllHistory() {
        clearAllConversations()
    }

    fun clearLogs() {
        LiteRtEngineManager.clearLogs()
    }

    /** Full-text body search across conversations; bodies are loaded on demand (P6). */
    suspend fun searchConversationBodies(query: String): Set<String> =
        chatRepo.listConversations()
            .filter { conv -> conv.messages.any { it.content.contains(query, ignoreCase = true) } }
            .map { it.id }
            .toSet()

    /** Full bodies are loaded on demand so the UI list stays on cheap headers (P6). */
    suspend fun exportAllConversationsJson(): String {
        return com.google.gson.Gson().toJson(chatRepo.listConversations())
    }

    fun exportChatMarkdown(conv: Conversation): String = chatRepo.exportToMarkdown(conv)

    /** Loads the full conversation on demand for per-item export from header lists (P6). */
    suspend fun exportChatMarkdownById(id: String): String =
        chatRepo.get(id)?.let { chatRepo.exportToMarkdown(it) } ?: ""

    /** Loads full bodies on demand for the "export all as markdown" share action (P6). */
    suspend fun exportAllChatsMarkdown(): String =
        chatRepo.listConversations().joinToString("\n\n---\n\n") { chatRepo.exportToMarkdown(it) }

    fun exportCurrentChatMarkdown(): String {
        val conv = conversation ?: return ""
        return chatRepo.exportToMarkdown(conv)
    }

    fun stopGeneration() {
        genJob?.cancel()
        genJob = null
        // A stopped compare must never chain pass 2 or merge into a later run.
        pendingComparePass = null
        pendingCompareMerge = null
        // Never leave an approval dialog hanging after stop/switch.
        denyMcpApproval()
        denyContainerApproval()
        engine.cancelGeneration()
        isGenerating.value = false
        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT && messages.last().content.isBlank()) {
            messages.removeAt(messages.lastIndex)
        }
        persist()
    }

    // ── Autonomous Tool Calling Loop (Google AI Edge Architecture) ────

    /**
     * Collects a stream of text deltas into the trailing assistant message.
     * UI updates are batched at ~50ms intervals to avoid excessive recomposition.
     * [onDelta] is invoked for every raw delta (e.g. for token counting).
     * Collection stops appending if the user switched conversation mid-stream.
     */
    private suspend fun collectStreamIntoMessage(
        deltas: Flow<String>,
        targetChatId: String?,
        onDelta: ((String) -> Unit)? = null,
    ) {
        var lastEmissionTime = 0L
        val tokenBuffer = StringBuilder()
        deltas.collect { delta ->
            if (currentConversationId != targetChatId) return@collect
            tokenBuffer.append(delta)
            onDelta?.invoke(delta)
            val now = System.currentTimeMillis()
            if (now - lastEmissionTime >= 50L) {
                lastEmissionTime = now
                flushTokenBuffer(tokenBuffer)
            }
        }
        flushTokenBuffer(tokenBuffer)
        updateContextTokens()
    }

    private var lastLiveTokenUpdate = 0L

    /** Appends the buffered text to the trailing assistant message, if any. */
    private fun flushTokenBuffer(tokenBuffer: StringBuilder) {
        if (tokenBuffer.isEmpty()) return
        val idx = messages.lastIndex
        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            val current = messages[idx]
            messages[idx] = current.copy(content = current.content + tokenBuffer.toString())
            tokenBuffer.setLength(0)
            val now = System.currentTimeMillis()
            if (now - lastLiveTokenUpdate >= 150L) {
                lastLiveTokenUpdate = now
                updateContextTokens()
            }
        }
    }

    // ── Local generation pipeline (shared by send / edit-regen / regen) ──

    /**
     * Inputs for one on-device generation pass. The three local entry points
     * differ only in these values; the streaming procedure itself is identical,
     * so it lives in [runGenerationPipeline].
     */
    private data class GenerationCtx(
        val s: Settings,
        val modelPath: String,
        val ragQuery: String,
        val imagePath: String?,
        val imageBytes: ByteArray?,
        val audioPath: String?,
        val audioBytes: ByteArray?,
        val targetChatId: String?,
        val onMetrics: (LiteRtEngineManager.InferenceMetrics) -> Unit,
        /**
         * Receives RAG sources before streaming starts. Eager delivery (rather
         * than only via the return value) preserves the original inline
         * assignment timing, so the finally-block still stamps sources when
         * the stream throws or is stopped mid-flight.
         */
        val onRagSources: (List<String>?) -> Unit,
    )

    /**
     * Result of [streamWithThinkingFallback]. [blankFailed] is true only when
     * the output is still blank after the no-thinking retry; the caller maps
     * it to its prune flag. The user-facing error is already set by the helper.
     */
    private data class LocalPipelineOutcome(
        val effectiveSystem: String,
        val history: List<PromptBuilder.ChatMessage>,
        val ragSources: List<String>?,
        val blankFailed: Boolean,
    )

    /**
     * Single local streaming pass: fit context, resolve the RAG-augmented
     * system prompt, build the templated prompt, and collect the token stream
     * into the trailing assistant message.
     * Returns (effectiveSystem, history, ragSources).
     */
    private suspend fun runGenerationPipeline(
        ctx: GenerationCtx,
        useThinking: Boolean,
    ): Triple<String, List<PromptBuilder.ChatMessage>, List<String>?> {
        val activeSummary = runCatching { ensureContextFit(ctx.s, ctx.modelPath) }.getOrNull()
        val appliedWindow = activeSummary != null
        val windowed = promptWindowMessages()
        val (ragSystem, sources) = withRagSystem(resolveSystemPrompt(ctx.s), ctx.ragQuery, ctx.s)
        ctx.onRagSources(sources)
        val effectiveSystem = injectSummary(ragSystem, activeSummary, appliedWindow)
        val history =
            windowed
                .dropLast(1)
                .map { PromptBuilder.ChatMessage(it.role, it.content) }
        val prompt =
            PromptBuilder.build(
                messages = history,
                systemPrompt = effectiveSystem,
                templateFormat = ctx.s.promptTemplateFormat,
                modelPath = ctx.modelPath,
                enableThinking = useThinking,
                contextWindow = ctx.s.contextWindowTokens,
            )
        collectStreamIntoMessage(
            deltas =
                engine.streamResponse(
                    prompt = prompt,
                    params =
                        LiteRtEngineManager.engineParamsFromSettings(
                            s = ctx.s,
                            modelPath = ctx.modelPath,
                            systemPrompt = effectiveSystem,
                            enableThinking = useThinking,
                            temperature = effectiveTemperature(ctx.s),
                            topK = effectiveTopK(ctx.s),
                            maxTokens = ctx.s.maxTokens,
                        ),
                    imagePath = ctx.imagePath,
                    imageBytes = ctx.imageBytes,
                    audioPath = ctx.audioPath,
                    audioBytes = ctx.audioBytes,
                    onMetrics = ctx.onMetrics,
                ),
            targetChatId = ctx.targetChatId,
        )
        return Triple(effectiveSystem, history, sources)
    }

    /**
     * Runs the pipeline with thinking as configured. Small on-device thinking
     * models sometimes end the turn inside the thinking channel, producing zero
     * answer text — retry once with thinking disabled instead of showing a
     * blank bubble. Local-only by design; the remote path has its own guard.
     */
    private suspend fun streamWithThinkingFallback(ctx: GenerationCtx): LocalPipelineOutcome {
        var (effectiveSystem, history, ragSources) = runGenerationPipeline(ctx, ctx.s.enableThinking)
        if (ctx.s.enableThinking &&
            currentConversationId == ctx.targetChatId &&
            stripThinking(messages.lastOrNull()?.content.orEmpty()).isBlank()
        ) {
            KLog.w("ChatVM", "Local pass produced thinking-only output; retrying once without thinking")
            val idx = messages.lastIndex
            if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                messages[idx] = messages[idx].copy(content = "")
            }
            val retry = runGenerationPipeline(ctx, false)
            effectiveSystem = retry.first
            history = retry.second
            ragSources = retry.third
            if (currentConversationId == ctx.targetChatId &&
                stripThinking(messages.lastOrNull()?.content.orEmpty()).isBlank()
            ) {
                errorMessage.value = "The model returned no answer. Try turning off Thinking or rephrasing."
                return LocalPipelineOutcome(effectiveSystem, history, ragSources, blankFailed = true)
            }
        }
        return LocalPipelineOutcome(effectiveSystem, history, ragSources, blankFailed = false)
    }

    private suspend fun handleAutonomousToolCall(
        modelPath: String,
        s: Settings,
        targetChatId: String?,
        history: List<PromptBuilder.ChatMessage>,
        effectiveSystem: String,
        currentSources: List<String>?,
        onMetricsUpdate: (LiteRtEngineManager.InferenceMetrics) -> Unit,
    ): List<String>? {
        val lastContent = messages.lastOrNull { it.role == ChatConstants.ROLE_ASSISTANT }?.content.orEmpty()
        val toolCall = com.raikaai.app.skills.ToolCallParser.parse(lastContent)
        if (toolCall == null) {
            val genericCall = com.raikaai.app.skills.ToolCallParser.parseGenericCall(lastContent)
            if (genericCall != null) {
                if (genericCall.name.equals("generate_image", ignoreCase = true)) {
                    val imgPrompt = genericCall.arguments.get("prompt")?.asString?.ifBlank { null }
                        ?: genericCall.arguments.get("description")?.asString?.ifBlank { null }
                        ?: ""
                    if (imgPrompt.isNotBlank()) {
                        executeImageToolCall(messages.lastIndex, imgPrompt)
                    }
                    return currentSources
                }
                if (isContainerToolCall(genericCall.name)) {
                    if (!containerReady(s)) {
                        errorMessage.value = "Linux Environment not ready — open Models Hub → Linux Environment → Install/Update, then retry."
                        KLog.w("ChatVM", "Container call skipped: environment not ready")
                    } else {
                        return handleContainerToolCall(
                            call = genericCall,
                            modelPath = modelPath,
                            s = s,
                            targetChatId = targetChatId,
                            history = history,
                            effectiveSystem = effectiveSystem,
                            currentSources = currentSources,
                            onMetricsUpdate = onMetricsUpdate,
                        )
                    }
                }
                if (s.mcpEnabled && s.modelSource == ChatConstants.SOURCE_LOCAL) {
                    return handleMcpToolCall(
                        call = genericCall,
                        modelPath = modelPath,
                        s = s,
                        targetChatId = targetChatId,
                        history = history,
                        effectiveSystem = effectiveSystem,
                        currentSources = currentSources,
                        onMetricsUpdate = onMetricsUpdate,
                    )
                }
            }
            return currentSources
        }
        if (!toolCall.name.startsWith("web_search", ignoreCase = true) || toolCall.query.isBlank()) return currentSources

        val rawQuery = toolCall.query.trim()
        val (contextualQuery, subject) = resolveSmartSearchQuery(rawQuery, history)

        KLog.d("ChatVM", "Autonomous Tool Call: tool='${toolCall.name}', lang='${toolCall.lang}', raw='$rawQuery', resolved='$contextualQuery', subject='$subject'")
        val idx = messages.lastIndex
        val searchMsg = "Searching the web for \"$contextualQuery\"..."
        // B42: don't touch another conversation's messages if the user
        // switched chats while the tool call was being prepared.
        if (currentConversationId == targetChatId && idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            messages[idx] = messages[idx].copy(content = searchMsg)
        }

        var searchResult = runCatching { webSearch.search(contextualQuery, lang = toolCall.lang) }.getOrNull()

        // Smart Relevance Check: If a specific subject is being tracked, verify that search results actually mention it
        if (subject != null && searchResult != null && searchResult.items.isNotEmpty()) {
            val subWords = subject.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
            val hasMatch = searchResult.items.any { item ->
                val text = "${item.title} ${item.snippet}".lowercase()
                subWords.any { text.contains(it) }
            }
            if (!hasMatch) {
                val cleanAction = rawQuery.replace(
                    Regex("\\b(dia|ia|beliau|mereka|kapan|dimana|siapa|kenapa|mengapa|he|she|they|it|when|where|who|why|what|how)\\b", RegexOption.IGNORE_CASE),
                    ""
                ).trim()
                val refinedQuery = "$subject $cleanAction".trim()
                KLog.d("ChatVM", "Initial search lacked target subject '$subject'. Executing secondary refined search: '$refinedQuery'")
                val refinedResult = runCatching { webSearch.search(refinedQuery, lang = toolCall.lang) }.getOrNull()
                if (refinedResult != null && refinedResult.items.isNotEmpty()) {
                    searchResult = refinedResult
                }
            }
        } else if (searchResult == null || searchResult.items.isEmpty()) {
            searchResult = runCatching { webSearch.search(rawQuery, lang = toolCall.lang) }.getOrNull()
        }

        val updatedSources = (currentSources ?: emptyList()).toMutableList()

        val searchContext = if (searchResult != null && searchResult.items.isNotEmpty()) {
            searchResult.items.forEachIndexed { i, item ->
                val citation = com.raikaai.app.web.WebSourceCitation(
                    title = item.title,
                    url = item.url,
                    snippet = item.snippet,
                    index = i + 1,
                )
                updatedSources.add(citation.toJson())
            }
            searchResult.promptContext
        } else {
            "[WEB SEARCH RESULTS]\nTopic: \"$contextualQuery\"\nNo additional results found from the web. Answer based on available knowledge."
        }

        // Cleanly inject search result into the last user prompt turn
        val questionLabel = "Question"
        val followUpHistory = history.mapIndexed { i, msg ->
            if (i == history.lastIndex && msg.role.equals(ChatConstants.ROLE_USER, ignoreCase = true)) {
                PromptBuilder.ChatMessage(
                    role = ChatConstants.ROLE_USER,
                    content = "$searchContext\n\n$questionLabel: ${msg.content}"
                )
            } else {
                msg
            }
        }

        val followUpPrompt = PromptBuilder.build(
            messages = followUpHistory,
            systemPrompt = effectiveSystem,
            templateFormat = s.promptTemplateFormat,
            modelPath = modelPath,
            enableThinking = s.enableThinking,
            contextWindow = s.contextWindowTokens,
        )

        // B42: the web search above is a network call — the user may have
        // switched conversations meanwhile. Never mutate the new
        // conversation's messages with this chat's tool-call state.
        if (currentConversationId != targetChatId) return currentSources
        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            messages[idx] = messages[idx].copy(content = "", sources = updatedSources)
        }

        collectStreamIntoMessage(
            deltas =
                engine.streamResponse(
                    prompt = followUpPrompt,
                    params =
                        LiteRtEngineManager.engineParamsFromSettings(
                            s = s,
                            modelPath = modelPath,
                            systemPrompt = effectiveSystem,
                            enableThinking = s.enableThinking,
                            temperature = effectiveTemperature(s),
                            topK = effectiveTopK(s),
                            maxTokens = s.maxTokens,
                            ),
                    onMetrics = { onMetricsUpdate(it) },
                ),
            targetChatId = targetChatId,
        )

        return updatedSources
    }

    private fun isContainerToolCall(name: String): Boolean {
        val n = name.lowercase()
        return n in CONTAINER_EXEC_NAMES || n in CONTAINER_INSTALL_NAMES || n in CONTAINER_DAEMON_NAMES
    }

    /** Models invent their own names (run_command, shell, exec…): normalize all. */
    private val CONTAINER_EXEC_NAMES = setOf(
        "execute_in_container", "run_command", "run", "shell", "exec",
        "bash", "terminal", "command",
    )
    private val CONTAINER_INSTALL_NAMES = setOf(
        "ensure_package", "install_package", "apk_add",
    )
    private val CONTAINER_DAEMON_NAMES = setOf(
        "list_daemons", "stop_daemon",
    )

    /** Models also vary the argument key (command/cmd/script/code…): try all. */
    private fun extractToolCommand(
        args: com.google.gson.JsonObject,
        toolName: String,
    ): String {
        for (key in listOf("command", "cmd", "commandline", "script", "code", "input", "query")) {
            val v = runCatching { args.get(key) }.getOrNull()
            if (v != null && v.isJsonPrimitive) {
                val t = v.asString.trim()
                if (t.isNotBlank()) {
                    val cmd = if (toolName.lowercase() in CONTAINER_INSTALL_NAMES && !t.startsWith("apk") && !t.contains(" ")) {
                        "apk add --no-cache $t"
                    } else t
                    return com.raikaai.app.core.container.joinBrokenPaths(cmd)
                }
            }
        }
        // ensure_package with {"package": "ffmpeg"}
        if (toolName.lowercase() in CONTAINER_INSTALL_NAMES) {
            val pkg = runCatching { args.get("package") }.getOrNull()
            if (pkg != null && pkg.isJsonPrimitive && pkg.asString.isNotBlank()) {
                return "apk add --no-cache ${pkg.asString.trim()}"
            }
        }
        return ""
    }

    private data class ContainerDispatch(
        val toolContext: String,
        val block: String?,
        val exitCode: Int?,
    )

    /**
     * Shared executor for all container tools (local + remote): daemon
     * manager calls, policy gate, approval, run-or-detach, verbose block.
     * Returns the model-facing context, an optional user-visible block,
     * and the exit code when something actually ran.
     */
    private suspend fun dispatchContainerTool(
        toolName: String,
        args: com.google.gson.JsonObject,
        yolo: Boolean,
        ranThisTurn: MutableMap<String, Int>?,
        onStatus: (String) -> Unit,
    ): ContainerDispatch {
        val lower = toolName.lowercase()
        if (lower == "list_daemons") {
            val list = containerManager.listDaemons()
            return ContainerDispatch(
                "[CONTAINER TOOL RESULT]\nlist_daemons:\n$list\nSummarize briefly.",
                null, 0,
            )
        }
        if (lower == "stop_daemon") {
            val id = args.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.toIntOrNull()
            if (id == null) {
                return ContainerDispatch(
                    "[CONTAINER TOOL ERROR]\nstop_daemon needs {\"id\": \"<number>\"} (see list_daemons).",
                    null, null,
                )
            }
            val ok = containerManager.stopDaemon(id)
            return ContainerDispatch(
                "[CONTAINER TOOL RESULT]\nstop_daemon $id: ${if (ok) "stopped" else "no daemon with that id"}.",
                null, if (ok) 0 else 1,
            )
        }
        val command = extractToolCommand(args, toolName)
        if (command.isNotBlank() && ranThisTurn?.containsKey(command) == true) {
            KLog.d("ChatVM", "Container: identical command already ran this turn, skipping re-run")
            return ContainerDispatch(
                "[CONTAINER TOOL SKIPPED]\nThat exact command already ran this turn " +
                    "(exit=${ranThisTurn[command]}). Do NOT emit it again. Fix the command or try a different one.",
                null, ranThisTurn[command],
            )
        }
        val policy = com.raikaai.app.core.container.installPolicyFor(command.ifBlank { toolName })
        if (command.isBlank()) {
            return ContainerDispatch(
                "[CONTAINER TOOL ERROR]\nEmpty command. Provide {\"command\": \"<shell>\"}.",
                null, null,
            )
        }
        if (policy == com.raikaai.app.core.container.InstallPolicy.DENY) {
            KLog.w("ChatVM", "Container call denied by policy: $command")
            return ContainerDispatch(
                "[CONTAINER TOOL DENIED]\nThat command is blocked by policy (destructive or untrusted source). " +
                    "Apologize briefly and offer a safe alternative under /workspace.",
                null, null,
            )
        }
        onStatus("Running `$command` in Linux Environment…")
        val approved = if (policy == com.raikaai.app.core.container.InstallPolicy.ASK && !yolo) {
            requestContainerApproval(command)
        } else true
        if (!approved) {
            return ContainerDispatch(
                "[CONTAINER TOOL CALL DENIED]\nThe user denied running `$command`. " +
                    "Apologize briefly and ask how to proceed without the sandbox.",
                null, null,
            )
        }
        val auto = policy == com.raikaai.app.core.container.InstallPolicy.ALLOW || yolo
        val res = if (com.raikaai.app.core.container.containsBackgroundOp(command)) {
            containerManager.startDaemon(command)
        } else {
            containerManager.run(command)
        }
        KLog.d("ChatVM", "Container tool exit=${res.exitCode} timedOut=${res.timedOut}")
        ranThisTurn?.set(command, res.exitCode)
        val block = formatContainerRunBlock(
            command, res.stdout, res.stderr, res.exitCode, res.timedOut, autoApproved = auto,
        )
        val ctx = "[CONTAINER TOOL RESULT]\nCommand: $command\nExit: ${res.exitCode}${if (res.timedOut) " (timed out)" else ""}\n" +
            "stdout:\n${res.stdout.take(4000)}\n" +
            (if (res.stderr.isNotBlank()) "stderr:\n${res.stderr.take(1000)}\n" else "") +
            (if (res.exitCode != 0 && command.contains('\n')) "\nHINT: the command contained a line break. If a path or package name got split across lines, re-emit the ENTIRE tool call on ONE single line.\n" else "") +
            "Summarize the result for the user. Never claim you ran anything else."
        return ContainerDispatch(ctx, block, res.exitCode)
    }

    /**
     * Executes one autonomous container tool call round: policy gate
     * (ALLOW → run, ASK → approval → run, DENY → refuse) → follow-up stream
     * with the result injected. Exactly one round per generation.
     */
    private suspend fun handleContainerToolCall(
        call: com.raikaai.app.skills.ToolCallParser.GenericToolCall,
        modelPath: String,
        s: Settings,
        targetChatId: String?,
        history: List<PromptBuilder.ChatMessage>,
        effectiveSystem: String,
        currentSources: List<String>?,
        onMetricsUpdate: (LiteRtEngineManager.InferenceMetrics) -> Unit,
    ): List<String>? {
        val idx = messages.lastIndex
        val dispatched = dispatchContainerTool(call.name, call.arguments, s.containerYolo, null) { status ->
            if (currentConversationId == targetChatId && idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                messages[idx] = messages[idx].copy(content = status)
            }
        }
        val toolContext = dispatched.toolContext
        val executedBlock: String? = dispatched.block
        // Inject the tool outcome into the last user turn and stream a follow-up.
        val followUpHistory = history.mapIndexed { i, msg ->
            if (i == history.lastIndex && msg.role.equals(ChatConstants.ROLE_USER, ignoreCase = true)) {
                PromptBuilder.ChatMessage(
                    role = ChatConstants.ROLE_USER,
                    content = "$toolContext\n\nQuestion: ${msg.content}",
                )
            } else {
                msg
            }
        }
        val followUpPrompt =
            PromptBuilder.build(
                messages = followUpHistory,
                systemPrompt = effectiveSystem,
                templateFormat = s.promptTemplateFormat,
                modelPath = modelPath,
                enableThinking = s.enableThinking,
                contextWindow = s.contextWindowTokens,
            )

        // B42: never mutate a new conversation with this chat's tool-call state.
        if (currentConversationId != targetChatId) return currentSources
        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            messages[idx] = messages[idx].copy(content = "")
        }

        collectStreamIntoMessage(
            deltas =
                engine.streamResponse(
                    prompt = followUpPrompt,
                    params =
                        LiteRtEngineManager.engineParamsFromSettings(
                            s = s,
                            modelPath = modelPath,
                            systemPrompt = effectiveSystem,
                            enableThinking = s.enableThinking,
                            temperature = effectiveTemperature(s),
                            topK = effectiveTopK(s),
                            maxTokens = s.maxTokens,
                        ),
                    onMetrics = { onMetricsUpdate(it) },
                ),
            targetChatId = targetChatId,
        )
        // Verbose mode: keep the executed command + output visible above the summary.
        executedBlock?.let { block ->
            if (idx < messages.size && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                messages[idx] = messages[idx].copy(content = block + "\n\n" + messages[idx].content)
            }
        }

        return currentSources
    }

    /**
     * Executes one autonomous MCP tool call round: parse → explicit user
     * approval → execute → follow-up stream with the result injected.
     * Exactly one round per generation (no re-parsing of the follow-up), so a
     * runaway model cannot trigger unbounded tool executions.
     */
    private suspend fun handleMcpToolCall(
        call: com.raikaai.app.skills.ToolCallParser.GenericToolCall,
        modelPath: String,
        s: Settings,
        targetChatId: String?,
        history: List<PromptBuilder.ChatMessage>,
        effectiveSystem: String,
        currentSources: List<String>?,
        onMetricsUpdate: (LiteRtEngineManager.InferenceMetrics) -> Unit,
    ): List<String>? {
        val tool = mcpManager.findTool(call.name)
        val idx = messages.lastIndex
        val updatedSources = (currentSources ?: emptyList()).toMutableList()

        val toolContext: String
        if (tool == null) {
            KLog.w("ChatVM", "MCP tool '${call.name}' not found on any enabled server")
            toolContext =
                "[MCP TOOL UNAVAILABLE]\n" +
                    "Tool '${call.name}' is not available on any enabled MCP server. " +
                    "Only call tools from the [MCP TOOLS] list. Apologize briefly and ask the user how to proceed."
        } else {
            // Show status while waiting for the explicit approval gate.
            if (currentConversationId == targetChatId && idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                messages[idx] = messages[idx].copy(content = "Requesting approval to run `${tool.name}`…")
            }
            val req =
                com.raikaai.app.mcp.McpCallRequest(
                    serverId = tool.serverId,
                    serverName = tool.serverName,
                    toolName = tool.name,
                    arguments = call.arguments,
                )
            val approved = requestMcpApproval(req)
            // The user may have switched conversations while deciding.
            if (currentConversationId != targetChatId) return currentSources
            toolContext =
                if (!approved) {
                    KLog.d("ChatVM", "MCP tool '${tool.name}' denied by user")
                    "[MCP TOOL CALL DENIED]\n" +
                        "The user denied the `${tool.name}` call. Do not retry it. " +
                        "Explain briefly and ask how they'd like to continue."
                } else {
                    if (currentConversationId == targetChatId && idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                        messages[idx] = messages[idx].copy(content = "Running `${tool.name}`…")
                    }
                    val result =
                        runCatching { mcpManager.callTool(tool.serverId, tool.name, call.arguments) }
                    result.fold(
                        onSuccess = { r ->
                            updatedSources.add("MCP: ${tool.name} (${tool.serverName})")
                            buildString {
                                append("[MCP TOOL RESULT]\n")
                                append("Tool: ${tool.name}  [server: ${tool.serverName}]\n")
                                append("Arguments: ${req.argumentsJson.take(2000)}\n")
                                if (r.isError) append("The tool reported an error:\n")
                                append("Result:\n${r.text.take(8000)}")
                            }
                        },
                        onFailure = { e ->
                            KLog.w("ChatVM", "MCP tool '${tool.name}' failed: ${e.message}")
                            "[MCP TOOL ERROR]\n" +
                                "Calling `${tool.name}` failed: ${e.message ?: e.javaClass.simpleName}. " +
                                "Explain briefly and suggest alternatives."
                        },
                    )
                }
        }

        // Inject the tool outcome into the last user turn and stream a follow-up.
        val followUpHistory = history.mapIndexed { i, msg ->
            if (i == history.lastIndex && msg.role.equals(ChatConstants.ROLE_USER, ignoreCase = true)) {
                PromptBuilder.ChatMessage(
                    role = ChatConstants.ROLE_USER,
                    content = "$toolContext\n\nQuestion: ${msg.content}",
                )
            } else {
                msg
            }
        }
        val followUpPrompt =
            PromptBuilder.build(
                messages = followUpHistory,
                systemPrompt = effectiveSystem,
                templateFormat = s.promptTemplateFormat,
                modelPath = modelPath,
                enableThinking = s.enableThinking,
                contextWindow = s.contextWindowTokens,
            )

        // B42: never mutate a new conversation with this chat's tool-call state.
        if (currentConversationId != targetChatId) return currentSources
        if (idx >= 0 && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
            messages[idx] = messages[idx].copy(content = "", sources = updatedSources)
        }

        collectStreamIntoMessage(
            deltas =
                engine.streamResponse(
                    prompt = followUpPrompt,
                    params =
                        LiteRtEngineManager.engineParamsFromSettings(
                            s = s,
                            modelPath = modelPath,
                            systemPrompt = effectiveSystem,
                            enableThinking = s.enableThinking,
                            temperature = effectiveTemperature(s),
                            topK = effectiveTopK(s),
                            maxTokens = s.maxTokens,
                            ),
                    onMetrics = { onMetricsUpdate(it) },
                ),
            targetChatId = targetChatId,
        )

        return updatedSources
    }

    // ── Generation Logic (Local LiteRT vs Remote Provider) ───────────

    private val reminderCancelId =
        Regex(
            """^(?:tolong\s+)?(?:batalkan|hapus)\s+(?:semua\s+)?(?:pengingat|reminder)s?\b.*$""",
            RegexOption.IGNORE_CASE,
        )
    private val reminderCancelEn =
        Regex("""^cancel\s+(?:all\s+)?reminders?\b.*$""", RegexOption.IGNORE_CASE)

    /**
     * Handles reminder commands locally (no model call). Returns true if the
     * text was a reminder command and was fully handled.
     */
    private fun tryHandleReminderCommand(body: String): Boolean {
        val trimmed = body.trim()
        if (reminderCancelId.matches(trimmed) || reminderCancelEn.matches(trimmed)) {
            cancelAllReminders(app)
            appendLocalExchange(
                trimmed,
                "All scheduled reminders have been cancelled.",
            )
            return true
        }
        val spec = ReminderParser.parse(trimmed) ?: return false
        scheduleReminder(app, spec)
        val whenStr =
            java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.US)
                .format(java.util.Date(spec.triggerAtMillis))
        appendLocalExchange(
            trimmed,
            "Reminder set for $whenStr:\n\"${spec.note}\"",
        )
        return true
    }

    /**
     * Handles explicit slash commands "/image <prompt>" or "/img <prompt>".
     * Natural-language image requests are handled organically by the model
     * calling the `generate_image` tool (works in any user language).
     */
    private fun tryHandleImageCommand(body: String): Boolean {
        val trimmed = body.trim()
        val regex = Regex("^(?:/image\\s+|/img\\s+)(.+)", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed) ?: return false
        val imagePrompt = match.groupValues[1].trim()
        if (imagePrompt.isBlank()) return false
        generateImage(imagePrompt)
        return true
    }

    /**
     * Handles explicit container slash commands (no model call):
     * - `/run <shell>` → execute in Alpine, reply with output
     * - `/verbose <shell>` → same with proot syscall tracing (tail kept)
     * - `/serve [port]` → serve /workspace on 127.0.0.1, reply with URL
     * - `/stop-serve` → stop the preview server
     * - `/daemons` → list background servers, `/killdaemon <id>` → stop one
     * - `/env install` | `/env wipe` → install / delete the environment
     */
    private fun tryHandleContainerCommand(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("/run", ignoreCase = true) &&
            !trimmed.startsWith("/verbose", ignoreCase = true) &&
            !trimmed.startsWith("/serve", ignoreCase = true) &&
            !trimmed.startsWith("/stop-serve", ignoreCase = true) &&
            !trimmed.startsWith("/daemons", ignoreCase = true) &&
            !trimmed.startsWith("/killdaemon", ignoreCase = true) &&
            !trimmed.startsWith("/env", ignoreCase = true)
        ) return false
        viewModelScope.launch {
            val reply = when {
                trimmed.startsWith("/verbose", ignoreCase = true) -> {
                    val cmd = Regex("^/verbose\\s+(.+)", RegexOption.IGNORE_CASE)
                        .find(trimmed)?.groupValues?.get(1)?.trim().orEmpty()
                    if (cmd.isBlank()) "Usage: /verbose <shell command>" else {
                        containerManager.run(
                            cmd,
                            timeoutSec = 120,
                            keepTailOutput = true,
                            extraEnv = mapOf("PROOT_VERBOSE" to "1"),
                        ).render()
                    }
                }
                trimmed.startsWith("/run", ignoreCase = true) -> {
                    val cmd = Regex("^/run\\s+(.+)", RegexOption.IGNORE_CASE)
                        .find(trimmed)?.groupValues?.get(1)?.trim().orEmpty()
                    if (cmd.isBlank()) "Usage: /run <shell command>  (example: /run ls /workspace)" else {
                        val policy = com.raikaai.app.core.container.installPolicyFor(cmd)
                        if (policy == com.raikaai.app.core.container.InstallPolicy.DENY) {
                            "⛔ Command blocked by policy (destructive / untrusted source)."
                        } else {
                            val yolo = settings.value.containerYolo
                            val approved = if (policy == com.raikaai.app.core.container.InstallPolicy.ASK && !yolo) {
                                requestContainerApproval(cmd)
                            } else true
                            if (!approved) "Cancelled." else containerManager.run(cmd).render()
                        }
                    }
                }
                trimmed.startsWith("/stop-serve", ignoreCase = true) -> {
                    containerManager.stopServe()
                    "Preview server stopped."
                }
                trimmed.startsWith("/daemons", ignoreCase = true) -> {
                    containerManager.listDaemons()
                }
                trimmed.startsWith("/killdaemon", ignoreCase = true) -> {
                    val id = trimmed.drop("/killdaemon".length).trim().toIntOrNull()
                    if (id == null) "Usage: /killdaemon <id> (see /daemons)"
                    else if (containerManager.stopDaemon(id)) "Daemon #$id stopped."
                    else "No daemon #$id."
                }
                trimmed.startsWith("/serve", ignoreCase = true) -> {
                    val arg = trimmed.drop("/serve".length).trim().toIntOrNull()
                    val url = containerManager.serve(arg ?: com.raikaai.app.core.container.DEFAULT_PREVIEW_PORT)
                    if (url.startsWith("ERROR:")) "❌ ${url.removePrefix("ERROR:")}"
                    else "🌐 Serving /workspace at:\n$url\nOpen it in the phone browser. Stop with /stop-serve."
                }
                else -> {
                    val sub = trimmed.drop("/env".length).trim().lowercase()
                    when (sub) {
                        "install" -> {
                            containerManager.install()
                            "Downloading Alpine… watch progress in Models Hub → Linux Environment."
                        }
                        "wipe", "uninstall", "hapus" -> {
                            containerManager.wipe()
                            "Linux Environment removed (workspace kept)."
                        }
                        else -> "Usage: /env install | /env wipe"
                    }
                }
            }
            appendLocalExchange(trimmed, reply)
        }
        return true
    }

    /**
     * Handles `/yolo on|off|status`: YOLO mode auto-approves ASK-policy
     * sandbox commands. DENY-policy stays blocked no matter what.
     */
    private fun tryHandleYoloCommand(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("/yolo", ignoreCase = true)) return false
        viewModelScope.launch {
            val arg = trimmed.drop("/yolo".length).trim().lowercase()
            val reply = when (arg) {
                "on", "enable", "true", "1" -> {
                    settingsRepo.setContainerYolo(true)
                    "YOLO mode ON — sandbox commands auto-run (destructive ones stay blocked). Toggle in Models Hub → Linux Environment."
                }
                "off", "disable", "false", "0" -> {
                    settingsRepo.setContainerYolo(false)
                    "YOLO mode OFF — back to asking approval."
                }
                else -> "YOLO mode is currently ${if (settings.value.containerYolo) "ON" else "OFF"}. Usage: /yolo on | /yolo off"
            }
            appendLocalExchange(trimmed, reply)
        }
        return true
    }

    /**
     * Executes autonomous image generation when the LLM emits a `generate_image` tool call.
     * Updates the assistant message in place with the resulting image.
     */
    private fun executeImageToolCall(messageIndex: Int, prompt: String) {
        val s = effectiveSettings()
        val imageModel = s.remoteImageModelId.ifBlank { s.remoteModelId }
        if (s.remoteBaseUrl.isBlank() || imageModel.isBlank()) {
            if (messageIndex in messages.indices) {
                messages[messageIndex] = messages[messageIndex].copy(
                    content = "[Image generation requires Remote Provider with an image model configured in Models tab.]"
                )
                persist()
            }
            return
        }
        viewModelScope.launch {
            imageGenState.value = "Generating image…"
            if (messageIndex in messages.indices) {
                messages[messageIndex] = messages[messageIndex].copy(
                    content = "Generating image: \"$prompt\"…"
                )
            }
            try {
                val bytes =
                    RemoteAiClient.generateImage(
                        s.remoteBaseUrl,
                        s.remoteApiKey,
                        imageModel,
                        prompt,
                        "1024x1024",
                    ).getOrThrow()
                val dir = File(app.filesDir, "chat_images").apply { if (!exists()) mkdirs() }
                val file = File(dir, "gen_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) { file.writeBytes(bytes) }
                if (messageIndex in messages.indices) {
                    messages[messageIndex] = messages[messageIndex].copy(
                        content = "Generated with `$imageModel`:\n$prompt",
                        imagePath = file.absolutePath,
                    )
                    persist()
                }
            } catch (t: Throwable) {
                KLog.e("ChatVM", "Image tool call failed", t)
                if (messageIndex in messages.indices) {
                    messages[messageIndex] = messages[messageIndex].copy(
                        content = "Failed to generate image: ${t.message ?: t.javaClass.simpleName}"
                    )
                    persist()
                }
            } finally {
                imageGenState.value = null
            }
        }
    }

    /** Appends a user/assistant pair for locally-handled commands (no model call). */
    private fun appendLocalExchange(userText: String, assistantText: String) {
        if (conversation == null) {
            val newConv = Conversation().also { currentConversationId = it.id }
            conversation = newConv
            nodes.clear()
            messages.clear()
            refreshPinState()
        }
        val userEntry =
            ChatMessageEntry(
                role = ChatConstants.ROLE_USER,
                content = userText,
                parentId = messages.lastOrNull()?.id,
            )
        messages.add(userEntry)
        registerActiveChild(userEntry)
        val assistantEntry =
            ChatMessageEntry(
                role = ChatConstants.ROLE_ASSISTANT,
                content = assistantText,
                parentId = userEntry.id,
            )
        messages.add(assistantEntry)
        registerActiveChild(assistantEntry)
        persist()
    }

    private suspend fun generateCompactedSummary(
        s: Settings,
        prompt: String,
        windowTokens: Int,
    ): String {
        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            val cleanUrl = s.remoteBaseUrl.trim()
            if (cleanUrl.isBlank() || s.remoteModelId.isBlank()) {
                throw IllegalStateException("Remote Provider URL or model ID is not configured.")
            }
            val remoteMessages =
                listOf(
                    RemoteChatMessage(
                        role = "system",
                        text = "You maintain a rolling memory summary of an ongoing conversation. Output ONLY the summary text concisely.",
                    ),
                    RemoteChatMessage(role = "user", text = prompt),
                )
            val sb = StringBuilder()
            RemoteAiClient.streamChat(
                baseUrl = cleanUrl,
                apiKey = s.remoteApiKey,
                model = s.remoteModelId,
                messages = remoteMessages,
                temperature = SamplingDefaults.LOCAL_TEMPERATURE,
                topP = SamplingDefaults.TOP_P,
                maxTokens = SamplingDefaults.SUMMARY_MAX_TOKENS,
            ).collect { delta ->
                sb.append(delta)
            }
            return sb.toString()
        } else {
            val modelPath =
                ChatServerService.resolveModelPath(app, s)
                    ?: throw IllegalStateException("No on-device model selected or found.")
            val params =
                LiteRtEngineManager.engineParamsFromSettings(
                    s = s,
                    modelPath = modelPath,
                    temperature = SamplingDefaults.LOCAL_TEMPERATURE,
                    topK = SamplingDefaults.TOP_K,
                    topP = SamplingDefaults.TOP_P,
                    maxTokens = SamplingDefaults.SUMMARY_MAX_TOKENS,
                    contextWindow = maxOf(1024, windowTokens),
                )
            return engine.generateResponse(prompt, params)
        }
    }

    /**
     * Handles manual compaction slash command "/compact [optional focus instructions]".
     * Forces folding of older conversation turns into the rolling memory summary immediately.
     */
    private fun tryHandleCompactCommand(body: String): Boolean {
        val trimmed = body.trim()
        val regex = Regex("""^/compact(?:\s+(.*))?$""", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed) ?: return false
        val customFocus = match.groupValues.getOrNull(1)?.trim().orEmpty()
        executeManualCompaction(trimmed, customFocus)
        return true
    }

    private fun executeManualCompaction(commandText: String, customFocus: String) {
        val conv = conversation
        val unfolded = unfoldedMessages().filter {
            it.role != ChatConstants.ROLE_COMPACTION && it.role != ChatConstants.ROLE_SYSTEM
        }
        if (conv == null || unfolded.size < 2) {
            appendLocalExchange(
                commandText,
                "⚠️ Conversation is too short to compact (at least 2 messages required).",
            )
            return
        }

        val s = effectiveSettings()
        val beforeTokens = effectiveContextTokens()
        val foldable = unfolded

        isGenerating.value = true
        isCompressing.value = true
        errorMessage.value = null

        genJob =
            viewModelScope.launch {
                try {
                    val clean =
                        foldable.mapNotNull { m ->
                            stripThinking(m.content).takeIf { it.isNotBlank() }?.let { "[${m.role}] $it" }
                        }
                    if (clean.isEmpty()) {
                        conv.summarizedUntilId = foldable.last().id
                        persist()
                        updateContextTokens()
                        return@launch
                    }

                    if (s.useChatMemory) {
                        runCatching {
                            rag.addChatMemory(
                                conv.id,
                                conv.title.ifBlank { "chat" },
                                clean.joinToString("\n\n").take(200_000),
                            )
                        }
                    }

                    val prev = conv.summary
                    val prompt =
                        buildString {
                            appendLine("You are an expert conversation summarizer compressing chat memory into an OpenCode-style briefing.")
                            appendLine("Review the ongoing conversation and output a structured compaction markdown summary with EXACTLY these sections:")
                            appendLine("## Objective")
                            appendLine("- Primary goals, core questions, or user intent.")
                            appendLine()
                            appendLine("## Requirements")
                            appendLine("- Explicit constraints, requested formats, libraries, or specifications.")
                            appendLine()
                            appendLine("## Decisions")
                            appendLine("- Key choices made, code changes, architectures discussed, or solutions agreed upon.")
                            appendLine()
                            appendLine("## Work State")
                            appendLine("### Completed")
                            appendLine("- Tasks, answers, or points implemented so far.")
                            appendLine("### In Progress / Next")
                            appendLine("- Pending items, open questions, or immediate next steps.")
                            appendLine()
                            if (!prev.isNullOrBlank()) {
                                appendLine("Prior rolling summary to incorporate:")
                                appendLine(prev)
                                appendLine()
                            }
                            appendLine("Dialogue to compact into the summary:")
                            appendLine(clean.joinToString("\n"))
                            appendLine()
                            if (customFocus.isNotBlank()) {
                                appendLine("Special user focus:")
                                appendLine(customFocus)
                                appendLine()
                            }
                            appendLine("Rules:")
                            appendLine("- Write in English.")
                            appendLine("- Output ONLY the markdown text starting directly with \"## Objective\". No conversational chatter.")
                        }

                    val windowTokens =
                        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
                            s.effectiveRemoteContextWindow(s.remoteModelId)
                        } else {
                            s.contextWindowTokens
                        }

                    val generatedRaw = generateCompactedSummary(s, prompt, windowTokens)
                    val generated = stripThinking(generatedRaw).trim()
                    val merged = generated.ifBlank { prev.orEmpty() }

                    conv.summary = merged.ifBlank { null }
                    val outTokens = estimateTokens(merged)
                    val statsLabel = "Compaction · ${formatCompactTokens(beforeTokens)} in · ${formatCompactTokens(outTokens)} out"

                    val compactionEntry =
                        ChatMessageEntry(
                            role = ChatConstants.ROLE_COMPACTION,
                            content = merged,
                            stats = statsLabel,
                            parentId = messages.lastOrNull()?.id,
                        )
                    messages.add(compactionEntry)
                    registerActiveChild(compactionEntry)

                    conv.summarizedUntilId = compactionEntry.id
                    compressionTick.value++
                    persist()
                    updateContextTokens()
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) {
                        KLog.w("ChatVM", "Manual compact failed: ${t.message}")
                        errorMessage.value = "Compaction failed: ${t.message}"
                        appendLocalExchange(
                            commandText,
                            "❌ Compaction failed: ${t.message ?: "Model inference error"}",
                        )
                    }
                } finally {
                    isCompressing.value = false
                    isGenerating.value = false
                }
            }
    }

    private fun formatCompactTokens(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> {
                val m = tokens / 1_000_000.0
                if (m == m.toLong().toDouble()) "${m.toLong()}M" else String.format(java.util.Locale.US, "%.1fM", m)
            }
            tokens >= 1000 -> {
                val k = tokens / 1000.0
                String.format(java.util.Locale.US, "%.1fK", k)
            }
            else -> "$tokens"
        }
    }

    fun sendMessage(userText: String) {
        val body = userText.trim()
        val currentAudioFile = selectedAudioFile.value
        val pending = selectedAttachments.value
        if ((body.isBlank() && pending.isEmpty() && currentAudioFile == null) || isGenerating.value) return
        // Local command: reminder scheduling/cancellation is handled on-device
        // without a model call.
        if (pending.isEmpty() && currentAudioFile == null && tryHandleReminderCommand(body)) return
        if (pending.isEmpty() && currentAudioFile == null && tryHandleImageCommand(body)) return
        if (pending.isEmpty() && currentAudioFile == null && tryHandleCompactCommand(body)) return
        if (pending.isEmpty() && currentAudioFile == null && tryHandleContainerCommand(body)) return
        if (pending.isEmpty() && currentAudioFile == null && tryHandleYoloCommand(body)) return
        // Set flag immediately to prevent double-send race condition
        isGenerating.value = true
        errorMessage.value = null
        val s = effectiveSettings()
        // Compare mode: stash the challenger pass; pass 1 runs on the active
        // model, pass 2 is chained from the generation finally-block.
        pendingComparePass = comparePassFor(s)
        genJob =
            viewModelScope.launch {
            var savedAudioFile: File? = currentAudioFile
            var audioBytes: ByteArray? = null
            if (savedAudioFile != null) {
                audioBytes = AttachmentPipeline.readBytesOffMain(savedAudioFile.absolutePath)
                clearSelectedAudio()
            }

            // Persist + process attachments: images downscaled, document text
            // extracted, PDFs rendered to page images.
            val prepared: List<PreparedAttachment> = AttachmentProcessor.prepare(app, pending)
            clearSelectedImage()

            val attachments = prepared.map { it.attachment }
            val imagePaths = AttachmentPipeline.imagePathsOf(attachments)
            // Inject extracted document text into the prompt.
            val promptText =
                AttachmentPipeline.buildPromptText(
                    body = body,
                    hasAudio = savedAudioFile != null,
                    prepared = prepared,
                )

            if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
                sendRemoteMessage(promptText, s, imagePaths, attachments)
                return@launch
            }

            val modelPath = ChatServerService.resolveModelPath(app, s)
            if (modelPath == null) {
                errorMessage.value = "No on-device model found. Please download a model or switch to Remote Provider."
                isGenerating.value = false
                return@launch
            }

            if (conversation == null) {
                conversation = Conversation().also { currentConversationId = it.id }
                conversation?.personaId = activePersona.value.id
            }
            // Local engine supports a single vision image: use the first one.
            val firstImageBytes = AttachmentPipeline.readBytesOffMain(imagePaths.firstOrNull())
            val userEntry =
                ChatMessageEntry(
                    role = ChatConstants.ROLE_USER,
                    content = promptText,
                    imagePath = imagePaths.firstOrNull(),
                    audioPath = savedAudioFile?.absolutePath,
                    attachments = attachments,
                    parentId = messages.lastOrNull()?.id,
                )
            messages.add(userEntry)
            registerActiveChild(userEntry)

            val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = userEntry.id)
            messages.add(assistant)
            registerActiveChild(assistant)
            updateContextTokens()

            var lastMetrics: LiteRtEngineManager.InferenceMetrics? = null
            var ragSources: List<String>? = null

            val targetChatId = currentConversationId
            // Single coroutine for the whole send flow: previously a redundant
            // nested launch overwrote genJob mid-flight, so stopGeneration()
            // could miss the outer job.
            var localBlankFailed = false
            try {
                val pipelineCtx =
                    GenerationCtx(
                        s = s,
                        modelPath = modelPath,
                        ragQuery = promptText,
                        imagePath = imagePaths.firstOrNull(),
                        imageBytes = firstImageBytes,
                        audioPath = savedAudioFile?.absolutePath,
                        audioBytes = audioBytes,
                        targetChatId = targetChatId,
                        onMetrics = { lastMetrics = it },
                        onRagSources = { ragSources = it },
                    )
                val outcome = streamWithThinkingFallback(pipelineCtx)
                localBlankFailed = outcome.blankFailed
                val effectiveSystem = outcome.effectiveSystem
                val history = outcome.history

                ragSources = handleAutonomousToolCall(
                    modelPath = modelPath,
                    s = s,
                    targetChatId = targetChatId,
                    history = history,
                    effectiveSystem = effectiveSystem,
                    currentSources = ragSources,
                    onMetricsUpdate = { lastMetrics = it },
                )
                    } catch (e: Exception) {
                        // Drop compare state: a failed/cancelled/switched pass 1 must not chain pass 2.
                        pendingComparePass = null
                        if (e is CancellationException || currentConversationId != targetChatId) return@launch
                        KLog.e("ChatVM", "Generation failed", e)
                        if (messages.lastOrNull()?.content?.isBlank() == true) {
                            messages.removeAt(messages.lastIndex)
                        }
                        errorMessage.value = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        // Consume compare state up-front: a conversation switch
                        // mid-generation must not leak a stale pass into a
                        // later, unrelated generation.
                        val pass2 = pendingComparePass
                        val merge = pendingCompareMerge
                        pendingComparePass = null
                        pendingCompareMerge = null
                        if (currentConversationId == targetChatId) {
                            if (lastMetrics != null && messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                                val idx = messages.lastIndex
                                val current = messages[idx]
                                val variants = if (current.variants.orEmpty().isEmpty()) listOf(current.content) else current.variants
                                messages[idx] = current.copy(stats = lastMetrics.displayBadge, variants = variants, sources = ragSources)
                            }
                            // Double-blank after the no-thinking retry: drop the
                            // empty bubble instead of persisting it. Runs before
                            // the compare-mode chaining below, which (if armed)
                            // still produces its own challenger message.
                            if (localBlankFailed &&
                                messages.isNotEmpty() &&
                                messages.last().role == ChatConstants.ROLE_ASSISTANT &&
                                stripThinking(messages.last().content).isBlank()
                            ) {
                                messages.removeAt(messages.lastIndex)
                            }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())

                        // Check if model called generate_image tool
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val lastIdx = messages.lastIndex
                            val genericCall = com.raikaai.app.skills.ToolCallParser.parseGenericCall(messages[lastIdx].content)
                            if (genericCall != null && genericCall.name.equals("generate_image", ignoreCase = true)) {
                                val imgPrompt = when {
                                    genericCall.arguments.has("prompt") -> genericCall.arguments.get("prompt").asString
                                    genericCall.arguments.has("description") -> genericCall.arguments.get("description").asString
                                    genericCall.arguments.has("query") -> genericCall.arguments.get("query").asString
                                    else -> ""
                                }
                                if (imgPrompt.isNotBlank()) {
                                    executeImageToolCall(lastIdx, imgPrompt)
                                }
                            }
                        }
                            when {
                                // Compare pass 1 done: stay in generating state, run the challenger.
                                pass2 != null -> {
                                    pendingCompareMerge = pass2
                                    runComparePass2(pass2)
                                }
                                // Compare pass 2 done: fold the challenger output into A's variants.
                                merge != null -> {
                                    isGenerating.value = false
                                    mergeLastAssistantAsVariant(merge)
                                }
                                else -> isGenerating.value = false
                            }
                        }
                    }
        }
    }

    /**
     * Encodes a persisted attachment image as an OpenAI-style
     * `data:image/jpeg;base64,...` URL for remote vision models.
     * The persisted file is already a downscaled JPEG (≤1024px, q85).
     */
    private suspend fun encodeImageDataUrl(imagePath: String?): String? =
        withContext(Dispatchers.IO) {
            if (imagePath.isNullOrBlank()) return@withContext null
            try {
                val file = File(imagePath)
                if (!file.exists() || file.length() == 0L || file.length() > AppLimits.REMOTE_IMAGE_MAX_BYTES) return@withContext null
                "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            } catch (t: Throwable) {
                KLog.w("ChatVM", "Failed to encode image for remote: ${t.message}")
                null
            }
        }

    private fun sendRemoteMessage(
        body: String,
        s: Settings,
        imagePaths: List<String> = emptyList(),
        attachments: List<ChatAttachment> = emptyList(),
    ) {
        if (s.remoteBaseUrl.isBlank() || s.remoteModelId.isBlank()) {
            errorMessage.value = "Please configure Remote Base URL and select a model in Models tab."
            // B1: sendMessage() already set isGenerating=true before calling us;
            // reset it so the send button doesn't get stuck as a Stop button.
            isGenerating.value = false
            return
        }
        if (conversation == null) {
            conversation = Conversation().also { currentConversationId = it.id }
            conversation?.personaId = activePersona.value.id
        }

        // Regenerate/edit flows pass a blank body to reuse the existing user
        // message — don't append an empty user node in that case.
        val userEntry =
            if (body.isBlank()) {
                messages.lastOrNull { it.role == ChatConstants.ROLE_USER }
                    ?: run {
                        errorMessage.value = "No user message to regenerate."
                        return
                    }
            } else {
                ChatMessageEntry(
                    role = ChatConstants.ROLE_USER,
                    content = body,
                    imagePath = imagePaths.firstOrNull(),
                    attachments = attachments,
                    parentId = messages.lastOrNull()?.id,
                ).also {
                    messages.add(it)
                    registerActiveChild(it)
                }
            }

        val branchQuery = userEntry.content
        // Regenerate reuses the stored attachments of the existing message.
        val effectiveImagePaths =
            if (imagePaths.isNotEmpty()) {
                imagePaths
            } else {
                userEntry.attachments.orEmpty().filter { it.kind == AttachmentType.IMAGE }.map { it.path }
                    .ifEmpty { listOfNotNull(userEntry.imagePath) }
            }

        val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = userEntry.id)
        messages.add(assistant)
        registerActiveChild(assistant)
        updateContextTokens()
        isGenerating.value = true

        val start = System.currentTimeMillis()
        // NOTE: tokenCount used to count SSE chunks (one per delta), not
        // tokens — providers send whole sentences per chunk, so the badge
        // showed e.g. "17 tokens" for a 400-token reply. Throughput is now
        // measured from the provider's usage report (falling back to a
        // length-based estimate) and timed from the first streamed chunk.
        var firstChunkAt = 0L
        var usageCompletionTokens: Int? = null
        var promptTokensEstimate = 0
        val targetRemoteChatId = currentConversationId
        var ragSources: List<String>? = null
        var remoteFinishReason: String? = null

        genJob =
            viewModelScope.launch {
                try {
                    var systemContent = resolveSystemPrompt(s)
                    if (!s.enableThinking) {
                        val noThinkDirective = "Do NOT think or reason inside <think>...</think> tags. Provide the final response directly."
                        systemContent = if (systemContent.isNotBlank()) "$systemContent\n$noThinkDirective" else noThinkDirective
                    }
                    val (ragSystem, sources) = withRagSystem(systemContent, branchQuery, s)
                    ragSources = sources
                    // Remote context window: per-model override wins, otherwise
                    // the global remote default. The on-device setting is left
                    // untouched so switching back to local restores it as-is.
                    val remoteWindow = s.effectiveRemoteContextWindow(s.remoteModelId)
                    val localModelPath = ChatServerService.resolveModelPath(app, s)
                    val activeSummary =
                        runCatching { ensureContextFit(s, localModelPath, remoteWindow) }.getOrNull()
                    val appliedWindowRemote = !activeSummary.isNullOrBlank()
                    val finalSystem =
                        injectSummary(ragSystem, activeSummary, appliedWindowRemote)

                    val imageDataUrls = effectiveImagePaths.mapNotNull { encodeImageDataUrl(it) }
                    val history = mutableListOf<RemoteChatMessage>()
                    if (finalSystem.isNotBlank()) {
                        history.add(RemoteChatMessage(role = "system", text = finalSystem))
                    }
                    val windowed = promptWindowMessages()
                    windowed.dropLast(1).forEach {
                        history.add(RemoteChatMessage(role = it.role, text = it.content))
                    }
                    // Attach the image to the latest user message (last entry).
                    if (imageDataUrls.isNotEmpty() && history.isNotEmpty()) {
                        val last = history.last()
                        history[history.lastIndex] = last.copy(imageDataUrls = imageDataUrls)
                    }
                    // Hard-cap to the remote window so long conversations are
                    // trimmed client-side instead of failing with a 400 from
                    // the server.
                    val tokenCounts =
                        history.map { msg ->
                            estimateTokens(msg.text) + msg.imageDataUrls.size * REMOTE_IMAGE_TOKEN_ESTIMATE
                        }
                    val capped = capMessagesToWindow(history, tokenCounts, remoteWindow, s.maxTokens + 256)
                    promptTokensEstimate = tokenCounts.sum()

                    collectStreamIntoMessage(
                        deltas =
                            RemoteAiClient.streamChat(
                                baseUrl = s.remoteBaseUrl,
                                apiKey = s.remoteApiKey,
                                model = s.remoteModelId,
                                messages = capped,
                                temperature = effectiveTemperature(s),
                                topP = s.topP,
                                maxTokens = s.maxTokens,
                                onUsageTokens = { usageCompletionTokens = it },
                                onFinishReason = { remoteFinishReason = it },
                            ),
                        targetChatId = targetRemoteChatId,
                        onDelta = {
                            if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()
                        },
                    )
                    // One autonomous container round (mirrors generate_image handling
                    // in finally): execute + stream a single follow-up, never re-parsed.
                    handleRemoteContainerFollowUp(
                        s = s,
                        targetChatId = targetRemoteChatId,
                        finalSystem = finalSystem,
                        remoteWindow = remoteWindow,
                        onUsageTokens = { usageCompletionTokens = it },
                        onPromptCounted = { promptTokensEstimate = it },
                    )
                } catch (e: Exception) {
                    // Drop compare state: a failed/cancelled/switched pass 1 must not chain pass 2.
                    pendingComparePass = null
                    if (e is CancellationException || currentConversationId != targetRemoteChatId) return@launch
                    KLog.e("ChatVM", "Remote generation failed", e)
                    if (messages.lastOrNull()?.content?.isBlank() == true) {
                        messages.removeAt(messages.lastIndex)
                    }
                    errorMessage.value = "Remote generation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    // Consume compare state up-front (see sendMessage's finally).
                    val pass2 = pendingComparePass
                    val merge = pendingCompareMerge
                    pendingComparePass = null
                    pendingCompareMerge = null
                    if (currentConversationId == targetRemoteChatId) {
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val idx = messages.lastIndex
                            val current = messages[idx]
                            // Mirror the local guard: a stream that ended with no
                            // visible body (cut connection, all chunks malformed)
                            // must not persist as a silent empty bubble.
                            if (stripThinking(current.content).isBlank()) {
                                messages.removeAt(idx)
                                errorMessage.value = "Remote model returned an empty response. Please try again."
                            } else {
                                val outputTokens = usageCompletionTokens ?: estimateTokens(current.content)
                                val decodeStart = if (firstChunkAt > 0L) firstChunkAt else start
                                val duration = maxOf(0.1f, (System.currentTimeMillis() - decodeStart) / 1000f)
                                val tokSec = outputTokens / duration
                                // finish_reason=length means the PROVIDER cut the reply
                                // at its own cap (our max_tokens request was higher).
                                // Surfacing it answers "kepotong" complaints in-app.
                                val cutSuffix = if (remoteFinishReason == "length") " · provider limit" else ""
                                KLog.d(
                                    "ChatVM",
                                    "Remote done: requested max_tokens=${s.maxTokens}, " +
                                        "got $outputTokens, finish_reason=${remoteFinishReason ?: "?"}",
                                )
                                val badge = "%.1f tok/s · %d tokens · Remote$cutSuffix".format(tokSec, outputTokens)
                                val variants = if (current.variants.orEmpty().isEmpty()) listOf(current.content) else current.variants
                                messages[idx] = current.copy(stats = badge, variants = variants, sources = ragSources)
                                recordRemoteCost(s.remoteModelId, promptTokensEstimate, outputTokens)
                            }
                        }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())

                        // Check if model called generate_image tool
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val lastIdx = messages.lastIndex
                            val genericCall = com.raikaai.app.skills.ToolCallParser.parseGenericCall(messages[lastIdx].content)
                            if (genericCall != null && genericCall.name.equals("generate_image", ignoreCase = true)) {
                                val imgPrompt = when {
                                    genericCall.arguments.has("prompt") -> genericCall.arguments.get("prompt").asString
                                    genericCall.arguments.has("description") -> genericCall.arguments.get("description").asString
                                    genericCall.arguments.has("query") -> genericCall.arguments.get("query").asString
                                    else -> ""
                                }
                                if (imgPrompt.isNotBlank()) {
                                    executeImageToolCall(lastIdx, imgPrompt)
                                }
                            }
                        }
                        when {
                            pass2 != null -> {
                                pendingCompareMerge = pass2
                                runComparePass2(pass2)
                            }
                            merge != null -> {
                                isGenerating.value = false
                                mergeLastAssistantAsVariant(merge)
                            }
                            else -> isGenerating.value = false
                        }
                    }
                }
            }
    }

    // ── Chat UX: Edit User Message & Swiping Variants ────────────────

    /**
     * OpenCode-style agent loop on the remote path, bounded to 3 tool rounds:
     * parse → policy gate (ASK → approval) → run → follow-up stream, then
     * re-parse the follow-up for the next call. Stops on no-call, denial
     * handling, conversation switch, or round cap.
     */
    private suspend fun handleRemoteContainerFollowUp(
        s: Settings,
        targetChatId: String?,
        finalSystem: String,
        remoteWindow: Int,
        onUsageTokens: (Int) -> Unit,
        onPromptCounted: (Int) -> Unit,
    ) {
        val idx = messages.lastIndex
        if (idx < 0 || messages[idx].role != ChatConstants.ROLE_ASSISTANT) return
        if (currentConversationId != targetChatId) return
        val firstCall = com.raikaai.app.skills.ToolCallParser.parseGenericCall(messages[idx].content) ?: return
        if (!isContainerToolCall(firstCall.name)) return
        if (!containerReady(s)) {
            errorMessage.value = "Linux Environment not ready — open Models Hub → Linux Environment → Install/Update, then retry."
            KLog.w("ChatVM", "Remote container call skipped: environment not ready")
            return
        }
        var rounds = 0
        val ranThisTurn = mutableMapOf<String, Int>()
        val executedBlocks = mutableListOf<String>()
        // True agent loop: each round's follow-up becomes the next round's
        // input, so the model chains ls -> cat -> run -> verify by itself.
        while (rounds < MAX_CONTAINER_ROUNDS) {
            if (currentConversationId != targetChatId) return
            val call = com.raikaai.app.skills.ToolCallParser.parseGenericCall(messages[idx].content) ?: break
            if (!isContainerToolCall(call.name)) break
            rounds++
            KLog.d("ChatVM", "Remote container round $rounds/$MAX_CONTAINER_ROUNDS: ${call.name}")
            val dispatched = dispatchContainerTool(call.name, call.arguments, s.containerYolo, ranThisTurn) { status ->
                if (currentConversationId == targetChatId && messages[idx].role == ChatConstants.ROLE_ASSISTANT) {
                    messages[idx] = messages[idx].copy(content = status)
                }
            }
            dispatched.block?.let { executedBlocks.add(it) }
            val toolContext = dispatched.toolContext
            if (currentConversationId != targetChatId) return
            val windowed = promptWindowMessages().dropLast(1)
            val lastUser = windowed.indexOfLast { it.role == ChatConstants.ROLE_USER }
            val follow = mutableListOf<RemoteChatMessage>()
            if (finalSystem.isNotBlank()) follow.add(RemoteChatMessage(role = "system", text = finalSystem))
            windowed.forEachIndexed { i, m ->
                val text = if (i == lastUser) "${m.content}\n\n$toolContext" else m.content
                follow.add(RemoteChatMessage(role = m.role, text = text))
            }
            val counts = follow.map { estimateTokens(it.text) }
            val cappedFollow = capMessagesToWindow(follow, counts, remoteWindow, s.maxTokens + 256)
            onPromptCounted(counts.sum())
            messages[idx] = messages[idx].copy(content = "")
            var followFinishReason: String? = null
            collectStreamIntoMessage(
                deltas =
                    RemoteAiClient.streamChat(
                        baseUrl = s.remoteBaseUrl,
                        apiKey = s.remoteApiKey,
                        model = s.remoteModelId,
                        messages = cappedFollow,
                        temperature = effectiveTemperature(s),
                        topP = s.topP,
                        maxTokens = s.maxTokens,
                        onUsageTokens = onUsageTokens,
                        onFinishReason = { followFinishReason = it },
                    ),
                targetChatId = targetChatId,
                onDelta = {},
            )
            followFinishReason?.let { KLog.d("ChatVM", "Remote container follow-up finish_reason=$it") }
        }
        if (rounds >= MAX_CONTAINER_ROUNDS && currentConversationId == targetChatId &&
            messages[idx].role == ChatConstants.ROLE_ASSISTANT
        ) {
            val more = com.raikaai.app.skills.ToolCallParser.parseGenericCall(messages[idx].content)
            if (more != null && isContainerToolCall(more.name)) {
                messages[idx] = messages[idx].copy(
                    content = messages[idx].content +
                        "\n\n_(Stopped after $MAX_CONTAINER_ROUNDS tool rounds — ask me to continue if needed.)_",
                )
            }
        }
        // Verbose mode: keep every executed command + output visible above the summary.
        if (executedBlocks.isNotEmpty() && idx < messages.size &&
            messages[idx].role == ChatConstants.ROLE_ASSISTANT
        ) {
            messages[idx] = messages[idx].copy(
                content = executedBlocks.joinToString("\n\n") + "\n\n" + messages[idx].content,
            )
        }
    }

    /**
     * Edits a user message by forking a new branch at that point. The old
     * branch (original prompt and everything after it) is preserved in the
     * tree and remains reachable via the branch navigation arrows.
     */
    fun editUserMessageAndRegenerate(index: Int, newText: String) {
        if (index < 0 || index >= messages.size || isGenerating.value) return
        stopGeneration()
        val oldMsg = messages[index]
        if (oldMsg.role != ChatConstants.ROLE_USER) return
        val s = effectiveSettings()

        val payload = AttachmentPipeline.extractAttachedFilesPayload(oldMsg.content)
        val fullContent = if (payload.isNotBlank()) newText.trim() + payload else newText.trim()

        val forked =
            oldMsg.copy(
                content = fullContent,
                timestamp = System.currentTimeMillis(),
                stats = null,
                variants = emptyList(),
                selectedVariant = 0,
                id = UUID.randomUUID().toString(),
                sources = null,
            )
        nodes.add(forked)
        registerActiveChild(forked)

        while (messages.size > index) {
            messages.removeAt(messages.lastIndex)
        }
        messages.add(forked)

        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            sendRemoteMessage("", s)
            return
        }
        val modelPath = ChatServerService.resolveModelPath(app, s) ?: return

        val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = forked.id)
        messages.add(assistant)
        registerActiveChild(assistant)
        isGenerating.value = true

        val editImagePathStr = oldMsg.imagePath
        val editAudioPathStr = oldMsg.audioPath

        var lastMetrics: LiteRtEngineManager.InferenceMetrics? = null
        var ragSources: List<String>? = null
        val targetChatId = currentConversationId

        genJob =
            viewModelScope.launch {
                // Disk reads stay off the main thread (large camera photos).
                val imageBytes = AttachmentPipeline.readBytesOffMain(editImagePathStr)
                val audioBytes = AttachmentPipeline.readBytesOffMain(editAudioPathStr)
                try {
                    val pipelineCtx =
                        GenerationCtx(
                            s = s,
                            modelPath = modelPath,
                            ragQuery = newText.trim(),
                            imagePath = oldMsg.imagePath,
                            imageBytes = imageBytes,
                            audioPath = oldMsg.audioPath,
                            audioBytes = audioBytes,
                            targetChatId = targetChatId,
                            onMetrics = { lastMetrics = it },
                            onRagSources = { ragSources = it },
                        )
                    val (effectiveSystem, history, _) = runGenerationPipeline(pipelineCtx, s.enableThinking)

                    ragSources = handleAutonomousToolCall(
                        modelPath = modelPath,
                        s = s,
                        targetChatId = targetChatId,
                        history = history,
                        effectiveSystem = effectiveSystem,
                        currentSources = ragSources,
                        onMetricsUpdate = { lastMetrics = it },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException || currentConversationId != targetChatId) return@launch
                    KLog.e("ChatVM", "Edit generation failed", e)
                    if (messages.lastOrNull()?.content?.isBlank() == true) {
                        messages.removeAt(messages.lastIndex)
                    }
                    errorMessage.value = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    if (currentConversationId == targetChatId) {
                        isGenerating.value = false
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val idx = messages.lastIndex
                            val current = messages[idx]
                            messages[idx] =
                                current.copy(
                                    stats = lastMetrics?.displayBadge,
                                    variants = listOf(current.content),
                                    sources = ragSources,
                                )
                        }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())
                    }
                }
            }
    }

    fun regenerateLastResponse() {
        regenerateInternal(sOverride = null, keepExisting = false, allowWhileGenerating = false)
    }

    /**
     * Regenerate flow shared by the UI button and compare-mode pass 2.
     *
     * @param sOverride settings to generate with (default: effective settings).
     * @param keepExisting when true the trailing assistant message is kept and
     * the new output is appended after it (compare mode) instead of replacing it.
     * @param allowWhileGenerating bypass the [isGenerating] guard — compare mode
     * chains pass 2 while the flag is still true.
     */
    private fun regenerateInternal(
        sOverride: Settings?,
        keepExisting: Boolean,
        allowWhileGenerating: Boolean,
    ) {
        if ((!allowWhileGenerating && isGenerating.value) || messages.isEmpty()) return
        val lastAssistant = messages.lastOrNull { it.role == ChatConstants.ROLE_ASSISTANT }
        val existingVariants = lastAssistant?.variants?.toMutableList() ?: mutableListOf()
        if (lastAssistant != null && lastAssistant.content.isNotBlank() && !existingVariants.contains(lastAssistant.content)) {
            existingVariants.add(lastAssistant.content)
        }

        val lastUser = messages.lastOrNull { it.role == ChatConstants.ROLE_USER } ?: return
        val s = sOverride ?: effectiveSettings()

        // Validate before touching the message list: a failed validation must
        // not wipe the currently visible response.
        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            if (s.remoteBaseUrl.isBlank() || s.remoteModelId.isBlank()) {
                errorMessage.value = "Please configure Remote Base URL and select a model in Models tab."
                return
            }
            if (!keepExisting) removeTrailingAssistantMessage()
            sendRemoteMessage("", s)
            return
        }

        val modelPath = ChatServerService.resolveModelPath(app, s)
        if (modelPath == null) {
            errorMessage.value = "No model found to regenerate response."
            return
        }
        if (!keepExisting) removeTrailingAssistantMessage()

        val imagePathStr = lastUser.imagePath
        val audioPathStr = lastUser.audioPath

        val assistant = ChatMessageEntry(role = ChatConstants.ROLE_ASSISTANT, content = "", parentId = lastUser.id)
        messages.add(assistant)
        registerActiveChild(assistant)
        updateContextTokens()
        isGenerating.value = true

        var lastMetrics: LiteRtEngineManager.InferenceMetrics? = null
        var ragSources: List<String>? = null
        val targetRegenChatId = currentConversationId

        genJob =
            viewModelScope.launch {
                var regenBlankFailed = false
                // Disk reads stay off the main thread (large camera photos).
                val imageBytes = AttachmentPipeline.readBytesOffMain(imagePathStr)
                val audioBytes = AttachmentPipeline.readBytesOffMain(audioPathStr)
                try {
                    val pipelineCtx =
                        GenerationCtx(
                            s = s,
                            modelPath = modelPath,
                            ragQuery = lastUser.content,
                            imagePath = lastUser.imagePath,
                            imageBytes = imageBytes,
                            audioPath = lastUser.audioPath,
                            audioBytes = audioBytes,
                            targetChatId = targetRegenChatId,
                            onMetrics = { lastMetrics = it },
                            onRagSources = { ragSources = it },
                        )
                    val outcome = streamWithThinkingFallback(pipelineCtx)
                    regenBlankFailed = outcome.blankFailed
                    val effectiveSystem = outcome.effectiveSystem
                    val history = outcome.history

                    ragSources = handleAutonomousToolCall(
                        modelPath = modelPath,
                        s = s,
                        targetChatId = targetRegenChatId,
                        history = history,
                        effectiveSystem = effectiveSystem,
                        currentSources = ragSources,
                        onMetricsUpdate = { lastMetrics = it },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException || currentConversationId != targetRegenChatId) return@launch
                    KLog.e("ChatVM", "Regen generation failed", e)
                    if (messages.lastOrNull()?.content?.isBlank() == true) {
                        messages.removeAt(messages.lastIndex)
                    }
                    errorMessage.value = "Generation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    // Consume compare-merge state up-front (see sendMessage's finally).
                    val merge = pendingCompareMerge
                    pendingCompareMerge = null
                    if (currentConversationId == targetRegenChatId) {
                        // Double-blank after the no-thinking retry: drop the
                        // empty bubble instead of persisting it with an empty
                        // variant. Partial user-stopped output is untouched:
                        // regenBlankFailed is only set by the retry path.
                        if (regenBlankFailed &&
                            messages.isNotEmpty() &&
                            messages.last().role == ChatConstants.ROLE_ASSISTANT &&
                            stripThinking(messages.last().content).isBlank()
                        ) {
                            messages.removeAt(messages.lastIndex)
                        }
                        if (messages.isNotEmpty() && messages.last().role == ChatConstants.ROLE_ASSISTANT) {
                            val idx = messages.lastIndex
                            val current = messages[idx]
                            val updatedVariants = existingVariants + current.content
                            messages[idx] =
                                current.copy(
                                    stats = lastMetrics?.displayBadge,
                                    variants = updatedVariants,
                                    selectedVariant = updatedVariants.lastIndex,
                                    sources = ragSources,
                                )
                        }
                        persist()
                        captureArtifacts(messages.lastOrNull()?.id, messages.lastOrNull()?.content.orEmpty())
                        if (merge != null) {
                            mergeLastAssistantAsVariant(merge)
                        }
                        isGenerating.value = false
                    }
                }
            }
    }

    fun selectMessageVariant(messageIndex: Int, variantIndex: Int) {
        if (messageIndex < 0 || messageIndex >= messages.size) return
        val msg = messages[messageIndex]
        val vs = msg.variants.orEmpty()
        if (variantIndex < 0 || variantIndex >= vs.size) return
        messages[messageIndex] = msg.copy(
            content = vs[variantIndex],
            selectedVariant = variantIndex,
        )
        persist()
    }

    private fun persist() {
        val conv = conversation ?: return
        // O(n) merge by id (was: indexOfFirst per message = O(n^2)).
        val byId = nodes.associateBy { it.id }.toMutableMap()
        messages.forEach { m ->
            val id = m.id ?: return@forEach
            byId[id] = m
        }
        nodes = byId.values.toMutableList()
        // Prune unreachable nodes: keep the active path plus their siblings
        // (branch-switchable). Anything else can never be reached by
        // resolveActivePath/siblingsOf and would bloat the file forever.
        // Skipped while messages is empty — with no active path every node
        // would look unreachable.
        if (messages.isNotEmpty()) {
            val activeIds = messages.mapNotNull { it.id }.toSet()
            val activeParents = messages.map { it.parentId ?: ChatRepository.ROOT_KEY }.toSet()
            val pruned = nodes.filter { node ->
                val id = node.id
                id != null && (id in activeIds || (node.parentId ?: ChatRepository.ROOT_KEY) in activeParents)
            }
            if (pruned.size != nodes.size) {
                nodes = pruned.toMutableList()
            }
        }
        conv.messages = nodes.toList()
        // Lazy persistence: a brand-new session is only written to disk once it
        // contains at least one real user message. This keeps Delete All /
        // New Conversation from leaving empty "New Chat" entries behind.
        val hasRealContent = conv.messages.any { it.role == ChatConstants.ROLE_USER }
        viewModelScope.launch {
            if (!hasRealContent && !chatRepo.exists(conv.id)) return@launch
            chatRepo.save(conv)
            refreshConversations()
        }
    }

    // ── Sampler Presets & Per-Model Parameters ───────────────────────

    fun applySamplerPreset(preset: String) {
        when (preset.lowercase()) {
            "precise" -> {
                setTemperature(0.1f)
                setTopK(20)
                setTopP(0.80f)
                setMaxTokens(1024)
            }
            "creative" -> {
                setTemperature(0.8f)
                setTopK(60)
                setTopP(0.95f)
                setMaxTokens(2048)
            }
            else -> { // balanced
                setTemperature(0.3f)
                setTopK(40)
                setTopP(0.90f)
                setMaxTokens(2048)
            }
        }
    }

    fun setContextWindowTokens(tokens: Int) = launchSetting {
        settingsRepo.setContextWindowTokens(tokens)
        if (settings.value.remoteModelId.isNotBlank()) {
            settingsRepo.clearRemoteModelContextWindow(settings.value.remoteModelId)
        }
    }

    fun setPromptTemplateFormat(format: String) = launchSetting { settingsRepo.setPromptTemplateFormat(format) }

    fun setModelSource(source: String) = launchSetting { settingsRepo.setModelSource(source) }

    fun setRemoteBaseUrl(url: String) = launchSetting { settingsRepo.setRemoteBaseUrl(url) }

    fun setRemoteApiKey(key: String) = launchSetting { settingsRepo.setRemoteApiKey(key) }

    fun setRemoteImageModelId(id: String) = launchSetting { settingsRepo.setRemoteImageModelId(id) }

    fun setRemoteContextWindowTokens(tokens: Int) =
        launchSetting { settingsRepo.setRemoteContextWindowTokens(tokens) }

    fun clearRemoteModelContextWindow(modelId: String) =
        launchSetting { settingsRepo.clearRemoteModelContextWindow(modelId) }

    fun setCompareMode(enabled: Boolean) = launchSetting { settingsRepo.setCompareMode(enabled) }

    fun setCompareChallenger(source: String, modelId: String) =
        launchSetting { settingsRepo.setCompareChallenger(source, modelId) }

    /** Short display name of the currently active model (compare label A). */
    private fun activeModelLabel(s: Settings): String =
        if (s.modelSource == ChatConstants.SOURCE_REMOTE) {
            "[Remote] ${s.remoteModelId.ifBlank { "remote" }}"
        } else {
            val path = ChatServerService.resolveModelPath(app, s)
            val name = path?.let { File(it).nameWithoutExtension }.orEmpty()
            "[On-device] ${name.ifBlank { s.activeModelId.ifBlank { "local" } }}"
        }

    /**
     * Builds the challenger pass for compare mode, or null when compare is
     * off / misconfigured / identical to the active model.
     */
    private fun comparePassFor(s: Settings): ComparePass? {
        if (!s.compareMode) return null
        val labelA = activeModelLabel(s)
        return when (s.compareSource) {
            ChatConstants.SOURCE_REMOTE -> {
                if (s.compareModelId.isBlank() || s.remoteBaseUrl.isBlank()) return null
                if (s.modelSource == ChatConstants.SOURCE_REMOTE && s.compareModelId == s.remoteModelId) return null
                ComparePass(
                    settings = s.copy(modelSource = ChatConstants.SOURCE_REMOTE, remoteModelId = s.compareModelId),
                    labelA = labelA,
                    labelB = "[Remote] ${s.compareModelId}",
                )
            }
            ChatConstants.SOURCE_LOCAL -> {
                val path =
                    s.compareModelId.ifBlank { ChatServerService.resolveModelPath(app, s) }
                        ?: return null
                val curPath = ChatServerService.resolveModelPath(app, s)
                if (s.modelSource == ChatConstants.SOURCE_LOCAL && path == curPath) return null
                ComparePass(
                    settings = s.copy(modelSource = ChatConstants.SOURCE_LOCAL, customModelPath = path),
                    labelA = labelA,
                    labelB = "[On-device] ${File(path).nameWithoutExtension.ifBlank { "local" }}",
                )
            }
            else -> null
        }
    }

    /**
     * Starts compare pass 2: reuses the last user message and generates with
     * the challenger settings. The resulting assistant message is merged as
     * a variant of pass 1's message in the generation finally-block.
     * Must be called with [isGenerating] still true.
     */
    private fun runComparePass2(pass: ComparePass) {
        val jobBefore = genJob
        regenerateInternal(sOverride = pass.settings, keepExisting = true, allowWhileGenerating = true)
        if (genJob == null || genJob === jobBefore) {
            // Validation failed inside regenerateInternal: nothing launched.
            // Bail out of compare cleanly instead of leaving a stale merge.
            isGenerating.value = false
            if (errorMessage.value.isNullOrBlank()) {
                errorMessage.value = "Compare challenger unavailable."
            }
        } else {
            pendingCompareMerge = pass
        }
    }

    /**
     * Merges the last assistant message (compare pass 2 output) into the
     * previous assistant message as an extra variant, then drops the orphaned
     * node so reloads keep a single message with a 1/2 switcher.
     */
    private fun mergeLastAssistantAsVariant(pass: ComparePass) {
        if (messages.size < 2) return
        val b = messages.removeAt(messages.lastIndex)
        if (b.role != ChatConstants.ROLE_ASSISTANT) {
            messages.add(b)
            return
        }
        val aIdx = messages.lastIndex
        if (aIdx < 0) {
            messages.add(b)
            return
        }
        val a = messages[aIdx]
        if (a.role != ChatConstants.ROLE_ASSISTANT) {
            messages.add(b)
            return
        }
        nodes.removeAll { it.id == b.id }
        if (b.content.isBlank()) {
            // Challenger produced nothing (e.g. stopped): just drop it.
            registerActiveChild(a)
            persist()
            return
        }
        val variants = if (a.variants.isEmpty()) listOf(a.content) else a.variants
        val merged =
            a.copy(
                variants = variants + b.content,
                stats = "${a.stats} · A/B: ${pass.labelA} vs ${pass.labelB}",
            )
        messages[aIdx] = merged
        registerActiveChild(merged)
        persist()
    }

    // ── MCP client ────────────────────────────────────────────────────

    private val mcpManager get() = com.raikaai.app.mcp.McpManager.getInstance(app)
    val mcpServers: StateFlow<List<com.raikaai.app.mcp.McpServerConfig>> get() = mcpManager.servers
    val mcpToolsByServer: StateFlow<Map<String, List<com.raikaai.app.mcp.McpTool>>> get() = mcpManager.toolsByServer
    val mcpToolErrors: StateFlow<Map<String, String>> get() = mcpManager.errors

    /**
     * Explicit safety boundary for autonomous MCP tool calls: when the model
     * emits a tool call, generation suspends here until the user approves or
     * denies it in the UI. Never auto-approved.
     */
    val pendingMcpApproval = mutableStateOf<com.raikaai.app.mcp.McpCallRequest?>(null)
    private var mcpApproval: CompletableDeferred<Boolean>? = null

    /**
     * Explicit safety boundary for autonomous container tool calls
     * (execute_in_container / ensure_package). Mirrors the MCP boundary:
     * ASK-policy calls suspend here until the user approves in the UI.
     * ALLOW-policy calls run without prompting; DENY never runs.
     */
    data class ContainerCallRequest(val command: String)
    val pendingContainerApproval = mutableStateOf<ContainerCallRequest?>(null)
    private var containerApproval: CompletableDeferred<Boolean>? = null

    /** Called from the approval dialog. */
    fun respondContainerApproval(approved: Boolean) {
        val d = containerApproval
        containerApproval = null
        pendingContainerApproval.value = null
        d?.complete(approved)
    }

    private fun denyContainerApproval() {
        val d = containerApproval
        containerApproval = null
        pendingContainerApproval.value = null
        d?.complete(false)
    }

    private suspend fun requestContainerApproval(command: String): Boolean {
        containerApproval?.complete(false)
        val d = CompletableDeferred<Boolean>()
        containerApproval = d
        pendingContainerApproval.value = ContainerCallRequest(command)
        return try {
            d.await()
        } finally {
            if (containerApproval === d) {
                containerApproval = null
                pendingContainerApproval.value = null
            }
        }
    }

    fun setMcpEnabled(enabled: Boolean) = launchSetting { settingsRepo.setMcpEnabled(enabled) }

    fun upsertMcpServer(
        server: com.raikaai.app.mcp.McpServerConfig,
        authToken: String? = null,
    ) = viewModelScope.launch(Dispatchers.IO) { settingsRepo.upsertMcpServer(server, authToken) }

    fun removeMcpServer(serverId: String) =
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.removeMcpServer(serverId) }

    fun setMcpServerEnabled(server: com.raikaai.app.mcp.McpServerConfig, enabled: Boolean) =
        upsertMcpServer(server.copy(enabled = enabled))

    fun refreshMcpTools() =
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mcpManager.refreshAllEnabled() }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        errorMessage.value = "MCP discovery failed: ${e.message}"
                    }
                }
        }

    fun refreshMcpServerTools(server: com.raikaai.app.mcp.McpServerConfig) =
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mcpManager.refreshTools(server) }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        errorMessage.value = "MCP '${server.name}': ${e.message}"
                    }
                }
        }

    /**
     * Manual tool invocation from the MCP screen. This is an explicit user
     * action, so it bypasses the approval gate used by autonomous calls.
     */
    fun testMcpCall(
        serverId: String,
        toolName: String,
        argsJson: String,
        onResult: (String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                runCatching {
                    val args =
                        runCatching {
                            com.google.gson.JsonParser.parseString(argsJson.ifBlank { "{}" }).asJsonObject
                        }.getOrElse { com.google.gson.JsonObject() }
                    mcpManager.callTool(serverId, toolName, args)
                }
            withContext(Dispatchers.Main) {
                onResult(
                    result.fold(
                        onSuccess = {
                            if (it.isError) "Tool returned an error:\n${it.text}" else "Result:\n${it.text}"
                        },
                        onFailure = { "Call failed: ${it.message}" },
                    ),
                )
            }
        }
    }

    /** Called from the approval dialog. */
    fun respondMcpApproval(approved: Boolean) {
        val d = mcpApproval
        mcpApproval = null
        pendingMcpApproval.value = null
        d?.complete(approved)
    }

    /** Denies any pending approval (stop / conversation switch). */
    private fun denyMcpApproval() {
        val d = mcpApproval
        mcpApproval = null
        pendingMcpApproval.value = null
        d?.complete(false)
    }

    private suspend fun requestMcpApproval(req: com.raikaai.app.mcp.McpCallRequest): Boolean {
        // Deny a stale request before arming a new one.
        mcpApproval?.complete(false)
        val d = CompletableDeferred<Boolean>()
        mcpApproval = d
        pendingMcpApproval.value = req
        return try {
            d.await()
        } finally {
            // Only clear if this request is still the current one — a newer
            // request may have armed itself while this one was settling.
            if (mcpApproval === d) {
                mcpApproval = null
                pendingMcpApproval.value = null
            }
        }
    }

    /**
     * Builds the MCP tools section injected into the system prompt for local
     * generation. Capped so small-context on-device models aren't starved.
     * Remote providers are excluded: their tool calls have no executor here.
     */
    private fun mcpToolsPromptBlock(s: Settings): String {
        if (!s.mcpEnabled || s.modelSource != ChatConstants.SOURCE_LOCAL) return ""
        val tools = mcpManager.allEnabledTools().take(12)
        if (tools.isEmpty()) return ""
        return buildString {
            append("\n\n[MCP TOOLS]\n")
            append("You may call tools hosted on the user's connected MCP servers. To call one, emit EXACTLY one block and then stop:\n")
            append("<tool_call>{\"name\": \"<tool_name>\", \"arguments\": {…}}</tool_call>\n")
            append("The user must approve each call; after approval the tool result is provided and you continue. Never invent tool results.\n")
            append("Available tools:\n")
            tools.forEach { t ->
                append("- ${t.signature}")
                val desc = t.description.take(160)
                if (desc.isNotBlank()) append(": $desc")
                append("  [server: ${t.serverName}]\n")
            }
        }.take(2500)
    }

    /**
     * Builds the Linux container section injected into the system prompt for
     * local generation. Only present when the environment is installed.
     */
    /**
     * Filesystem truth with settings fallback — the settings flag can go stale
     * after a failed install while the marker tells the truth. Heals the flag
     * when they disagree so prompt, tools, and UI converge.
     */
    private fun containerReady(s: Settings): Boolean {
        if (containerManager.isInstalled()) {
            if (!s.alpineInstalled) launchSetting { settingsRepo.setAlpineInstalled(true) }
            return true
        }
        return s.alpineInstalled
    }

    private fun containerToolsPromptBlock(s: Settings): String {
        if (!containerReady(s)) return ""
        return "\n\n[CONTAINER TOOLS]\n" +
            "You have a Linux (Alpine) sandbox with a persistent /workspace folder. " +
            "To run a shell command, emit EXACTLY one block and then stop:\n" +
            "<tool_call>{\"name\": \"execute_in_container\", \"arguments\": {\"command\": \"<shell>\"}}</tool_call>\n" +
            "Keep the ENTIRE tool call on ONE single line — never insert line breaks inside it, " +
            "even for long commands. Correct examples:\n" +
            "<tool_call>{\"name\": \"execute_in_container\", \"arguments\": {\"command\": \"ls /workspace\"}}</tool_call>\n" +
            "<tool_call>{\"name\": \"execute_in_container\", \"arguments\": {\"command\": \"unzip -l '/workspace/my file.zip'\"}}</tool_call>\n" +
            "Prefer single quotes for paths with spaces (simpler and always valid)."
            "To install a missing program, use:\n" +
            "<tool_call>{\"name\": \"ensure_package\", \"arguments\": {\"command\": \"apk add --no-cache <pkg>\"}}</tool_call>\n" +
            "Rules: only write files under /workspace; keep commands short and non-interactive; " +
            "never run rm -rf / or --allow-untrusted. Small official packages install automatically; " +
            "other installs ask the user first. Never invent command results. " +
            "Long-running servers: end the command with `&` and it keeps running detached " +
            "(e.g. `python3 server.py --port 8080 &`); verify with `wget -qO- http://127.0.0.1:PORT` " +
            "in your NEXT call — never curl/netstat/ss (not installed, use wget). " +
            "Manage servers with `list_daemons` and `stop_daemon` {\"id\"} tools. " +
            "Act directly: emit the tool call instead of asking the user what to run. " +
            "Respond concisely; do not narrate your reasoning steps. " +
            "When the user mentions a file, first run `ls /workspace` to locate it — " +
            "user files live ONLY in the phone's /workspace (never /mnt/data, /sdcard, or home). " +
            "Chat attachments are auto-mirrored to /workspace/chat_uploads/ with original filenames. " +
            "If the file isn't there, tell the user to add it via the Files tab Import button, then continue. " +
            "Always quote file paths (filenames may contain spaces): " +
            "unzip -l \"/workspace/my file.zip\", never bare."
    }

    /** Records the estimated USD cost of one remote generation (owned by ServerControls). */
    private fun recordRemoteCost(modelId: String, promptTokens: Int, completionTokens: Int) =
        serverControls.recordRemoteCost(modelId, promptTokens, completionTokens)

    // ── Model Management ─────────────────────────────────────────────

    fun loadActiveModel() {
        val s = settings.value
        val modelPath = ChatServerService.resolveModelPath(app, s) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.load(
                    LiteRtEngineManager.EngineParams(
                        modelPath = modelPath,
                        temperature = s.temperature,
                        topK = s.topK,
                        topP = s.topP,
                        maxTokens = s.maxTokens,
                        contextWindow = s.contextWindowTokens,
                        backend = s.backend,
                        systemPrompt = s.systemPrompt,
                    ),
                )
            } catch (e: Exception) {
                // Never let an uncaught exception kill the process (force close),
                // e.g. missing model file. Surface it as a regular error message.
                KLog.e("ChatViewModel", "loadActiveModel failed", e)
                withContext(Dispatchers.Main) {
                    errorMessage.value = "Failed to load model: ${e.message}"
                }
            }
        }
    }

    fun unloadActiveModel() {
        viewModelScope.launch(Dispatchers.IO) { engine.unload() }
    }

    fun startDownload(modelId: String) {
        LocalAiCatalog.getModelById(modelId)?.let { downloader.startDownload(it) }
    }

    fun startDownload(model: com.raikaai.app.localai.LocalAiModel) {
        downloader.startDownload(model)
    }

    fun downloadCustomUrl(downloadUrl: String, fileName: String? = null, expectedSizeBytes: Long = 0L, sha256: String? = null) {
        val fn = fileName?.ifBlank { null } ?: downloadUrl.substringAfterLast('/').substringBefore('?').ifBlank { "custom_model.litertlm" }
        val id = "custom_" + fn.hashCode()
        downloader.startCustomDownload(id, fn, downloadUrl, expectedSizeBytes, expectedSha256 = sha256)
    }

    fun cancelDownload(modelId: String) = downloader.cancelDownload(modelId)

    fun deleteModel(fileName: String) {
        if (loadedModelPath.value?.endsWith(fileName) == true) unloadActiveModel()
        modelManager.deleteFile(fileName)
        refreshInstalledModels()
    }

    fun deleteModel(model: com.raikaai.app.localai.LocalAiModel) {
        if (loadedModelPath.value?.endsWith(model.fileName) == true) unloadActiveModel()
        modelManager.deleteModel(model)
        refreshInstalledModels()
    }

    fun setActivePreset(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setActiveModelId(modelId)
            settingsRepo.setCustomModelPath("")
            settingsRepo.setModelSource(ChatConstants.SOURCE_LOCAL)
            engine.unload()
            refreshInstalledModels()
        }
    }

    fun importCustomModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = modelManager.importCustomModel(uri)
            if (path != null) {
                settingsRepo.setCustomModelPath(path)
                settingsRepo.setActiveModelId("")
                settingsRepo.setModelSource(ChatConstants.SOURCE_LOCAL)
                engine.unload()
                refreshInstalledModels()
            } else {
                withContext(Dispatchers.Main) {
                    errorMessage.value = "Failed to import model file"
                }
            }
        }
    }

    // ── Custom Personas CRUD (Character Card v2, owned by PersonaStore) ──

    fun addCustomPersona(
        name: String,
        iconType: String = "custom",
        systemPrompt: String,
        description: String = "",
        greeting: String = "",
        avatarUri: String = "",
        temperature: Float? = null,
        topK: Int? = null,
        personality: String = "",
        scenario: String = "",
        exampleDialogues: String = "",
    ) = personaStore.addCustomPersona(name, iconType, systemPrompt, description, greeting, avatarUri, temperature, topK, personality, scenario, exampleDialogues)

    fun deleteCustomPersona(id: String) = personaStore.deleteCustomPersona(id)

    fun upsertCustomPersona(persona: PersonaPreset) = personaStore.upsertCustomPersona(persona)

    suspend fun savePersonaAvatar(source: Uri): String? = personaStore.savePersonaAvatar(source)

    fun exportPersonaJson(persona: PersonaPreset): String = personaStore.exportPersonaJson(persona)

    fun importPersonaJson(jsonString: String): Boolean = personaStore.importPersonaJson(jsonString)

    fun importPersonaFromJson(jsonString: String): Boolean = personaStore.importPersonaFromJson(jsonString)

    fun applyPersona(persona: PersonaPreset) = personaStore.applyPersona(persona)

    /**
     * Effective system prompt for chat generation. The "general" character
     * keeps the legacy behavior: the global prompt editable in Chat Settings.
     * Any other character uses its own prompt via [CharacterPrompt].
     */
    private fun resolveSystemPrompt(s: Settings): String {
        val persona = activePersona.value
        if (persona.id == "general" || persona.id.isBlank()) return s.systemPrompt
        return CharacterPrompt.build(persona)
    }

    /** Effective sampler values: persona override wins, settings is the fallback. */
    private fun effectiveTemperature(s: Settings): Float = activePersona.value.temperature ?: s.temperature

    private fun effectiveTopK(s: Settings): Int = activePersona.value.topK ?: s.topK

    // ── Settings mutations ───────────────────────────────────────────

    fun resetParametersToRecommended() {
        launchSetting {
            settingsRepo.setTemperature(0.2f)
            settingsRepo.setTopK(40)
            settingsRepo.setTopP(0.90f)
            settingsRepo.setMaxTokens(512)
            settingsRepo.setContextWindowTokens(2048)
            settingsRepo.setPromptTemplateFormat("auto")
            settingsRepo.setBackend(ChatConstants.BACKEND_GPU)
        }
    }

    fun setTemperature(v: Float) = launchSetting { settingsRepo.setTemperature(v) }

    fun setTopK(v: Int) = launchSetting { settingsRepo.setTopK(v) }

    fun setTopP(v: Float) = launchSetting { settingsRepo.setTopP(v) }

    fun setMaxTokens(v: Int) = launchSetting { settingsRepo.setMaxTokens(v) }

    fun setBackend(v: String) = launchSetting { settingsRepo.setBackend(v) }

    fun setThemeMode(v: String) = launchSetting { settingsRepo.setThemeMode(v) }

    fun setThemeColor(v: Long) = launchSetting { settingsRepo.setThemeColor(v) }

    fun setPureBlack(v: Boolean) = launchSetting { settingsRepo.setPureBlack(v) }

    fun setHuggingFaceToken(v: String) = launchSetting { settingsRepo.setHuggingFaceToken(v) }

    fun setSystemPrompt(v: String) = launchSetting { settingsRepo.setSystemPrompt(v) }

    fun setEnableThinking(v: Boolean) = launchSetting { settingsRepo.setEnableThinking(v) }

    fun setShowTokensPerSec(v: Boolean) = launchSetting { settingsRepo.setShowTokensPerSec(v) }

    fun setAutoScroll(v: Boolean) = launchSetting { settingsRepo.setAutoScroll(v) }

    fun setSendOnEnter(v: Boolean) = launchSetting { settingsRepo.setSendOnEnter(v) }

    fun setKeepAwake(v: Boolean) = launchSetting { settingsRepo.setKeepAwake(v) }

    fun setContainerYolo(v: Boolean) = launchSetting { settingsRepo.setContainerYolo(v) }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

/**
 * Verbose transcript block for one executed sandbox command: stays visible
 * above the AI summary so every requested command and its output can be
 * reviewed without a special mode. Capped to stay context-cheap.
 */
internal fun formatContainerRunBlock(
    command: String,
    stdout: String,
    stderr: String,
    exitCode: Int,
    timedOut: Boolean,
    autoApproved: Boolean,
): String {
    val head = if (autoApproved) "🔧 `$command` · auto" else "🔧 `$command`"
    return buildString {
        append(head)
        append("\n```\n")
        val out = stdout.take(1000)
        val err = stderr.take(300)
        if (out.isBlank() && err.isBlank()) append("(no output)") else append(out)
        if (err.isNotBlank()) {
            append("\n[stderr]\n")
            append(err)
        }
        append("\nexit=$exitCode")
        if (timedOut) append(" (timed out)")
        append("\n```")
    }.take(1600)
}
