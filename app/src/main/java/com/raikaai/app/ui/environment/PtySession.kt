package com.raikaai.app.ui.environment

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.raikaai.app.util.KLog
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * One interactive PTY shell backed by the vendored Termux terminal-emulator
 * (Apache 2.0) plus our tiny JNI forkpty bridge (libraikaai_pty.so).
 *
 * Unlike the one-shot ContainerManager.run (captures output, kills on
 * timeout), this keeps a real interactive shell alive: vim/htop-style
 * fullscreen programs work, Ctrl+C interrupts, and the shell keeps its own
 * history. Used by the Terminal tab only; AI tool calls keep using the
 * one-shot path.
 */
class PtySession(
    shellPath: String,
    cwd: String,
    args: Array<String>,
    env: Array<String>,
) {
    companion object {
        private const val TAG = "PtySession"
        const val TRANSCRIPT_ROWS = 2000
    }

    /** Bumped on every screen change; the Compose canvas reads it. */
    var revision by mutableStateOf(0)
        private set

    /** True once the shell process has exited. */
    var exited by mutableStateOf(false)
        private set

    var title by mutableStateOf("")
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    // onTextChanged can fire dozens of times per second during output bursts
    // (and once per keystroke echo). Cap redraws at ~30fps with a trailing
    // bump so the last frame is never dropped.
    private var lastBumpAt = 0L
    private var trailingBumpPosted = false

    private fun bumpRevision() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBumpAt >= 33) {
            lastBumpAt = now
            revision++
            return
        }
        if (!trailingBumpPosted) {
            trailingBumpPosted = true
            mainHandler.postDelayed(
                {
                    trailingBumpPosted = false
                    lastBumpAt = android.os.SystemClock.uptimeMillis()
                    revision++
                },
                33,
            )
        }
    }

    private inner class SessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            bumpRevision()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            title = changedSession.title ?: ""
            revision++
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            mainHandler.post {
                exited = true
                revision++
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {
            revision++
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            revision++
        }

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            KLog.d(TAG, "shell pid=$pid")
        }

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String, message: String) {
            KLog.e(TAG, "$tag: $message")
        }

        override fun logWarn(tag: String, message: String) {
            KLog.w(TAG, "$tag: $message")
        }

        override fun logInfo(tag: String, message: String) {
            KLog.d(TAG, "$tag: $message")
        }

        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            KLog.e(TAG, "$tag: $message", e)
        }

        override fun logStackTrace(tag: String, e: Exception) {
            KLog.e(TAG, tag, e)
        }
    }

    private val client = SessionClient()

    private val session = TerminalSession(
        shellPath,
        cwd,
        args,
        env,
        TRANSCRIPT_ROWS,
        client,
    )

    val emulator: TerminalEmulator
        get() = session.emulator

    val isRunning: Boolean
        get() = session.isRunning

    private var started = false

    /** Creates the emulator and spawns the shell. Idempotent. */
    @Synchronized
    fun start(columns: Int, rows: Int) {
        if (started) {
            resize(columns, rows)
            return
        }
        started = true
        session.updateSize(columns, rows, 0, 0)
    }

    fun resize(columns: Int, rows: Int) {
        if (!started) return
        // Cell pixels only matter for sixel images; pass 1 to avoid div-by-zero.
        session.updateSize(columns.coerceAtLeast(2), rows.coerceAtLeast(2), 1, 1)
    }

    fun write(text: String) {
        if (text.isEmpty()) return
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            KLog.w(TAG, "write failed: ${t.message}")
        }
    }

    fun writeCodePoint(codePoint: Int) {
        try {
            session.writeCodePoint(false, codePoint)
        } catch (t: Throwable) {
            KLog.w(TAG, "writeCodePoint failed: ${t.message}")
        }
    }

    /** Kills the shell (SIGKILL). Safe to call repeatedly. */
    fun finish() {
        runCatching { session.finishIfRunning() }
    }
}
