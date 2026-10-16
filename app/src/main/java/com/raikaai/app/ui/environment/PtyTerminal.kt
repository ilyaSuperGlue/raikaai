package com.raikaai.app.ui.environment

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raikaai.app.core.container.ContainerManager
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle

/**
 * Real interactive PTY terminal (Termux-style), replacing the old
 * shell-in-a-box. The shell keeps its own history, job control, and
 * fullscreen programs (vim/htop); Ctrl+C truly interrupts.
 *
 * Typing goes through a 1dp hidden field: committed text is forwarded to
 * the pty, backspace sends DEL, Enter sends CR. Extra-key rows send real
 * escape sequences. AI tool calls intentionally keep using
 * [ContainerManager.run] (non-interactive, timeout-guarded).
 */
class PtyController(private val manager: ContainerManager) {
    var session: PtySession? by mutableStateOf(null)
        private set
    var generation by mutableStateOf(0)
        private set

    // Sticky modifiers (mirrors the extra-key rows).
    var ctrlActive by mutableStateOf(false)
    var shiftActive by mutableStateOf(false)
    var altActive by mutableStateOf(false)

    // Transcript scrollback offset in lines (0 = follow bottom).
    var scrollLines by mutableStateOf(0)

    var lastCols by mutableStateOf(80)
        private set
    var lastRows by mutableStateOf(24)
        private set

    fun ensure(cols: Int, rows: Int) {
        lastCols = cols.coerceAtLeast(2)
        lastRows = rows.coerceAtLeast(2)
        var s = session
        if (s == null) {
            s = PtySession(
                shellPath = manager.prootBin.absolutePath,
                cwd = manager.workspaceDir.absolutePath,
                args = (listOf("proot") + manager.prootArgv().drop(1) + "/bin/sh").toTypedArray(),
                env = manager.containerEnvMap().map { (k, v) -> "$k=$v" }.toTypedArray(),
            )
            session = s
        }
        s.start(lastCols, lastRows)
    }

    fun write(text: String) {
        session?.write(text)
    }

    fun pageUp() {
        scrollLines += lastRows
    }

    fun pageDown() {
        scrollLines = (scrollLines - lastRows).coerceAtLeast(0)
    }

    fun clearScroll() {
        scrollLines = 0
    }

    fun clearModifiers() {
        ctrlActive = false
        shiftActive = false
        altActive = false
    }

    fun finish() {
        session?.finish()
        session = null
    }

    fun restart() {
        finish()
        scrollLines = 0
        clearModifiers()
        generation++
    }
}


@Composable
fun PtyTerminalView(
    controller: PtyController,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val focusReq = remember { FocusRequester() }
    val paint = remember {
        android.graphics.Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
    }
    val fontPx = with(density) { 11.sp.toPx() }
    paint.textSize = fontPx
    val fm = paint.fontMetrics
    val cellW = paint.measureText("MMMMMMMMMM") / 10f
    val cellH = (fm.descent - fm.ascent).coerceAtLeast(1f)

    // Hidden IME field. Unlike the first version (which reset the field to a
    // sentinel after every keystroke and forced the IME to restart input each
    // time — the main source of typing lag), the field now accumulates text
    // and we forward only the diff. The IME is never interrupted, so typing
    // stays fluid; a rare reset happens past 256 chars.
    var ime by remember { mutableStateOf(TextFieldValue("")) }
    var lastSent by remember { mutableStateOf("") }
    fun sendImeText(raw: String) {
        if (raw.isEmpty()) return
        val sb = StringBuilder()
        for (c in raw) sb.append(if (c == '\n') '\r' else c)
        controller.write(sb.toString())
    }
    fun sendAdded(added: String) {
        if (added.isEmpty()) return
        when {
            controller.ctrlActive -> {
                controller.ctrlActive = false
                added.forEach { ch ->
                    val lower = ch.lowercaseChar()
                    if (lower in 'a'..'z' && added.length == 1) {
                        controller.write(((lower - 'a' + 1).toChar()).toString())
                    } else {
                        sendImeText(ch.toString())
                    }
                }
            }
            controller.altActive -> {
                controller.altActive = false
                added.forEach { ch ->
                    controller.write(if (ch == '\n') "\r" else "\u001B$ch")
                }
            }
            controller.shiftActive -> {
                controller.shiftActive = false
                sendImeText(if (added.length == 1) added.uppercase() else added)
            }
            else -> sendImeText(added)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val cols = ((constraints.maxWidth / cellW).toInt()).coerceAtLeast(2)
        val rows = ((constraints.maxHeight / cellH).toInt()).coerceAtLeast(2)
        LaunchedEffect(cols, rows, controller.generation) {
            controller.ensure(cols, rows)
        }
        val session = controller.session
        // Read revision for recomposition on screen change.
        session?.revision
        val scroll = controller.scrollLines

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clickable { focusReq.requestFocus() },
        ) {
            if (session != null) {
                drawPtyScreen(session, cols, rows, scroll, cellW, cellH, paint, fm)
            }
        }

        BasicTextField(
            value = ime,
            onValueChange = { v ->
                val n = v.text
                val o = lastSent
                if (n != o) {
                    // Prefix/suffix diff: robust to cursor moves and batch
                    // commits. The field is never reset, so the IME is never
                    // interrupted (that restart was the typing-lag source).
                    var pre = 0
                    val mn = minOf(o.length, n.length)
                    while (pre < mn && o[pre] == n[pre]) pre++
                    var suf = 0
                    while (suf < mn - pre && o[o.length - 1 - suf] == n[n.length - 1 - suf]) suf++
                    val removed = o.length - pre - suf
                    val added = n.substring(pre, n.length - suf)
                    repeat(removed) { controller.write("\u007F") }
                    sendAdded(added)
                    lastSent = n
                    if (n.length > 256) {
                        // Rare hygiene reset so the buffer stays small.
                        lastSent = ""
                        ime = TextFieldValue("", TextRange.Zero)
                        return@BasicTextField
                    }
                }
                ime = v
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusReq),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            singleLine = false,
            maxLines = 10,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Unspecified,
            ),
            keyboardActions = KeyboardActions(
                onDone = { controller.write("\r") },
                onGo = { controller.write("\r") },
                onSend = { controller.write("\r") },
            ),
        )
    }
}

private fun DrawScope.drawPtyScreen(
    session: PtySession,
    cols: Int,
    rows: Int,
    scrollLines: Int,
    cellW: Float,
    cellH: Float,
    paint: android.graphics.Paint,
    fm: Paint.FontMetrics,
) {
    val emulator = runCatching { session.emulator }.getOrNull() ?: return
    val buf = emulator.screen
    val colors = emulator.mColors
    val defaultBg = resolveTermColor(colors, TextStyle.COLOR_INDEX_BACKGROUND, false)
    drawRect(defaultBg, size = Size(cols * cellW, rows * cellH))

    val totalActive = buf.activeRows
    if (totalActive <= 0) return
    val transcript = buf.activeTranscriptRows.coerceAtLeast(0)
    val maxScroll = (transcript).coerceAtLeast(0)
    val scroll = scrollLines.coerceIn(0, maxScroll)
    val bottomExt = totalActive - scroll
    val topExt = bottomExt - rows

    for (r in 0 until rows) {
        val ext = topExt + r
        if (ext < 0) continue
        val rowText = buf.getSelectedText(0, ext, cols, ext, false)
            .padEnd(cols, ' ').take(cols)
        val y = r * cellH
        paint.isFakeBoldText = false
        var c = 0
        while (c < cols) {
            val style = buf.getStyleAt(ext, c)
            var c2 = c + 1
            while (c2 < cols && buf.getStyleAt(ext, c2) == style) c2++
            var fg = resolveTermColor(colors, TextStyle.decodeForeColor(style), true)
            var bg = resolveTermColor(colors, TextStyle.decodeBackColor(style), false)
            val effect = TextStyle.decodeEffect(style)
            val inverse = (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0
            if (inverse) {
                val t = fg
                fg = bg
                bg = t
            }
            val bold = (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
            val underline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
            if (bg != defaultBg) {
                drawRect(bg, topLeft = Offset(c * cellW, y), size = Size((c2 - c) * cellW, cellH))
            }
            paint.isFakeBoldText = bold
            paint.color = fg.toArgb()
            val runText = rowText.substring(c, c2)
            drawContext.canvas.nativeCanvas.drawText(
                runText,
                c * cellW,
                y - fm.ascent,
                paint,
            )
            if (underline) {
                drawRect(
                    fg,
                    topLeft = Offset(c * cellW, y + cellH - 2.dp.toPx()),
                    size = Size((c2 - c) * cellW, 1.5.dp.toPx()),
                )
            }
            c = c2
        }
    }

    // Cursor block (only when following the bottom and session alive).
    if (scroll == 0 && !session.exited) {
        val screenRows = totalActive - (totalActive - rows).coerceAtLeast(0)
        val cursorScreenRow = runCatching { emulator.cursorRow }.getOrDefault(-1)
        val cursorCol = runCatching { emulator.cursorCol }.getOrDefault(-1)
        if (cursorScreenRow in 0 until rows && cursorCol in 0 until cols) {
            // Map screen row to viewport row (emulator screen == viewport size).
            val viewRow = rows - screenRows + cursorScreenRow
            if (viewRow in 0 until rows) {
                drawRect(
                    Color.White.copy(alpha = 0.75f),
                    topLeft = Offset(cursorCol * cellW, viewRow * cellH),
                    size = Size(cellW, cellH),
                )
            }
        }
    }
    paint.isFakeBoldText = false
}

/** Palette index (0..258) or truecolor ARGB (negative Int) → Compose Color. */
private fun resolveTermColor(colors: TerminalColors, code: Int, isFg: Boolean): Color {
    if (code < 0) return Color(code)
    val idx = code.coerceIn(0, TextStyle.NUM_INDEXED_COLORS - 1)
    return Color(colors.mCurrentColors[idx])
}
