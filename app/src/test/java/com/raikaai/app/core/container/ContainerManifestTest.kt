package com.raikaai.app.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerManifestTest {

    @Test
    fun defaultManifestPointsToAlpine321() {
        val m = ContainerManifest()
        assertEquals("3.21", m.alpineVersion)
        assertTrue(m.rootfsUrl.contains("alpine-minirootfs-3.21"))
        assertTrue(m.rootfsUrl.startsWith("https://"))
    }

    @Test
    fun bundledProotResolvesPerAbiWithPinnedHashes() {
        val arm64 = bundledProotForAbi("arm64-v8a")!!
        assertEquals("arm64-v8a", arm64.abi)
        assertEquals(5, arm64.libs.size)
        assertTrue(arm64.allHashesValid())
        assertTrue(arm64.libs.any { it.fileName == "libproot.so" })
        val x86 = bundledProotForAbi("x86_64")!!
        assertTrue(x86.allHashesValid())
        assertEquals(null, bundledProotForAbi("armeabi-v7a"))
    }

    @Test
    fun sha256FormatCheck() {
        assertTrue(isValidSha256Hex("a".repeat(64)))
        assertTrue(isValidSha256Hex("ABCDEF0123456789".repeat(4)))
        assertFalse(isValidSha256Hex(""))
        assertFalse(isValidSha256Hex("abc"))
        assertFalse(isValidSha256Hex("z".repeat(64)))
    }

    @Test
    fun servePortValidation() {
        assertFalse(isValidServePort(80))
        assertFalse(isValidServePort(8080)) // taken by OpenAI server
        assertTrue(isValidServePort(8081))
        assertTrue(isValidServePort(3000))
        assertFalse(isValidServePort(70000))
    }

    @Test
    fun installPolicyGate() {
        assertEquals(InstallPolicy.ALLOW, installPolicyFor("apk add --no-cache ffmpeg"))
        assertEquals(InstallPolicy.ALLOW, installPolicyFor("apk add curl"))
        assertEquals(InstallPolicy.ALLOW, installPolicyFor("fastfetch"))
        assertEquals(InstallPolicy.ALLOW, installPolicyFor("ls -la /workspace"))
        assertEquals(InstallPolicy.ALLOW, installPolicyFor("cat /etc/os-release"))
        assertEquals(InstallPolicy.ASK, installPolicyFor("echo hi > out.txt"))
        assertEquals(InstallPolicy.ASK, installPolicyFor("ls /tmp; echo done"))
        assertEquals(InstallPolicy.DENY, installPolicyFor("ls; rm -rf /tmp/x"))
        assertEquals(InstallPolicy.ASK, installPolicyFor("apk add --no-cache some-unknown-pkg"))
        assertEquals(InstallPolicy.ASK, installPolicyFor("pip install requests"))
        assertEquals(InstallPolicy.ASK, installPolicyFor("npm install -g server"))
        assertEquals(InstallPolicy.DENY, installPolicyFor("rm -rf /"))
        assertEquals(InstallPolicy.DENY, installPolicyFor("apk add --allow-untrusted foo"))
    }

    @Test
    fun joinBrokenPathsOnlyInsideQuotes() {
        assertEquals(
            "cat \"/workspace/kzkt/x.zip\"",
            joinBrokenPaths("cat \"/workspace/\nkzkt/x.zip\""),
        )
        assertEquals(
            "cat '/workspace/kzkt/x.zip'",
            joinBrokenPaths("cat '/workspace/  \n  kzkt/x.zip'"),
        )
        // Real separators outside quotes are untouched.
        assertEquals("cd /tmp\nls", joinBrokenPaths("cd /tmp\nls"))
        assertEquals("echo hi", joinBrokenPaths("echo hi"))
        // Other wraps inside quotes join with a space.
        assertEquals("echo \"a b\"", joinBrokenPaths("echo \"a\nb\""))
    }
}
