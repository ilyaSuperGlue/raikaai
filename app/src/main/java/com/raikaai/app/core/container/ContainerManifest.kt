package com.raikaai.app.core.container

/**
 * Alpine PRoot environment manifest.
 *
 * proot + helpers + deps ship as native libs (`jniLibs/<abi>/lib*.so`) so
 * the package manager extracts them with exec permission — running
 * binaries from `filesDir` is blocked (EACCES) on HyperOS / newer Android.
 * Only the Alpine minirootfs tarball is downloaded, from upstream
 * (dl-cdn.alpinelinux.org).
 */

/** Alpine release pinned for the rootfs download. Bump in one place. */
const val ALPINE_VERSION = "3.21"
const val ALPINE_ARCH = "aarch64"

/** Upstream Alpine minirootfs (public CDN, no auth). */
const val ALPINE_ROOTFS_URL =
    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"

/** Bundled proot build provenance (see assets/container/ATTRIBUTION.md). */
const val PROOT_VERSION = "5.1.107.96 (Termux NDK build)"

/** Reserved ports. 8080 is taken by the embedded OpenAI server. */
const val OPENAI_SERVER_PORT = 8080
const val DEFAULT_PREVIEW_PORT = 8081

/**
 * One bundled native lib (shipped in jniLibs, extracted by the installer
 * with exec permission).
 */
data class BundledLib(
    val fileName: String,
    val sha256: String,
)

data class BundledProot(
    val abi: String,
    val libs: List<BundledLib>,
) {
    fun allHashesValid(): Boolean = libs.all { isValidSha256Hex(it.sha256) }
}

data class ContainerManifest(
    val alpineVersion: String = ALPINE_VERSION,
    val rootfsUrl: String = ALPINE_ROOTFS_URL,
    val rootfsSha256: String = "", // pin when the release checksum is known
)

/** Resolves the bundled proot payload for an Android ABI. Null = unsupported. */
fun bundledProotForAbi(abi: String): BundledProot? = when (abi) {
    "arm64-v8a" -> BundledProot(
        abi = "arm64-v8a",
        libs = listOf(
            BundledLib("libproot.so", "fae5a21c8e144ac5e5509758d110017c8777a35af43dced2fdf1f943c9c22f3f"),
            BundledLib("libtalloc_prt.so", "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309"),
            BundledLib("libshmem_prt.so", "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731"),
            BundledLib("libproot_loader.so", "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551"),
            BundledLib("libproot_loader32.so", "c429996fee730e1a4fe12625b22dab5a5bc2847a4cae274d36fd4a19cd8299a8"),
        ),
    )
    "x86_64" -> BundledProot(
        abi = "x86_64",
        libs = listOf(
            BundledLib("libproot.so", "af4b41a25abe601ca2128acf74bdf1aa87286fc1293a288977961b5cce7a433f"),
            BundledLib("libtalloc_prt.so", "7fa6722a4320495b1b85832fc7fd02de0cb367b45f116c1921b2830a6936da90"),
            BundledLib("libshmem_prt.so", "092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f"),
            BundledLib("libproot_loader.so", "a836b0569c84b1661333d16bacf6b396ff08fea816a5c936a82e08ddeeca8341"),
            BundledLib("libproot_loader32.so", "fd5b394aa0801619e293b76fe6e830a3beb363aed4fa4b9a5a41f1388a0cb9b9"),
        ),
    )
    else -> null
}

/** Container install status, derived from Settings + filesystem check. */
enum class ContainerStatus {
    NOT_INSTALLED,
    INSTALLED,
}

/** SHA-256 hex format check (64 hex chars). Does not hash anything. */
fun isValidSha256Hex(s: String): Boolean {
    if (s.length != 64) return false
    return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

/** Preview/serve ports must be high ports and avoid the OpenAI server port. */
fun isValidServePort(port: Int): Boolean {
    if (port < 1024 || port > 65535) return false
    if (port == OPENAI_SERVER_PORT) return false
    return true
}

/**
 * Fase 2 policy gate (pure function, no Android deps so it is unit-testable).
 * - DENY: destructive or untrusted-source installs.
 * - ASK: package managers that pull arbitrary code (pip/npm), big packages,
 *   or third-party repos.
 * - ALLOW: small official Alpine packages.
 */
enum class InstallPolicy { ALLOW, ASK, DENY }

private val allowlistedApkPackages = setOf(
    "curl", "jq", "sqlite", "ffmpeg", "python3", "py3-pip",
    "nodejs", "npm", "git", "ripgrep", "imagemagick",
)

fun installPolicyFor(command: String): InstallPolicy {
    val cmd = command.trim()
    if (cmd.isEmpty()) return InstallPolicy.DENY
    // Destructive patterns are never auto-run.
    if (cmd.contains("rm -rf /") || cmd.contains("--allow-untrusted")) return InstallPolicy.DENY
    if (cmd.contains("--repository") && !cmd.contains("dl-cdn.alpinelinux.org")) return InstallPolicy.DENY
    // Arbitrary code managers always ask.
    if (cmd.startsWith("pip install") || cmd.startsWith("pip3 install")) return InstallPolicy.ASK
    if (cmd.startsWith("npm i") || cmd.startsWith("npm install")) return InstallPolicy.ASK
    // Official apk packages: allowlist = auto, rest = ask.
    if (cmd.startsWith("apk add")) {
        val pkg = cmd.removePrefix("apk add").replace("--no-cache", "").trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (pkg in allowlistedApkPackages) InstallPolicy.ALLOW else InstallPolicy.ASK
    }
    return InstallPolicy.ASK
}
