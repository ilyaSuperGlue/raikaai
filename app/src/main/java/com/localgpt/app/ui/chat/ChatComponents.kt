package com.localgpt.app.ui.chat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.localgpt.app.util.FileSaver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.localgpt.app.core.audio.AudioPlayerHelper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgpt.app.data.ChatConstants
import com.localgpt.app.data.ChatMessageEntry
import com.localgpt.app.ui.component.MarkdownText
import com.localgpt.app.ui.component.SearchSourcesCard
import com.localgpt.app.web.WebSourceCitation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Branch navigation pill shown under bubbles that have sibling branches
 * (from editing an older message or regenerating). Matches the variant
 * swipe pagination styling.
 */
@Composable
internal fun BranchNavRow(
    position: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        IconButton(
            onClick = onPrev,
            enabled = position > 1,
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateBefore,
                contentDescription = "Previous branch",
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = "$position/$total",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        IconButton(
            onClick = onNext,
            enabled = position < total,
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = "Next branch",
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Minimal empty-state hero: plain centered greeting only.
 * Signature kept so the caller in ChatScreen.kt is unaffected.
 */
@Composable
internal fun EmptyChatHero(
    persona: PersonaPreset,
    modelName: String,
    onPromptSelected: (String) -> Unit,
    onOpenCharacters: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "What can I help you with today?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Message list with ChatterUI-style bubble and frame layouts.
 * Uses reverseLayout = true so incoming streaming tokens (and long thinking blocks)
 * naturally anchor to the bottom without jumping or locking user scroll position.
 */
@Composable
internal fun MessageList(
    viewModel: ChatViewModel,
    activePersona: PersonaPreset,
    isGenerating: Boolean,
    enableThinking: Boolean,
    ttsHelper: TtsHelper,
    audioPlayerHelper: AudioPlayerHelper? = null,
    onPreviewHtml: (String) -> Unit = {},
    onPreviewImage: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val messageCount = viewModel.messages.size
    val settings by viewModel.settings.collectAsState()

    // Scroll to bottom (index 0 in reverseLayout) when a new message is sent or started.
    // Respects the "Auto-scroll" setting — when off, the list stays where the user left it.
    LaunchedEffect(messageCount, settings.autoScroll) {
        if (messageCount > 0 && settings.autoScroll) {
            listState.animateScrollToItem(0)
        }
    }

    val isScrolledUp by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 250
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(
                count = messageCount,
                key = { i ->
                    val reversedIndex = messageCount - 1 - i
                    val msg = viewModel.messages.getOrNull(reversedIndex)
                    msg?.id ?: "msg_${reversedIndex}_$i"
                },
            ) { i ->
                val reversedIndex = messageCount - 1 - i
                val msg = viewModel.messages.getOrNull(reversedIndex) ?: return@items
                val isLast = (reversedIndex == messageCount - 1)
                MessageBubble(
                    messageIndex = reversedIndex,
                    message = msg,
                    activePersona = activePersona,
                    isLastMessage = isLast,
                    isGenerating = isGenerating,
                    enableThinking = enableThinking,
                    ttsHelper = ttsHelper,
                    audioPlayerHelper = audioPlayerHelper,
                    branchInfo = viewModel.branchInfoFor(reversedIndex),
                    onEditUserMessage = { newText -> viewModel.editUserMessageAndRegenerate(reversedIndex, newText) },
                    onSelectVariant = { varIndex -> viewModel.selectMessageVariant(reversedIndex, varIndex) },
                    onSwitchBranch = { delta -> viewModel.switchBranch(reversedIndex, delta) },
                    onRegenerate = { viewModel.regenerateLastResponse() },
                    onPreviewHtml = onPreviewHtml,
                    onPreviewImage = onPreviewImage,
                )
            }
        }

        // Floating Jump to Bottom action button when user scrolls up
        AnimatedVisibility(
            visible = isScrolledUp,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(0)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
                tonalElevation = 4.dp,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Minimal chat message layout (Claude-style): assistant replies render flat
 * directly on the background (no Surface, border, or header), user messages
 * use a thin neutral bubble. All actions (edit, copy, TTS, regenerate,
 * branch nav) are preserved in a small action row below the message.
 */
@Composable
internal fun MessageBubble(
    messageIndex: Int,
    message: ChatMessageEntry,
    activePersona: PersonaPreset,
    isLastMessage: Boolean,
    isGenerating: Boolean,
    enableThinking: Boolean,
    ttsHelper: TtsHelper,
    audioPlayerHelper: AudioPlayerHelper? = null,
    branchInfo: Pair<Int, Int>?,
    onEditUserMessage: (String) -> Unit,
    onSelectVariant: (Int) -> Unit,
    onSwitchBranch: (Int) -> Unit,
    onRegenerate: () -> Unit,
    onPreviewHtml: (String) -> Unit = {},
    onPreviewImage: (String) -> Unit = {},
) {
    val isUser = message.role == ChatConstants.ROLE_USER
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Attached Image if present (above the message body)
        if (!message.imagePath.isNullOrBlank()) {
            val imgFile = remember(message.imagePath) { File(message.imagePath) }
            val bitmap = remember(message.imagePath) {
                try {
                    if (imgFile.exists()) {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeFile(imgFile.absolutePath, opts)
                    } else null
                } catch (_: Throwable) {
                    null
                }
            }
            // Recycle bitmap when imagePath changes or composable leaves composition
            DisposableEffect(bitmap) {
                onDispose { bitmap?.recycle() }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Attached Image",
                    modifier = Modifier
                        .fillMaxWidth(if (isUser) 0.85f else 1f)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPreviewImage(message.imagePath ?: "") },
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        // Attached Audio if present (Native multimodal voice note)
        if (!message.audioPath.isNullOrBlank()) {
            AudioMessagePlayer(
                audioPath = message.audioPath,
                audioPlayerHelper = audioPlayerHelper,
                isUser = isUser,
                modifier = Modifier
                    .fillMaxWidth(if (isUser) 0.85f else 1f)
                    .padding(vertical = 6.dp),
            )
        }

        // Message Text Content
        if (isUser) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(0.85f),
                ) {
                    Column {
                // Attachments: images (multi) + document chips.
                // Falls back to legacy single imagePath for old conversations.
                val attachmentImages =
                    remember(message.attachments, message.imagePath) {
                        val fromList = message.attachments.filter { it.type == "image" }.map { it.path }
                        if (fromList.isNotEmpty()) fromList
                        else listOfNotNull(message.imagePath?.takeIf { it.isNotBlank() })
                    }
                val attachmentFiles =
                    remember(message.attachments) {
                        message.attachments.filter { it.type == "file" }
                    }
                attachmentImages.forEach { imagePath ->
                    // B38: decode off the main thread, downsampled to bubble
                    // width (was: main-thread full-size decode in remember).
                    val bitmap by produceState<Bitmap?>(initialValue = null, imagePath) {
                        value =
                            withContext(Dispatchers.IO) {
                                decodeSampledBitmapFromFile(File(imagePath), reqSizePx = 1024)
                            }
                    }
                    // NOTE: no manual bitmap.recycle() — the bitmap is still
                    // referenced by the Image below; racy recycling crashes
                    // with "Canvas: trying to use a recycled bitmap".
                    // Copy to a local val first: smart cast doesn't work on
                    // delegated properties.
                    val currentBitmap = bitmap
                    if (currentBitmap != null) {
                        Image(
                            bitmap = currentBitmap.asImageBitmap(),
                            contentDescription = "Attached Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPreviewImage(imagePath) },
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                }
                if (attachmentFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        attachmentFiles.take(3).forEach { file ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        file.name.take(16),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        if (attachmentFiles.size > 3) {
                            Text(
                                "+${attachmentFiles.size - 3}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                    }
                }

                // Message Text Content
                        Text(
                            message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Edit action below the bubble
            if (!isGenerating) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit message",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        } else {
            MarkdownText(
                markdown = message.content.ifBlank { "…" },
                enableThinking = enableThinking,
                onPreviewHtml = onPreviewHtml,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )

            // Web Search Clickable & Collapsible Sources Card
            val webCitations = remember(message.sources) {
                message.sources.orEmpty().mapNotNull { raw ->
                    WebSourceCitation.parse(raw)
                }
            }
            if (webCitations.isNotEmpty()) {
                SearchSourcesCard(
                    sources = webCitations,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }

            // Local Document RAG citation chips (if non-web)
            val docSources = remember(message.sources, webCitations) {
                if (webCitations.isNotEmpty()) {
                    message.sources.orEmpty().filter { raw ->
                        WebSourceCitation.parse(raw) == null
                    }
                } else {
                    message.sources.orEmpty()
                }
            }
            if (docSources.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                            docSources.take(3).forEach { label ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(11.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                }
            }

            // Footer Row: Telemetry Stats + Swipes Pagination + Quick Actions.
            // Hidden for greeting messages (no stats, single variant, not regenerable).
            if (!message.stats.isNullOrBlank() || message.variants.size > 1 || (isLastMessage && !isGenerating)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!message.stats.isNullOrBlank()) {
                        Text(
                            text = message.stats,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (message.variants.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 4.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    if (message.selectedVariant > 0) {
                                        onSelectVariant(message.selectedVariant - 1)
                                    }
                                },
                                enabled = message.selectedVariant > 0,
                                modifier = Modifier.size(18.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.NavigateBefore,
                                    contentDescription = "Previous variant",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(
                                text = "${message.selectedVariant + 1}/${message.variants.size}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                            IconButton(
                                onClick = {
                                    if (message.selectedVariant < message.variants.lastIndex) {
                                        onSelectVariant(message.selectedVariant + 1)
                                    }
                                },
                                enabled = message.selectedVariant < message.variants.lastIndex,
                                modifier = Modifier.size(18.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.NavigateNext,
                                    contentDescription = "Next variant",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Copy Action
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(message.content))
                            copied = true
                            scope.launch {
                                delay(2000)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy message",
                            modifier = Modifier.size(13.dp),
                            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Read Aloud / TTS Action
                    val isSpeaking = (ttsHelper.currentlySpeakingId.value == (message.id ?: "msg_$messageIndex"))
                    IconButton(
                        onClick = {
                            val msgId = message.id ?: "msg_$messageIndex"
                            if (isSpeaking) {
                                ttsHelper.stop()
                            } else {
                                ttsHelper.speak(msgId, message.content)
                            }
                        },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = if (isSpeaking) "Stop reading" else "Read aloud",
                            modifier = Modifier.size(14.dp),
                            tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Share Action
                    IconButton(
                        onClick = {
                            val sendIntent =
                                Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, message.content)
                                    type = "text/plain"
                                }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Message"))
                        },
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = "Share message",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Regenerate Action (only on last assistant message when not generating)
                    if (isLastMessage && !isGenerating) {
                        IconButton(
                            onClick = onRegenerate,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Regenerate Response",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            }
        }

        if (branchInfo != null) {
            BranchNavRow(
                position = branchInfo.first,
                total = branchInfo.second,
                onPrev = { onSwitchBranch(-1) },
                onNext = { onSwitchBranch(1) },
            )
        }
    }

    if (showEditDialog) {
        var editInput by remember { mutableStateOf(message.content) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            icon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Edit Message") },
            text = {
                OutlinedTextField(
                    value = editInput,
                    onValueChange = { editInput = it },
                    label = { Text("Prompt") },
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editInput.isNotBlank()) {
                            onEditUserMessage(editInput)
                            showEditDialog = false
                        }
                    },
                    enabled = editInput.isNotBlank(),
                ) {
                    Text("Save & Regenerate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Maps persona icon type string to Material icon vector.
 */
internal fun getIconVector(iconType: String): ImageVector =
    when (iconType) {
        "assistant" -> Icons.Default.Person
        "coder" -> Icons.Default.Code
        "writer" -> Icons.Default.Description
        "translator" -> Icons.Default.Translate
        "reasoning" -> Icons.Default.Psychology
        "speed" -> Icons.Default.Speed
        "shell" -> Icons.Default.Terminal
        "general" -> Icons.Default.AutoAwesome
        else -> Icons.Default.AutoAwesome
    }

/**
 * Formats file size in human-readable format.
 */
internal fun formatFileSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}

/**
 * Clean Material 3 Audio Player Card for native audio multimodal voice notes.
 */
@Composable
fun AudioMessagePlayer(
    audioPath: String,
    audioPlayerHelper: AudioPlayerHelper?,
    isUser: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val currentlyPlaying by audioPlayerHelper?.currentlyPlayingPath?.collectAsState() ?: remember { mutableStateOf(null) }
    val isPlaying = currentlyPlaying == audioPath

    val durationText = remember(audioPath) {
        try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(audioPath)
            val durMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
            val totalSec = durMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            "%d:%02d".format(Locale.US, min, sec)
        } catch (_: Throwable) {
            "Audio"
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isUser) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
        },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(36.dp),
            ) {
                IconButton(
                    onClick = {
                        if (isPlaying) {
                            audioPlayerHelper?.stop()
                        } else {
                            audioPlayerHelper?.play(audioPath)
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isPlaying) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = if (isPlaying) "Playing voice note…" else "Voice Prompt",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "$durationText · 16kHz PCM WAV",
                    style = MaterialTheme.typography.labelSmall,
                    color = (if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * Circular character profile photo. Returns false (renders nothing) when
 * [avatarUri] is blank or unreadable, so callers show the photo only when set.
 * Accepts an absolute file path (internal avatar copy) or a content URI.
 */
@Composable
fun PersonaAvatar(
    avatarUri: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
): Boolean {
    if (avatarUri.isBlank()) return false
    val context = LocalContext.current
    val bitmap = remember(avatarUri) {
        try {
            val file = File(avatarUri)
            if (file.isAbsolute && file.exists()) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } else {
                context.contentResolver.openInputStream(android.net.Uri.parse(avatarUri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    if (bitmap == null) return false
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentScale = ContentScale.Crop,
    )
    return true
}

/**
 * Fullscreen Image Viewer with Save to Pictures (Gallery) and Share via FileProvider.
 */
@Composable
fun ImageViewerDialog(
    imagePath: String,
    onDismiss: () -> Unit,
) {
    if (imagePath.isBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }

    val file = remember(imagePath) { File(imagePath) }
    val bitmap = remember(imagePath) {
        try {
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } else null
        } catch (_: Throwable) {
            null
        }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.95f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Image Container centered
                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Full image preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Image cannot be loaded", color = Color.White)
                    }
                }

                // Top Bar with Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Share Button
                        IconButton(
                            onClick = {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        val ext = file.extension.lowercase(Locale.ROOT)
                                        type = if (ext == "jpg" || ext == "jpeg") "image/jpeg" else "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = "Share",
                                tint = Color.White,
                            )
                        }

                        // Save to Pictures Gallery Button
                        IconButton(
                            onClick = {
                                if (isSaving) return@IconButton
                                isSaving = true
                                scope.launch {
                                    val saved = FileSaver.saveImageToPictures(context, file)
                                    isSaving = false
                                    if (saved != null) {
                                        Toast.makeText(context, "Saved to Pictures/LiteChat ($saved)", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = "Save to Pictures",
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}
