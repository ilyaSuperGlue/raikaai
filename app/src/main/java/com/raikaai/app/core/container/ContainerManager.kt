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
        const val ROOTFS_SCHEMA = 2

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
    fun diagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        sb.appendLine("marker=${runCatching { File(alpineDir, ".installed").readText().trim() }.getOrDefault("(none)")}")
        sb.appendLine("schema=$ROOTFS_SCHEMA installed=${isInstalled()} outdated=${isOutdated()}")
        val bb = File(alpineDir, "bin/busybox")
        sb.appendLine("busybox exists=${bb.isFile} size=${if (bb.isFile) bb.length() else -1}")
        val sh = File(alpineDir, "bin/sh")
        val shTarget = runCatching { android.system.Os.readlink(sh.absolutePath) }.getOrNull()
        sb.appendLine("bin/sh exists=${sh.exists()} isSymlink=${shTarget != null} target=${shTarget ?: "-"}")
        sb.appendLine("alpineTop=${topLevelListing()}")
        sb.appendLine("alpineEntries=${countEntries(alpineDir, 0)}")
        sb.appendLine("nativeLibs=${nativeLibDir.listFiles()?.map { it.name }?.sorted() ?: "?"}")
        return sb.toString()
    }

    private fun countEntries(dir: File, depth: Int): Int {
        if (depth > 3) return 0
        val kids = dir.listFiles() ?: return 0
        var n = kids.size
        if (n > 2000) return n
        for (k in kids) if (k.isDirectory) n += countEntries(k, depth + 1)
        return n
    }

    fun workspaceFiles(): List<File> {
        val dir = workspaceDir
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

                File(alpineDir, "etc/resolv.conf").apply {
                    parentFile?.mkdirs()
                    writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                }
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
    suspend fun run(command: String, timeoutSec: Long = 15): RunResult {
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
        return runMutex.withLock {
            withContext(Dispatchers.IO) {
                var proc: Process? = null
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
                        "/bin/sh", "-c", clean,
                    )
                    pb.redirectErrorStream(false)
                    withContainerEnv(pb)
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
                            readCapped(proc.inputStream),
                            readCapped(proc.errorStream),
                            -1,
                            timedOut = true,
                        )
                    }
                    RunResult(readCapped(proc.inputStream), readCapped(proc.errorStream), proc.exitValue())
                } catch (t: Throwable) {
                    try { proc?.destroyForcibly() } catch (_: Throwable) {}
                    RunResult("", "run failed: ${t.message ?: t.javaClass.simpleName}", -1)
                }
            }
        }
    }

    /** Forcibly stops the currently running command, if any (terminal Ctrl+C). */
    fun cancelRun() {
        try { currentProc?.destroyForcibly() } catch (_: Throwable) {}
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

    // ── Internals ─────────────────────────────────────────────────────

    /** Points proot at its bundled engine: lib dir first on the search path. */
    private fun withContainerEnv(pb: ProcessBuilder) {
        val libDir = nativeLibDir.absolutePath
        val env = pb.environment()
        val existing = env["LD_LIBRARY_PATH"].orEmpty()
        env["LD_LIBRARY_PATH"] = if (existing.isBlank()) libDir else "$libDir:$existing"
        env["PROOT_LOADER"] = File(nativeLibDir, "libproot_loader.so").absolutePath
        env["PROOT_LOADER_32"] = File(nativeLibDir, "libproot_loader32.so").absolutePath
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
     */
    private fun verifyRootfs(entries: Int) {
        if (entries < 100) {
            throw IllegalStateException(
                "Rootfs unpack suspicious ($entries entries). Top: ${topLevelListing()}",
            )
        }
        if (!File(alpineDir, "bin/busybox").isFile) {
            throw IllegalStateException(
                "bin/busybox missing after unpack. Top: ${topLevelListing()}",
            )
        }
        // File.exists() follows symlinks, so this is false for missing AND broken links.
        if (!File(alpineDir, "bin/sh").exists()) {
            try {
                android.system.Os.symlink("busybox", File(alpineDir, "bin/sh").absolutePath)
            } catch (t: Throwable) {
                throw IllegalStateException("bin/sh missing and relink failed: ${t.message}")
            }
        }
    }

    private fun topLevelListing(): String {
        return runCatching {
            (alpineDir.listFiles()?.take(12)?.map { it.name } ?: emptyList()).toString()
        }.getOrDefault("?")
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
                        target.parentFile?.mkdirs()
                        if (target.exists() || target.isDirectory) target.deleteRecursively()
                        try {
                            android.system.Os.symlink(linkTarget, target.absolutePath)
                            count++
                        } catch (t: Throwable) {
                            throw IllegalStateException("Symlink failed ($name -> $linkTarget): ${t.message}")
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

    private fun readCapped(stream: java.io.InputStream): String {
        return try {
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
        } catch (_: Throwable) {
            ""
        }
    }
}
