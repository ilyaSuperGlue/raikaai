package com.raikaai.app.core.container

import android.app.Application
import com.raikaai.app.data.SettingsRepository
import com.raikaai.app.util.KLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Single gateway to the Alpine PRoot userland (Fase 1–4).
 *
 * Layout under app private storage:
 * - nativeLibraryDir / libproot.so  → proot engine (extracted by installer)
 * - nativeLibraryDir / lib*_prt.so  → proot deps + loader helpers
 * - filesDir / alpine               → unpacked minirootfs (+ .installed marker)
 * - filesDir / workspace            → bind-mounted as /workspace (sole read-write dir for AI)
 *
 * Only the Alpine rootfs is downloaded (with SHA-256 verification when the
 * checksum is pinned). No PTY here (Fase 3 terminal is shell-in-a-box
 * over [run]; full PTY is a later milestone).
 */
class ContainerManager private constructor(
    private val app: Application,
    private val settingsRepo: SettingsRepository,
) {
    companion object {
        private const val TAG = "Container"
        private const val OUTPUT_CAP = 64 * 1024
        /**
         * Rootfs layout schema. Bump when the unpack format changes
         * (symlinks, permissions, …) so stale on-device rootfs is detected
         * and the UI forces a reinstall instead of failing cryptically.
         */
        const val ROOTFS_SCHEMA = 5

        /**
         * Minimal `curl` subset (GET only) backed by busybox wget, installed
         * to /usr/local/bin at install time. Triple-quoted: no Kotlin
         * escapes needed except ${'$'} for shell variables (none used).
         */
        const val CURL_SHIM = """#!/bin/sh
# Minimal curl subset for agents (backed by busybox wget). GET only.
head_only=0
outfile=""
url=""
next_is_outfile=0
for a in "${'$'}@"; do
    if [ "${'$'}next_is_outfile" = 1 ]; then
        outfile="${'$'}a"; next_is_outfile=0; continue
    fi
    case "${'$'}a" in
        -I|--head) head_only=1 ;;
        -o|--output) next_is_outfile=1 ;;
        -O|--remote-name) outfile="REMOTE" ;;
        -s|-S|-sS|-Ss|-L|--location|-f|--fail|-k|--insecure|-q) ;;
        -*) ;;
        *) url="${'$'}a" ;;
    esac
done
if [ -z "${'$'}url" ]; then echo "curl: no URL" >&2; exit 2; fi
if [ "${'$'}outfile" = "REMOTE" ]; then outfile="${'$'}{url##*/}"; fi
if [ "${'$'}head_only" = 1 ]; then
    if wget -q -O /dev/null "${'$'}url"; then echo "HTTP OK: ${'$'}url"; else echo "curl: failed: ${'$'}url" >&2; exit 1; fi
else
    if [ -n "${'$'}outfile" ]; then wget -q -O "${'$'}outfile" "${'$'}url"; else wget -qO- "${'$'}url"; fi
fi
"""

        @Volatile private var instance: ContainerManager? = null

        fun getInstance(app: Application, settingsRepo: SettingsRepository): ContainerManager =
            instance ?: synchronized(this) {
                instance ?: ContainerManager(app.applicationContext as Application, settingsRepo)
                    .also { instance = it }
            }
    }

    sealed interface InstallState {
        data object Idle : InstallState
        data class Downloading(val label: String, val progress: Float) : InstallState
        data class Extracting(val label: String) : InstallState
        data object Ready : InstallState
        data class Error(val message: String) : InstallState
    }

    data class RunResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean = false,
    ) {
        /** Compact rendering for chat bubbles / terminal log. */
        fun render(capChars: Int = 4000): String {
            val out = (stdout.ifBlank { "(no output)" }).take(capChars)
            val err = stderr.take(1000)
            val sb = StringBuilder("```\n$out")
            if (out.length >= capChars) sb.append("\n…(truncated)")
            sb.append("\n```")
            if (err.isNotBlank()) sb.append("\nstderr:\n```\n$err\n```")
            sb.append("\nexit=$exitCode")
            if (timedOut) sb.append(" (timed out)")
            return sb.toString()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val alpineDir: File get() = File(app.filesDir, "alpine")
    val workspaceDir: File get() = File(app.filesDir, "workspace")
    /** Installer-extracted native libs (exec permission guaranteed). */
    val nativeLibDir: File get() = File(app.applicationInfo.nativeLibraryDir)
    val prootBin: File get() = File(nativeLibDir, "libproot.so")

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _servedPort = MutableStateFlow<Int?>(null)
    /** Currently served localhost port, null = not serving. */
    val servedPort: StateFlow<Int?> = _servedPort.asStateFlow()

    private var serveProcess: Process? = null
    private var serveJob: Job? = null
    private var installJob: Job? = null
    @Volatile private var currentProc: Process? = null
    private var resolvLastRefresh = 0L
    private val daemons = mutableMapOf<Int, Process>()
    private var daemonSeq = 0
    private val MAX_DAEMONS = 5

    /** Marker content is "<version>#<schema>". Missing/stale → not installed. */
    private fun markerSchema(): Int? {
        val text = runCatching { File(alpineDir, ".installed").readText().trim() }.getOrNull()
            ?: return null
        return text.substringAfterLast("#", "").toIntOrNull()
    }

    /** Filesystem truth: rootfs marker with current schema + engine present. */
    fun isInstalled(): Boolean =
        markerSchema() == ROOTFS_SCHEMA && prootBin.isFile

    /** Marker exists but was written by an older unpack format → reinstall needed. */
    fun isOutdated(): Boolean {
        if (!File(alpineDir, ".installed").isFile) return false
        return markerSchema() != ROOTFS_SCHEMA
    }

    /** One-shot diagnostics for the on-device state (copy-pasteable). */
    suspend fun diagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        sb.appendLine("marker=${runCatching { File(alpineDir, ".installed").readText().trim() }.getOrDefault("(none)")}")
        sb.appendLine("schema=$ROOTFS_SCHEMA installed=${isInstalled()} outdated=${isOutdated()}")
        val bb = File(alpineDir, "bin/busybox")
        sb.appendLine("busybox exists=${bb.isFile} size=${if (bb.isFile) bb.length() else -1}")
        val sh = File(alpineDir, "bin/sh")
        val shTarget = runCatching { android.system.Os.readlink(sh.absolutePath) }.getOrNull()
        sb.appendLine("bin/sh exists=${sh.exists()} isSymlink=${shTarget != null} target=${shTarget ?: "-"} guestResolves=${guestResolves("bin/sh")}")
        sb.appendLine("alpineTop=${topLevelListing()}")
        sb.appendLine("alpineEntries=${countEntries(alpineDir, 0)}")
        sb.appendLine("nativeLibs=${nativeLibDir.listFiles()?.map { it.name }?.sorted() ?: "?"}")
        sb.append(runCatching { engineProbes() }.getOrDefault("probes failed"))
        return sb.toString()
    }

    private fun isSymlink(f: File): Boolean {
        return runCatching {
            val st = android.system.Os.lstat(f.absolutePath)
            android.system.OsConstants.S_ISLNK(st.st_mode)
        }.getOrDefault(false)
    }

    private fun countEntries(dir: File, depth: Int): Int {
        if (depth > 4) return 0
        val kids = dir.listFiles() ?: return 0
        var n = kids.size
        if (n > 3000) return n
        // Never follow symlinks: avoids double counts and link cycles.
        for (k in kids) if (k.isDirectory && !isSymlink(k)) n += countEntries(k, depth + 1)
        return n
    }

    fun workspaceFiles(dir: File = workspaceDir): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    // ── Install / wipe ────────────────────────────────────────────────

    /**
     * Verifies bundled native libs, downloads the rootfs, unpacks to
     * [alpineDir], writes resolv.conf + marker, flips settings.
     * Safe to call twice; concurrent calls are ignored.
     */
    fun install(manifest: ContainerManifest = ContainerManifest()) {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            try {
                workspaceDir.mkdirs()
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val bundled = bundledProotForAbi(abi)
                    ?: throw IllegalStateException("Unsupported ABI: $abi (need arm64-v8a or x86_64)")

                _installState.value = InstallState.Downloading("proot engine", 0f)
                verifyBundledLibs(bundled)
                _installState.value = InstallState.Downloading("proot engine", 1f)

                _installState.value = InstallState.Downloading("alpine-minirootfs", 0f)
                val tmpTar = File(app.cacheDir, "alpine-minirootfs.tar.gz")
                downloadToFile(manifest.rootfsUrl, tmpTar, expectedSha256 = manifest.rootfsSha256.ifBlank { null }) { done, total ->
                    _installState.value = InstallState.Downloading(
                        "alpine-minirootfs",
                        if (total > 0) done.toFloat() / total else 0f,
                    )
                }

                _installState.value = InstallState.Extracting("alpine-minirootfs")
                // Clean slate: an older unpack may lack symlinks or be partial.
                alpineDir.deleteRecursively()
                alpineDir.mkdirs()
                val entries = unpackTarGz(tmpTar, alpineDir)
                tmpTar.delete()
                makeBusyboxExecutable()
                verifyRootfs(entries)

                File(alpineDir, "etc").mkdirs()
                // Real DNS comes after unpack (needs etc/ to exist first).
                refreshResolvConf()
                // apk expects these to exist (minirootfs omits some).
                listOf("tmp", "var/cache/apk", "etc/apk", "lib/apk/db", "root").forEach {
                    File(alpineDir, it).mkdirs()
                }
                installCurlShim()
                File(alpineDir, ".installed").writeText("${manifest.alpineVersion}#$ROOTFS_SCHEMA")
                settingsRepo.setAlpineInstalled(true)
                settingsRepo.setAlpineVersion(manifest.alpineVersion)
                // Smoke test: Ready must mean the shell actually runs.
                val smoke = run("echo ok", timeoutSec = 20)
                if (smoke.exitCode != 0 || !smoke.stdout.contains("ok")) {
                    File(alpineDir, ".installed").delete()
                    settingsRepo.setAlpineInstalled(false)
                    settingsRepo.setAlpineVersion("")
                    throw IllegalStateException(
                        "Shell smoke test failed (exit=${smoke.exitCode}): " +
                            (smoke.stderr.ifBlank { smoke.stdout }.take(300).ifBlank { "no output" }),
                    )
                }
                _installState.value = InstallState.Ready
                KLog.d(TAG, "Alpine ${manifest.alpineVersion} installed ($entries entries)")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                KLog.e(TAG, "Install failed", t)
                _installState.value = InstallState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        _installState.value = InstallState.Idle
    }

    /** Deletes the rootfs but keeps workspace/ (user files survive). */
    suspend fun wipe() {
        stopServe()
        withContext(Dispatchers.IO) {
            alpineDir.deleteRecursively()
        }
        settingsRepo.setAlpineInstalled(false)
        settingsRepo.setAlpineVersion("")
        _installState.value = InstallState.Idle
    }

    // ── Run ───────────────────────────────────────────────────────────

    /**
     * Runs [command] via `proot -r alpine -b workspace:/workspace -0 -w /workspace sh -c`.
     * Single-flight (mutex): concurrent callers queue. Timeout kills the process.
     */
    suspend fun run(
        command: String,
        timeoutSec: Long = 15,
        keepTailOutput: Boolean = false,
        extraEnv: Map<String, String> = emptyMap(),
    ): RunResult {
        if (!isInstalled()) {
            val hint = if (isOutdated()) {
                "Linux Environment format is outdated. Press Install again to update it."
            } else {
                "Linux Environment is not installed. Open Models Hub → Linux Environment → Install."
            }
            return RunResult("", hint, -1)
        }
        val clean = command.trim()
        if (clean.isEmpty()) return RunResult("", "empty command", -1)
        workspaceDir.mkdirs()
        // Networks change mid-session (WiFi/data/VPN flips): refresh guest
        // DNS when the copy is older than 15 minutes. Cheap system call,
        // avoids stale-DNS "network unreachable" ghosts.
        if (isInstalled() && System.currentTimeMillis() - resolvLastRefresh > 15 * 60_000L) {
            runCatching { refreshResolvConf() }
        }
        return execProot(
            args = baseArgs() + listOf("/bin/sh", "-c", clean),
            extraEnv = extraEnv,
            timeoutSec = timeoutSec,
            keepTailOutput = keepTailOutput,
        )
    }

    /** Shared proot invocation prefix: rootfs + binds + fake root + workdir. */
    private fun baseArgs(): List<String> {
        workspaceDir.mkdirs()
        return listOf(
            "-r", alpineDir.absolutePath,
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "-b", "/dev",
            "-b", "/proc",
            "-0",
            "-w", "/workspace",
        )
    }

    /**
     * Raw proot invocation (single-flight). Used by [run] and engine probes.
     * No install gate: probes must run even on broken installs.
     */
    suspend fun execProot(
        args: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSec: Long = 15,
        keepTailOutput: Boolean = false,
    ): RunResult {
        return runMutex.withLock {
            withContext(Dispatchers.IO) {
                var proc: Process? = null
                try {
                    val pb = ProcessBuilder(listOf(prootBin.absolutePath) + args)
                    pb.redirectErrorStream(false)
                    withContainerEnv(pb)
                    extraEnv.forEach { (k, v) -> pb.environment()[k] = v }
                    proc = pb.start()
                    currentProc = proc
                    val finished = try {
                        proc.waitFor(timeoutSec, TimeUnit.SECONDS)
                    } finally {
                        if (currentProc === proc) currentProc = null
                    }
                    if (!finished) {
                        proc.destroyForcibly()
                        return@withContext RunResult(
                            readCapped(proc.inputStream, keepTailOutput),
                            readCapped(proc.errorStream, keepTailOutput),
                            -1,
                            timedOut = true,
                        )
                    }
                    RunResult(
                        readCapped(proc.inputStream, keepTailOutput),
                        readCapped(proc.errorStream, keepTailOutput),
                        proc.exitValue(),
                    )
                } catch (t: Throwable) {
                    try { proc?.destroyForcibly() } catch (_: Throwable) {}
                    RunResult("", "run failed: ${t.message ?: t.javaClass.simpleName}", -1)
                }
            }
        }
    }

    /**
     * Engine self-test probes (no install gate). Distinguishes engine/linker
     * failure (P0), rootfs access failure (P1), and shell lookup failure (P2).
     */
    suspend fun engineProbes(): String {
        val sb = StringBuilder()
        val p0 = execProot(listOf("--version"), timeoutSec = 10)
        sb.appendLine("P0 engine --version: exit=${p0.exitCode} out=${(p0.stdout.ifBlank { p0.stderr }).take(200)}")
        if (!File(alpineDir, ".installed").isFile) {
            sb.appendLine("P1-P4 skipped: no rootfs marker")
            return sb.toString()
        }
        val base = baseArgs()
        val p1 = execProot(base + listOf("/bin/busybox", "echo", "hi"), timeoutSec = 10)
        sb.appendLine("P1 busybox echo: exit=${p1.exitCode} out=${(p1.stdout.ifBlank { p1.stderr }).take(200)}")
        val p2 = execProot(base + listOf("/bin/sh", "-c", "echo hi"), timeoutSec = 10)
        sb.appendLine("P2 sh echo: exit=${p2.exitCode} out=${(p2.stdout.ifBlank { p2.stderr }).take(200)}")
        if (p2.exitCode != 0) {
            val pv = execProot(
                base + listOf("/bin/sh", "-c", "echo hi"),
                extraEnv = mapOf("PROOT_VERBOSE" to "1"),
                timeoutSec = 10,
            )
            sb.appendLine("P2 verbose: ${(pv.stdout.ifBlank { pv.stderr }).take(1500)}")
        }
        // P3: what does the GUEST see for the busybox links?
        val p3 = execProot(base + listOf("/bin/busybox", "ls", "-l", "/bin/sh", "/usr/bin/zipinfo"), timeoutSec = 10)
        sb.appendLine("P3 guest ls: exit=${p3.exitCode} out=${(p3.stdout.ifBlank { p3.stderr }).take(300)}")
        // P4: can the guest write where apk needs to (exact failing area)?
        val p4 = execProot(
            base + listOf("/bin/sh", "-c", "touch /usr/bin/_wtest && ls -l /usr/bin/_wtest && rm /usr/bin/_wtest && echo TOUCH_OK"),
            timeoutSec = 10,
        )
        sb.appendLine("P4 touch test: exit=${p4.exitCode} out=${(p4.stdout.ifBlank { p4.stderr }).take(300)}")
        // P5: dotfile create (apk temp-file style).
        val p5 = execProot(
            base + listOf("/bin/sh", "-c", "touch /usr/bin/.apk.probe && ls -l /usr/bin/.apk.probe && rm /usr/bin/.apk.probe && echo DOT_OK"),
            timeoutSec = 10,
        )
        sb.appendLine("P5 dotfile test: exit=${p5.exitCode} out=${(p5.stdout.ifBlank { p5.stderr }).take(300)}")
        // P6: the exact apk dance — create temp, then rename onto final name.
        val p6 = execProot(
            base + listOf("/bin/sh", "-c", "touch /usr/bin/.apk.mv && mv /usr/bin/.apk.mv /usr/bin/_apkfinal && ls -l /usr/bin/_apkfinal && rm /usr/bin/_apkfinal && echo MV_OK"),
            timeoutSec = 10,
        )
        sb.appendLine("P6 rename test: exit=${p6.exitCode} out=${(p6.stdout.ifBlank { p6.stderr }).take(300)}")
        return sb.toString()
    }

    /** Forcibly stops the currently running command, if any (terminal Ctrl+C). */
    fun cancelRun() {
        try { currentProc?.destroyForcibly() } catch (_: Throwable) {}
    }

    /**
     * Starts [command] as a detached daemon (for `&` background servers):
     * returns at once while the process tree keeps running. Proot lives as
     * long as any tracee, so fire-and-forget through [run] would hang until
     * timeout and then kill the server — this is the correct primitive.
     */
    suspend fun startDaemon(command: String): RunResult {
        return runMutex.withLock {
            withContext(Dispatchers.IO) {
                if (daemons.size >= MAX_DAEMONS) {
                    return@withContext RunResult(
                        "",
                        "Too many background servers (${daemons.size}). Stop one first: stop_daemon tool or /killdaemon <id>.",
                        -1,
                    )
                }
                try {
                    workspaceDir.mkdirs()
                    val pb = ProcessBuilder(
                        listOf(prootBin.absolutePath) + baseArgs() + listOf("/bin/sh", "-c", command),
                    )
                    pb.redirectErrorStream(false)
                    withContainerEnv(pb)
                    val proc = pb.start()
                    val id = ++daemonSeq
                    daemons[id] = proc
                    // Drain so a chatty daemon never blocks on a full pipe; reap on exit.
                    scope.launch {
                        runCatching {
                            val buf = ByteArray(8192)
                            while (proc.inputStream.read(buf) >= 0) {}
                        }
                        runCatching {
                            val buf = ByteArray(8192)
                            while (proc.errorStream.read(buf) >= 0) {}
                        }
                    }
                    scope.launch {
                        try { proc.waitFor() } catch (_: Throwable) {}
                        daemons.remove(id)
                    }
                    RunResult(
                        "Started in background as daemon #$id. It keeps running after this call returns. " +
                            "Verify it with a follow-up command (e.g. wget -qO- http://127.0.0.1:PORT). " +
                            "Stop it with the stop_daemon tool {\"id\": \"$id\"}.",
                        "",
                        0,
                    )
                } catch (t: Throwable) {
                    RunResult("", "daemon start failed: ${t.message ?: t.javaClass.simpleName}", -1)
                }
            }
        }
    }

    /** Stops one daemon by id. Returns false when unknown. */
    suspend fun stopDaemon(id: Int): Boolean {
        return runMutex.withLock {
            withContext(Dispatchers.IO) {
                val proc = daemons.remove(id) ?: return@withContext false
                try { proc.destroy() } catch (_: Throwable) {}
                try { proc.waitFor(2, TimeUnit.SECONDS) } catch (_: Throwable) {}
                try { proc.destroyForcibly() } catch (_: Throwable) {}
                true
            }
        }
    }

    /** Human-readable daemon list for tools/chat. */
    suspend fun listDaemons(): String {
        return runMutex.withLock {
            withContext(Dispatchers.IO) {
                if (daemons.isEmpty()) "No background daemons running."
                else daemons.keys.sorted().joinToString("\n") { "#$it: running" }
            }
        }
    }

    private suspend fun killAllDaemons() {
        val ids: List<Int> = runMutex.withLock {
            withContext(Dispatchers.IO) { daemons.keys.toList() }
        }
        ids.forEach { stopDaemon(it) }
    }

    /**
     * Installs a minimal `curl` subset backed by the busybox `wget` applet
     * (Alpine minirootfs ships no curl/ss/nc, and models constantly reach
     * for `curl -I`). Supports: [-sS] [-L] [-I] [-o FILE] [-O] URL, GET only.
     */
    private fun installCurlShim() {
        val dest = File(alpineDir, "usr/local/bin/curl")
        dest.parentFile?.mkdirs()
        dest.writeText(CURL_SHIM)
        dest.setExecutable(true, false)
        if (!dest.canExecute()) throw IllegalStateException("curl shim not executable")
    }

    // ── Serve localhost ───────────────────────────────────────────────

    /**
     * Serves [workspaceDir] on 127.0.0.1:[port] via busybox httpd inside the
     * container. Returns the URL, or an error string prefixed with "ERROR:".
     */
    suspend fun serve(port: Int = DEFAULT_PREVIEW_PORT): String {
        if (!isValidServePort(port)) return "ERROR:Invalid port $port (use 1025–65535 except 8080)."
        if (!isInstalled()) return "ERROR:Linux Environment is not installed."
        stopServe()
        return runMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    workspaceDir.mkdirs()
                    val pb = ProcessBuilder(
                        prootBin.absolutePath,
                        "-r", alpineDir.absolutePath,
                        "-b", "${workspaceDir.absolutePath}:/workspace",
                        "-b", "/dev",
                        "-b", "/proc",
                        "-0",
                        "-w", "/workspace",
                        "httpd", "-f", "-p", "127.0.0.1:$port", "-h", "/workspace",
                    )
                    withContainerEnv(pb)
                    val proc = pb.start()
                    // Give httpd a moment; if it exits immediately the port is taken.
                    Thread.sleep(600)
                    val alive = try { proc.exitValue(); false } catch (_: IllegalThreadStateException) { true }
                    if (!alive) {
                        val err = readCapped(proc.errorStream)
                        return@withContext "ERROR:httpd failed to bind port $port. $err"
                    }
                    serveProcess = proc
                    _servedPort.value = port
                    serveJob = scope.launch {
                        try { proc.waitFor() } catch (_: Throwable) {}
                        _servedPort.value = null
                    }
                    "http://127.0.0.1:$port"
                } catch (t: Throwable) {
                    "ERROR:serve failed: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    suspend fun stopServe() {
        killAllDaemons()
        serveJob?.cancel()
        serveJob = null
        withContext(Dispatchers.IO) {
            try { serveProcess?.destroy() } catch (_: Throwable) {}
            try { serveProcess?.waitFor(2, TimeUnit.SECONDS) } catch (_: Throwable) {}
            try { serveProcess?.destroyForcibly() } catch (_: Throwable) {}
            serveProcess = null
        }
        _servedPort.value = null
    }

    fun close() = scope.cancel()

    /**
     * proot argv prefix shared by one-shot runs and the interactive PTY
     * shell. Callers append the guest command (e.g. `/bin/sh`).
     */
    fun prootArgv(): List<String> {
        workspaceDir.mkdirs()
        return listOf(
            prootBin.absolutePath,
            "-r", alpineDir.absolutePath,
            "-b", "${workspaceDir.absolutePath}:/workspace",
            "-b", "/dev",
            "-b", "/proc",
            "-0",
            "-w", "/workspace",
        )
    }

    /**
     * Full guest environment map for spawning an interactive PTY shell
     * (same proot paths as [run], plus a real TERM since output renders
     * through a terminal emulator, not a pipe).
     */
    fun containerEnvMap(): Map<String, String> {
        val libDir = nativeLibDir.absolutePath
        val tmpDir = File(app.filesDir, "proot-tmp").apply { mkdirs() }.absolutePath
        return mapOf(
            "LD_LIBRARY_PATH" to libDir,
            "PROOT_LOADER" to File(nativeLibDir, "libproot_loader.so").absolutePath,
            "PROOT_LOADER_32" to File(nativeLibDir, "libproot_loader32.so").absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_TMP_DIR" to tmpDir,
            "PROOT_L2S_DIR" to tmpDir,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/root",
            "TMPDIR" to "/tmp",
            "TEMP" to "/tmp",
            "TMP" to "/tmp",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
        )
    }

    // ── Internals ─────────────────────────────────────────────────────

    /** Points proot at its bundled engine: lib dir first on the search path. */
    private fun withContainerEnv(pb: ProcessBuilder) {
        val libDir = nativeLibDir.absolutePath
        val env = pb.environment()
        val existing = env["LD_LIBRARY_PATH"].orEmpty()
        env["LD_LIBRARY_PATH"] = if (existing.isBlank()) libDir else "$libDir:$existing"
        env["PROOT_LOADER"] = File(nativeLibDir, "libproot_loader.so").absolutePath
        env["PROOT_LOADER_32"] = File(nativeLibDir, "libproot_loader32.so").absolutePath
        // proot's own documented workaround: its seccomp fast path returns
        // bogus errno (e.g. EACCES on apk's dirfd-relative openat/linkat) on
        // newer kernels ("new syscall order"). Pure ptrace is the reference
        // path and always correct; the per-syscall cost is negligible here.
        env["PROOT_NO_SECCOMP"] = "1"
        // Guest shells inherit an empty PATH through proot: set Alpine's
        // default so apk/ls/busybox applets resolve without full paths.
        // Guest TMPDIR points inside the guest (/tmp is created on install);
        // PROOT_TMP_DIR stays host-side for proot's own glue files.
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["HOME"] = "/root"
        env["TMPDIR"] = "/tmp"
        env["TEMP"] = "/tmp"
        env["TMP"] = "/tmp"
        // proot's compiled-in tmp points at the Termux prefix: redirect all
        // temp (glue rootfs, f2fs probe, shm) into app storage.
        val tmpDir = File(app.filesDir, "proot-tmp").apply { mkdirs() }.absolutePath
        env["PROOT_TMP_DIR"] = tmpDir
        env["TMPDIR"] = tmpDir
        env["TEMP"] = tmpDir
        env["TMP"] = tmpDir
        env["PROOT_L2S_DIR"] = tmpDir
    }

    /**
     * Verifies every bundled native lib against its pinned SHA-256.
     * Throws when a lib is missing or corrupted (reinstall the app).
     */
    private fun verifyBundledLibs(bundled: BundledProot) {
        for (lib in bundled.libs) {
            val f = File(nativeLibDir, lib.fileName)
            if (!f.isFile) throw IllegalStateException("Bundled ${lib.fileName} missing — reinstall the app.")
            if (!verifySha256(f, lib.sha256)) {
                throw IllegalStateException("Bundled ${lib.fileName} corrupted — reinstall the app.")
            }
        }
        if (!prootBin.canExecute() && !prootBin.setExecutable(true)) {
            throw IllegalStateException("proot is not executable — reinstall the app.")
        }
    }

    private suspend fun downloadToFile(
        url: String,
        dest: File,
        expectedSha256: String?,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        if (expectedSha256 != null && !isValidSha256Hex(expectedSha256)) {
            throw IllegalArgumentException("Bad SHA-256 in manifest")
        }
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("User-Agent", "RaikaAI-container").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
                val body = resp.body ?: throw IllegalStateException("Empty body for $url")
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parent, dest.name + ".part")
                FileOutputStream(tmp).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    throw IllegalStateException("Downloaded file is empty: $url")
                }
                if (expectedSha256 != null && !verifySha256(tmp, expectedSha256)) {
                    tmp.delete()
                    throw IllegalStateException("SHA-256 mismatch for $url")
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    private fun verifySha256(file: File, hex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        return got.equals(hex, ignoreCase = true)
    }

    /**
     * Sanity-checks the unpacked rootfs. Repairs a missing /bin/sh from
     * busybox; anything worse throws with a directory listing for diagnosis.
     *
     * NOTE: guest symlinks are usually absolute (/bin/busybox), so plain
     * File.exists() — which resolves on the HOST — lies about them. All
     * checks here resolve guest-side via lstat/readlink.
     */
    private fun verifyRootfs(entries: Int) {
        if (entries < 100) {
            throw IllegalStateException(
                "Rootfs unpack suspicious ($entries entries). Top: ${topLevelListing()}",
            )
        }
        if (!guestResolves("bin/busybox")) {
            throw IllegalStateException(
                "bin/busybox missing after unpack. Top: ${topLevelListing()}",
            )
        }
        if (!guestResolves("bin/sh")) {
            try {
                val sh = File(alpineDir, "bin/sh")
                if (sh.exists() || runCatching { android.system.Os.lstat(sh.absolutePath) }.isSuccess) {
                    sh.delete()
                }
                android.system.Os.symlink("busybox", sh.absolutePath)
            } catch (t: Throwable) {
                throw IllegalStateException("bin/sh missing and relink failed: ${t.message}")
            }
            if (!guestResolves("bin/sh")) {
                throw IllegalStateException("bin/sh still unresolvable after relink")
            }
        }
    }

    /**
     * Guest-side existence: lstat must succeed and symlink chains (absolute
     * targets re-rooted at [alpineDir]) must land on a real file.
     */
    private fun guestResolves(relative: String): Boolean {
        var cur = File(alpineDir, relative)
        try {
            android.system.Os.lstat(cur.absolutePath)
        } catch (_: Throwable) {
            return false
        }
        repeat(8) {
            val target = runCatching { android.system.Os.readlink(cur.absolutePath) }.getOrNull()
                ?: return cur.isFile
            cur = if (target.startsWith("/")) {
                File(alpineDir, target.removePrefix("/"))
            } else {
                File(cur.parentFile, target)
            }
            try {
                android.system.Os.lstat(cur.absolutePath)
            } catch (_: Throwable) {
                return false
            }
        }
        return cur.isFile
    }

    private fun topLevelListing(): String {
        return runCatching {
            (alpineDir.listFiles()?.take(12)?.map { it.name } ?: emptyList()).toString()
        }.getOrDefault("?")
    }

    /**
     * Rewrites the guest resolv.conf from Android's CURRENT DNS servers
     * (captive portals / carriers / VPNs often block third-party DNS, so a
     * hardcoded 8.8.8.8 goes dark when the network changes). Returns the
     * servers used, for chat feedback.
     */
    suspend fun refreshResolvConf(): String {
        return withContext(Dispatchers.IO) {
            val servers = runCatching {
                val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
                    ?: return@runCatching emptyList<String>()
                val props = cm.getLinkProperties(cm.activeNetwork) ?: return@runCatching emptyList<String>()
                val all = props.dnsServers.mapNotNull { it.hostAddress?.takeIf { h -> h.isNotBlank() } }
                // IPv4 first: some mobile IPv6 DNS answers never arrive.
                (all.filter { !it.contains(':') } + all.filter { it.contains(':') }).distinct()
            }.getOrDefault(emptyList()).take(3)
                .ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
            val f = File(alpineDir, "etc/resolv.conf")
            f.parentFile?.mkdirs()
            f.writeText(servers.joinToString(separator = "") { "nameserver $it\n" })
            resolvLastRefresh = System.currentTimeMillis()
            KLog.d(TAG, "resolv.conf DNS: ${servers.joinToString()}")
            servers.joinToString()
        }
    }

    /** Minimal tar.gz extractor: regular files + dirs + symlinks. Returns entry count. */
    private fun unpackTarGz(tarGz: File, destDir: File): Int {
        var count = 0
        GZIPInputStream(BufferedInputStream(FileInputStream(tarGz), 64 * 1024)).buffered().use { gz ->
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                var read = 0
                while (read < 512) {
                    val n = gz.read(header, read, 512 - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 0) break // EOF
                if (read < 512) throw IllegalStateException("Truncated tar header")
                if (header.all { it == 0.toByte() }) {
                    // Two zero blocks end the archive; one is enough to stop here.
                    break
                }
                val nameBytes = header.copyOfRange(0, 100).takeWhile { it != 0.toByte() }.toByteArray()
                var name = longName ?: String(nameBytes, Charsets.UTF_8)
                longName = null
                val typeFlag = header[156].toInt().toChar()
                val sizeOctal = String(header.copyOfRange(124, 136)).trim().trimEnd('\u0000').trim()
                val size = if (sizeOctal.isEmpty()) 0L else sizeOctal.toLong(8)
                val modeOctal = String(header.copyOfRange(100, 108)).trim().trimEnd('\u0000').trim()
                val mode = runCatching { if (modeOctal.isEmpty()) 0 else modeOctal.toLong(8).toInt() }.getOrDefault(0)

                if (typeFlag == 'g' || typeFlag == 'x') {
                    // pax extended header: skip payload
                    skipFully(gz, size)
                    skipPadding(gz, size)
                    continue
                }
                if (typeFlag == 'L') {
                    // GNU long name: payload is the real name
                    val payload = ByteArray(size.toInt())
                    readFully(gz, payload)
                    longName = String(payload, Charsets.UTF_8).trimEnd('\u0000').trim()
                    skipPadding(gz, size)
                    continue
                }
                val target = File(destDir, name)
                // Zip-slip guard
                if (!target.canonicalPath.startsWith(destDir.canonicalPath)) {
                    throw IllegalStateException("Tar entry escapes destination: $name")
                }
                // Guest-relative name (tar uses ./ prefix): needed to rewrite
                // absolute symlinks into relative ones below.
                val guestName = name.removePrefix("./").trimStart('/')
                when (typeFlag) {
                    '5' -> {
                        target.mkdirs()
                        count++
                    }
                    '2' -> {
                        // Symlink (Alpine links every applet to busybox).
                        // Link target lives at header offset 157, length 100.
                        val linkBytes = header.copyOfRange(157, 257).takeWhile { it != 0.toByte() }.toByteArray()
                        val linkTarget = String(linkBytes, Charsets.UTF_8)
                        if (linkTarget.isBlank()) throw IllegalStateException("Empty symlink target: $name")
                        // Host-side stat() must resolve our links too (proot
                        // consistency checks run on the host): rewrite absolute
                        // guest targets into relative ones. Inside the guest
                        // both forms resolve identically.
                        val guestTarget = relativizeGuestLink(guestName, linkTarget)
                        target.parentFile?.mkdirs()
                        // lstat, not exists(): broken/absolute links must be
                        // replaced too, and File.delete() removes the link
                        // itself (never follows it).
                        if (isSymlink(target) || target.exists()) {
                            if (!target.delete()) throw IllegalStateException("Cannot replace $name")
                        }
                        try {
                            android.system.Os.symlink(guestTarget, target.absolutePath)
                            count++
                        } catch (t: Throwable) {
                            throw IllegalStateException("Symlink failed ($name -> $guestTarget): ${t.message}")
                        }
                    }
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var remaining = size
                            val buf = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val want = minOf(buf.size.toLong(), remaining).toInt()
                                val n = gz.read(buf, 0, want)
                                if (n < 0) throw IllegalStateException("Truncated tar payload: $name")
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        if (mode and 0b001001001 != 0) target.setExecutable(true, false)
                        count++
                    }
                }
                skipPadding(gz, size)
            }
        }
        return count
    }

    private fun skipPadding(gz: java.io.InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512)
        if (pad > 0) skipFully(gz, pad)
    }

    private fun skipFully(gz: java.io.InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(8 * 1024)
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val r = gz.read(buf, 0, want)
            if (r < 0) throw IllegalStateException("Truncated tar stream")
            remaining -= r
        }
    }

    private fun readFully(gz: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = gz.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalStateException("Truncated tar stream")
            off += n
        }
    }

    private fun makeBusyboxExecutable() {
        listOf("bin/busybox", "bin/sh", "bin/bbconfig").forEach { rel ->
            val f = File(alpineDir, rel)
            if (f.isFile) f.setExecutable(true, false)
        }
    }

    private fun readCapped(stream: java.io.InputStream, keepTail: Boolean = false): String {
        return try {
            if (!keepTail) {
                val buf = ByteArray(OUTPUT_CAP + 1)
                var total = 0
                while (total <= OUTPUT_CAP) {
                    val n = stream.read(buf, total, minOf(buf.size - total, 8192))
                    if (n < 0) break
                    total += n
                    if (total > OUTPUT_CAP) break
                }
                var text = String(buf, 0, minOf(total, OUTPUT_CAP), Charsets.UTF_8)
                if (total > OUTPUT_CAP) text += "\n…(output truncated)"
                text
            } else {
                // Verbose traces: the failure is at the END. Keep the tail.
                val chunks = ArrayDeque<ByteArray>()
                var total = 0
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    chunks.addLast(buf.copyOf(n))
                    total += n
                    while (total > OUTPUT_CAP) {
                        total -= chunks.removeFirst().size
                    }
                }
                val out = ByteArray(total)
                var off = 0
                for (c in chunks) {
                    c.copyInto(out, off)
                    off += c.size
                }
                "…(showing last ${total / 1024}KB)\n" + String(out, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            ""
        }
    }
}

/**
 * Rewrites an absolute guest symlink target into a relative one so the
 * link resolves both inside the guest AND on the host (proot stat checks
 * run host-side, where guest-absolute targets don't exist).
 * Targets outside the rootfs (/proc, /dev, sys) are kept absolute.
 */
internal fun relativizeGuestLink(guestName: String, linkTarget: String): String {
    if (!linkTarget.startsWith("/")) return linkTarget
    val destSegs = linkTarget.removePrefix("/").split("/").filter { it.isNotEmpty() }
    if (destSegs.isEmpty()) return linkTarget
    // Only rewrite targets that stay inside the rootfs (first segment is
    // a real top-level dir, not proc/dev/sys).
    if (destSegs[0] in setOf("proc", "dev", "sys")) return linkTarget
    val parentSegs = guestName.split("/").filter { it.isNotEmpty() }.dropLast(1)
    var common = 0
    while (common < parentSegs.size && common < destSegs.size &&
        parentSegs[common] == destSegs[common]
    ) {
        common++
    }
    val up = List(parentSegs.size - common) { ".." }
    return (up + destSegs.drop(common)).joinToString("/").ifEmpty { "." }
}
