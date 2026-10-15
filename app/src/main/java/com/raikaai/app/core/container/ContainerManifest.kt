package com.raikaai.app.core.container

/**
 * Fase 0: manifest + status only. No download, no exec.
 *
 * Two downloadable artifacts (never bundled in APK):
 *  - proot binary per-ABI, hosted on our own GitHub Release
 *  - alpine minirootfs tarball, hosted upstream (dl-cdn.alpinelinux.org)
 */

/** Alpine release pinned for Fase 0. Bump in one place. */
const val ALPINE_VERSION = "3.21"
const val ALPINE_ARCH = "aarch64"

/** Upstream Alpine minirootfs (public CDN, no auth). */
const val ALPINE_ROOTFS_URL =
    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"

/** Our own GitHub Release base for the proot binary (per-ABI). */
const val PROOT_RELEASE_BASE = "https://github.com/kouzen-neo/raikaai/releases/download/container-proot-v1"

/** Reserved ports. 8080 is taken by the embedded OpenAI server. */
const val OPENAI_SERVER_PORT = 8080
const val DEFAULT_PREVIEW_PORT = 8081

data class ContainerManifest(
    val alpineVersion: String = ALPINE_VERSION,
    val rootfsUrl: String = ALPINE_ROOTFS_URL,
    val rootfsSha256: String = "", // filled when release checksum is pinned (Fase 1)
    val prootUrlArm64: String = "$PROOT_RELEASE_BASE/proot-arm64-v8a",
    val prootSha256Arm64: String = "", // filled when binary is published (Fase 1)
    val prootUrlX8664: String = "$PROOT_RELEASE_BASE/proot-x86_64",
    val prootSha256X8664: String = "",
) {
    /** Resolves the proot URL for an Android ABI. Null = unsupported in Fase 0. */
    fun prootUrlForAbi(abi: String): String? = when (abi) {
        "arm64-v8a" -> prootUrlArm64
        "x86_64" -> prootUrlX8664
        else -> null
    }
}

/** Container install status, derived from Settings + filesystem check (Fase 1). */
enum class ContainerStatus {
    NOT_INSTALLED,
    INSTALLED,
}

/** SHA-256 hex format check (64 lowercase hex chars). Does not hash anything. */
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
 * Fase 2 policy gate (pure function, no Android deps so it is unit-testable now).
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
