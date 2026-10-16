package com.raikaai.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raikaai.app.util.CodeArtifacts
import com.raikaai.app.util.FileSaver
import java.util.regex.Pattern
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dependency-free, high-performance Markdown renderer for streaming chat tokens.
 * Handles:
 * - Collapsible reasoning/thinking blocks (<think>...</think>)
 * - Headers (#, ##, ###)
 * - Code blocks (```lang) with copy feedback
 * - Blockquotes (>)
 * - Bullets (- / *) and Numbered lists (1.)
 * - Inline formatting (**bold**, *italic*, `inline code`, [links](url))
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    enableThinking: Boolean = true,
    onPreviewHtml: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    // Extract thinking tags (<think>...</think>)
    val parsedThinking = remember(markdown, enableThinking) { extractThinking(markdown, enableThinking) }
    val formattedText = remember(parsedThinking.mainText) { formatToolCallsForDisplay(parsedThinking.mainText) }
    val blocks = remember(formattedText) { parseBlocks(formattedText) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Render Collapsible Thinking Block if present and enabled
        if (enableThinking && parsedThinking.thinkingText != null) {
            ThinkingCard(
                thought = parsedThinking.thinkingText,
                isComplete = parsedThinking.isComplete,
            )
        }

            blocks.forEach { block ->
                when (block.type) {
                    BlockType.CODE_BLOCK -> {
                        CodeBlockCard(
                            language = block.language,
                            code = block.text,
                            onCopy = { clipboard.setText(AnnotatedString(block.text)) },
                            onPreviewHtml = onPreviewHtml,
                        )
                    }
                    BlockType.HEADER_1 -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = rememberAnnotated(block.text, scheme),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (color != Color.Unspecified) color else scheme.primary,
                        )
                    }
                    BlockType.HEADER_2 -> {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = rememberAnnotated(block.text, scheme),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (color != Color.Unspecified) color else scheme.primary,
                        )
                    }
                    BlockType.HEADER_3 -> {
                        Text(
                            text = rememberAnnotated(block.text, scheme),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (color != Color.Unspecified) color else scheme.onSurface,
                        )
                    }
                    BlockType.BLOCKQUOTE -> {
                        BlockquoteCard(text = block.text, scheme = scheme)
                    }
                    BlockType.BULLET -> {
                        Row(modifier = Modifier.padding(start = 4.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = rememberAnnotated(block.text, scheme),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                            )
                        }
                    }
                    BlockType.NUMBERED -> {
                        Row(modifier = Modifier.padding(start = 4.dp)) {
                            Text(block.language, style = MaterialTheme.typography.bodyMedium, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = rememberAnnotated(block.text, scheme),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                            )
                        }
                    }
                    BlockType.TABLE -> {
                        TableCard(tableRaw = block.text, scheme = scheme)
                    }
                    BlockType.PARAGRAPH -> {
                        if (block.text.isNotBlank()) {
                            Text(
                                text = rememberAnnotated(block.text, scheme),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                            )
                        }
                    }
                }
            }
        }
}

/**
 * Collapsible Thinking Process Card (for DeepSeek R1, Qwen3, and reasoning LLMs).
 */
@Composable
private fun ThinkingCard(
    thought: String,
    isComplete: Boolean,
) {
    // Collapsed by default when complete, expanded while generating
    var expanded by remember(isComplete) { mutableStateOf(!isComplete) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isComplete) "Thought Process" else "Thinking…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isComplete) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = thought.ifBlank { "…" },
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

private fun findUnescapedQuote(s: String): Int {
    var i = 0
    while (i < s.length) {
        if (s[i] == '\\') {
            i += 2
        } else if (s[i] == '"') {
            return i
        } else {
            i++
        }
    }
    return -1
}

private fun unescapeJsonString(raw: String): String {
    if (raw.isEmpty()) return ""
    val text = if (raw.endsWith("\\")) raw.dropLast(1) else raw
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            when (val next = text[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'u' -> {
                    if (i + 5 < text.length) {
                        val hex = text.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    sb.append(c)
                }
                else -> sb.append(next)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun formatToolCallBlock(body: String, isComplete: Boolean): String {
    val cleanBody = body.trim()
    if (cleanBody.isBlank()) return if (isComplete) "" else "**Tool call** _(preparing...)_"

    val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(cleanBody)
    val toolName = nameMatch?.groupValues?.get(1)?.trim().orEmpty()

    val pathMatch = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(cleanBody)
    val path = pathMatch?.groupValues?.get(1)?.trim().orEmpty()

    val cmdMatch = Regex("\"command\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.IGNORE_CASE).find(cleanBody)
    val command = cmdMatch?.let { unescapeJsonString(it.groupValues[1]) }.orEmpty()

    val statusTag = if (isComplete) "" else " _(writing...)_"

    return when {
        toolName.contains("write_file", ignoreCase = true) -> {
            val contentIdx = cleanBody.indexOf("\"content\"")
            val content = if (contentIdx >= 0) {
                val colon = cleanBody.indexOf(':', contentIdx)
                if (colon >= 0) {
                    val firstQuote = cleanBody.indexOf('"', colon)
                    if (firstQuote >= 0) {
                        val rawContent = cleanBody.substring(firstQuote + 1)
                        val endQuote = findUnescapedQuote(rawContent)
                        val cleanRaw = if (endQuote >= 0) {
                            rawContent.substring(0, endQuote)
                        } else {
                            rawContent.removeSuffix("\"").removeSuffix("}").trimEnd()
                        }
                        unescapeJsonString(cleanRaw)
                    } else ""
                } else ""
            } else ""

            val ext = if (path.contains('.')) path.substringAfterLast('.').lowercase() else "code"
            buildString {
                if (path.isNotBlank()) {
                    append("**Write file:** `$path`$statusTag\n\n")
                } else {
                    append("**Write file**$statusTag\n\n")
                }
                if (content.isNotBlank()) {
                    append("```$ext\n")
                    append(content)
                    append("\n```\n")
                }
            }
        }
        toolName.contains("edit_file", ignoreCase = true) -> {
            buildString {
                if (path.isNotBlank()) {
                    append("**Edit file:** `$path`$statusTag\n\n")
                } else {
                    append("**Edit file**$statusTag\n\n")
                }
            }
        }
        toolName.contains("read_file", ignoreCase = true) -> {
            if (path.isNotBlank()) "**Read file:** `$path`" else "**Read file**"
        }
        toolName.contains("execute_in_container", ignoreCase = true) ||
            toolName.contains("run_command", ignoreCase = true) -> {
            if (command.isNotBlank()) "**Run:** `$command`$statusTag" else "**Run in container**$statusTag"
        }
        toolName.isNotBlank() -> {
            "**Tool:** `$toolName`$statusTag"
        }
        else -> {
            if (isComplete) "" else "**Tool call** _(streaming...)_"
        }
    }
}

private fun formatToolCallsForDisplay(text: String): String {
    if (!text.contains("<tool_call", ignoreCase = true) &&
        !text.contains("```tool_call", ignoreCase = true) &&
        !text.contains("```tool", ignoreCase = true)
    ) {
        return text
    }

    var result = text

    val xmlPattern = Pattern.compile("<tool_call>([\\s\\S]*?)(?:<\\/tool_call>|$)", Pattern.CASE_INSENSITIVE)
    val xmlMatcher = xmlPattern.matcher(result)
    val xmlReplacements = mutableListOf<Pair<String, String>>()
    while (xmlMatcher.find()) {
        val fullMatch = xmlMatcher.group(0) ?: continue
        val body = xmlMatcher.group(1).orEmpty()
        val isComplete = fullMatch.contains("</tool_call>", ignoreCase = true)
        val formatted = formatToolCallBlock(body, isComplete)
        xmlReplacements.add(fullMatch to formatted)
    }
    for ((orig, repl) in xmlReplacements) {
        result = result.replace(orig, "\n\n$repl\n\n")
    }

    val mdPattern = Pattern.compile("```(?:tool_call|tool)(?:\\s+([\\s\\S]*?))?(?:```|$)", Pattern.CASE_INSENSITIVE)
    val mdMatcher = mdPattern.matcher(result)
    val mdReplacements = mutableListOf<Pair<String, String>>()
    while (mdMatcher.find()) {
        val fullMatch = mdMatcher.group(0) ?: continue
        val body = mdMatcher.group(1).orEmpty()
        val isComplete = fullMatch.endsWith("```")
        val formatted = formatToolCallBlock(body, isComplete)
        mdReplacements.add(fullMatch to formatted)
    }
    for ((orig, repl) in mdReplacements) {
        result = result.replace(orig, "\n\n$repl\n\n")
    }

    return result.trim()
}

private data class ParsedThinking(
    val thinkingText: String?,
    val mainText: String,
    val isComplete: Boolean,
)

private fun extractThinking(raw: String, enableThinking: Boolean = true): ParsedThinking {
    val thinkOpenTag = if (raw.contains("<thought>")) "<thought>" else if (raw.contains("<think>")) "<think>" else null
    val thinkCloseTag = if (thinkOpenTag == "<thought>") "</thought>" else if (thinkOpenTag == "<think>") "</think>" else if (raw.contains("</think>")) "</think>" else if (raw.contains("</thought>")) "</thought>" else null

    if (thinkOpenTag == null && thinkCloseTag == null) {
        return ParsedThinking(null, raw.trim(), true)
    }

    if (thinkOpenTag == null && thinkCloseTag != null) {
        val think = raw.substringBefore(thinkCloseTag).trim()
        val main = raw.substringAfter(thinkCloseTag).replace("</think>", "").replace("</thought>", "").trim()
        return ParsedThinking(if (enableThinking && think.isNotBlank()) think else null, main, true)
    }

    if (!enableThinking) {
        return if (thinkCloseTag != null && raw.contains(thinkCloseTag)) {
            val main = raw.substringAfter(thinkCloseTag).replace("</think>", "").replace("</thought>", "").trim()
            ParsedThinking(null, main, true)
        } else {
            ParsedThinking(null, "", false)
        }
    }

    return if (thinkCloseTag != null && raw.contains(thinkCloseTag)) {
        val think = raw.substringAfter(thinkOpenTag ?: "").substringBefore(thinkCloseTag).trim()
        val main = raw.substringAfter(thinkCloseTag).replace("</think>", "").replace("</thought>", "").trim()
        ParsedThinking(think, main, true)
    } else {
        val think = raw.substringAfter(thinkOpenTag ?: "").trim()
        ParsedThinking(think, "", false)
    }
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String,
    onCopy: () -> Unit,
    onPreviewHtml: ((String) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    var copied by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf<String?>(null) }
    // B36: rapid taps used to stack delay-reset coroutines; keep the latest
    // job and cancel it before starting a new one.
    var copyResetJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val isHtml = remember(language, code) {
        val l = language.trim().lowercase()
        l == "html" || l == "htm" || code.contains("<!DOCTYPE html", ignoreCase = true) || code.contains("<html", ignoreCase = true)
    }

    val lines = remember(code) { code.lines() }
    val totalLines = lines.size
    val isCollapsible = totalLines > 10
    var isExpanded by remember { mutableStateOf(false) }
    val displayCode = remember(code, isExpanded) {
        if (isCollapsible && !isExpanded) lines.take(10).joinToString("\n") else code
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = language.ifBlank { "code" }.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (isCollapsible) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "· $totalLines lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCollapsible) {
                        IconButton(
                            onClick = { isExpanded = !isExpanded },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "Minimize code" else "Maximize code",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (isHtml && onPreviewHtml != null) {
                        IconButton(
                            onClick = { onPreviewHtml(code) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Visibility,
                                contentDescription = "Preview HTML",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val resolvedName = CodeArtifacts.deriveFileName(language.ifBlank { "txt" }, code)
                                val name = FileSaver.saveToDownloads(
                                    context,
                                    resolvedName,
                                    code,
                                )
                                if (name != null) {
                                    savedName = name
                                    android.widget.Toast.makeText(
                                        context,
                                        "Saved to Download/RaikaAI/$name",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                    delay(2000)
                                    savedName = null
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Failed to save file",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            if (savedName != null) Icons.Filled.Check else Icons.Filled.SaveAlt,
                            contentDescription = "Save code file",
                            modifier = Modifier.size(14.dp),
                            tint = if (savedName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            onCopy()
                            copied = true
                            copyResetJob?.cancel()
                            copyResetJob =
                                scope.launch {
                                    delay(2000)
                                    copied = false
                                }
                        },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = "Copy code",
                            modifier = Modifier.size(14.dp),
                            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = displayCode,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (isCollapsible) {
                Surface(
                    onClick = { isExpanded = !isExpanded },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isExpanded) "Collapse ($totalLines lines)" else "Show all $totalLines lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockquoteCard(
    text: String,
    scheme: ColorScheme,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(scheme.primary.copy(alpha = 0.7f), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = rememberAnnotated(text, scheme),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableCard(
    tableRaw: String,
    scheme: ColorScheme,
) {
    val table = remember(tableRaw) { parseTable(tableRaw) }
    if (table.headers.isEmpty() && table.rows.isEmpty()) return

    val colCount = maxOf(
        table.headers.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
    )
    if (colCount == 0) return

    val scrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            // Header Row
            if (table.headers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (c in 0 until colCount) {
                        val headerText = table.headers.getOrNull(c).orEmpty()
                        val align = table.alignments.getOrNull(c) ?: TextAlign.Start
                        Box(
                            modifier = Modifier
                                .widthIn(min = 120.dp, max = 320.dp)
                                .padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = rememberAnnotated(headerText, scheme),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = align,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }

            // Data Rows
            table.rows.forEachIndexed { rIdx, row ->
                val rowBg = if (rIdx % 2 == 1) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.25f)
                } else {
                    Color.Transparent
                }
                Row(
                    modifier = Modifier
                        .background(rowBg)
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (c in 0 until colCount) {
                        val cellText = row.getOrNull(c).orEmpty()
                        val align = table.alignments.getOrNull(c) ?: TextAlign.Start
                        Box(
                            modifier = Modifier
                                .widthIn(min = 120.dp, max = 320.dp)
                                .padding(horizontal = 8.dp),
                        ) {
                            Text(
                                text = rememberAnnotated(cellText, scheme),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = align,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (rIdx < table.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                }
            }
        }
    }
}

private data class TableData(
    val headers: List<String>,
    val alignments: List<TextAlign>,
    val rows: List<List<String>>,
)

private fun parseTable(raw: String): TableData {
    val lines = raw.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return TableData(emptyList(), emptyList(), emptyList())

    fun splitRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        return trimmed.split('|').map { it.trim() }
    }

    val headerLine = lines.getOrNull(0) ?: ""
    val sepLine = lines.getOrNull(1) ?: ""
    val headers = splitRow(headerLine)

    val alignments = if (sepLine.isNotBlank()) {
        splitRow(sepLine).map { col ->
            val c = col.trim()
            when {
                c.startsWith(":") && c.endsWith(":") -> TextAlign.Center
                c.endsWith(":") -> TextAlign.End
                else -> TextAlign.Start
            }
        }
    } else {
        emptyList()
    }

    val rows = lines.drop(2).map { splitRow(it) }
    return TableData(headers, alignments, rows)
}

private enum class BlockType { PARAGRAPH, HEADER_1, HEADER_2, HEADER_3, BULLET, NUMBERED, BLOCKQUOTE, CODE_BLOCK, TABLE }

private data class Block(
    val type: BlockType,
    val text: String,
    val language: String = "",
)

private fun parseBlocks(markdown: String): List<Block> {
    if (markdown.isBlank()) return emptyList()

    val blocks = mutableListOf<Block>()
    val lines = markdown.lines()
    var inCodeBlock = false
    var codeLang = ""
    val codeContent = StringBuilder()

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(Block(BlockType.CODE_BLOCK, codeContent.toString().trimEnd(), codeLang))
                codeContent.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                inCodeBlock = true
                codeLang = trimmed.removePrefix("```").trim().ifBlank { "code" }
                codeContent.clear()
            }
            i++
            continue
        }

        if (inCodeBlock) {
            codeContent.appendLine(line)
            i++
            continue
        }

        // Table Detection: current line is table row and next line is table separator
        if (isTableRow(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val tableLines = mutableListOf<String>()
            tableLines.add(line)
            tableLines.add(lines[i + 1])
            i += 2
            while (i < lines.size && isTableRow(lines[i])) {
                tableLines.add(lines[i])
                i++
            }
            blocks.add(Block(BlockType.TABLE, tableLines.joinToString("\n")))
            continue
        }

        when {
            line.isBlank() -> Unit
            line.startsWith("### ") -> blocks.add(Block(BlockType.HEADER_3, line.removePrefix("### ").trim()))
            line.startsWith("## ") -> blocks.add(Block(BlockType.HEADER_2, line.removePrefix("## ").trim()))
            line.startsWith("# ") -> blocks.add(Block(BlockType.HEADER_1, line.removePrefix("# ").trim()))
            line.startsWith("> ") -> blocks.add(Block(BlockType.BLOCKQUOTE, line.removePrefix("> ").trim()))
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                blocks.add(Block(BlockType.BULLET, line.trimStart().drop(2).trim()))
            NUMBERED_LIST_REGEX.containsMatchIn(line.trimStart()) -> {
                val num = line.trimStart().substringBefore(".") + "."
                val rest = line.trimStart().substringAfter(".").trim()
                blocks.add(Block(BlockType.NUMBERED, rest, num))
            }
            else -> blocks.add(Block(BlockType.PARAGRAPH, line))
        }
        i++
    }

    if (inCodeBlock && codeContent.isNotEmpty()) {
        blocks.add(Block(BlockType.CODE_BLOCK, codeContent.toString().trimEnd(), codeLang))
    }

    return blocks.ifEmpty { listOf(Block(BlockType.PARAGRAPH, markdown)) }
}

private fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("|") && (trimmed.endsWith("|") || trimmed.count { it == '|' } >= 2)
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains("|")) return false
    val cols = trimmed.trim('|').split('|')
    return cols.isNotEmpty() && cols.all { col ->
        val c = col.trim()
        c.isNotEmpty() && c.matches(TABLE_SEPARATOR_CELL_REGEX)
    }
}

private val INLINE_REGEX = Regex("(\\*\\*([^*]+?)\\*\\*)|(\\*([^*]+?)\\*)|(`([^`]+?)`)|(\\[([^\\]]+)\\]\\(([^)]+)\\))")

// B32: hoisted — previously recompiled per line / per table cell.
private val NUMBERED_LIST_REGEX = Regex("^\\d+\\.\\s")
private val TABLE_SEPARATOR_CELL_REGEX = Regex("^:?-+:?$")

/**
 * B31: memoizes the regex-heavy [annotate] pass. Called from composition, so
 * without remember every ~50ms token batch re-ran the inline-format regexes
 * over the whole message (O(n²) while streaming long responses).
 */
@Composable
private fun rememberAnnotated(
    text: String,
    scheme: ColorScheme,
): AnnotatedString = remember(text, scheme) { annotate(text, scheme) }

private fun annotate(
    text: String,
    scheme: ColorScheme,
): AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    val codeStyle =
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = scheme.surfaceContainerHighest.copy(alpha = 0.8f),
            color = scheme.primary,
            fontSize = 13.sp,
        )
    val linkStyle = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline)

    return buildAnnotatedString {
        var lastIndex = 0
        for (match in INLINE_REGEX.findAll(text)) {
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }
            val raw = match.value
            when {
                raw.startsWith("**") && raw.endsWith("**") -> {
                    pushStyle(boldStyle)
                    append(raw.substring(2, raw.length - 2))
                    pop()
                }
                raw.startsWith("*") && raw.endsWith("*") -> {
                    pushStyle(italicStyle)
                    append(raw.substring(1, raw.length - 1))
                    pop()
                }
                raw.startsWith("`") && raw.endsWith("`") -> {
                    pushStyle(codeStyle)
                    append(raw.substring(1, raw.length - 1))
                    pop()
                }
                raw.startsWith("[") && raw.contains("](") -> {
                    val linkText = raw.substringAfter("[").substringBefore("]")
                    val url = raw.substringAfter("](").substringBeforeLast(")")
                    // Link APIs (TextLinkStyles, withLink) live in
                    // androidx.compose.ui.text in current Compose, not
                    // androidx.compose.foundation.text.
                    val linkTextStyles =
                        TextLinkStyles(
                            style = linkStyle,
                            focusedStyle = linkStyle,
                            hoveredStyle = linkStyle,
                            pressedStyle = linkStyle,
                        )
                    withLink(LinkAnnotation.Url(url, linkTextStyles)) {
                        append(linkText)
                    }
                }
                else -> append(raw)
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}
