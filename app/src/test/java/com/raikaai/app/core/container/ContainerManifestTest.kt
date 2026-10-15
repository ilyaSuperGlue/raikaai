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
    fun prootUrlResolvesPerAbi() {
        val m = ContainerManifest()
        assertTrue(m.prootUrlForAbi("arm64-v8a")!!.contains("arm64"))
        assertTrue(m.prootUrlForAbi("x86_64")!!.contains("x86_64"))
        assertEquals(null, m.prootUrlForAbi("armeabi-v7a"))
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
        assertEquals(InstallPolicy.ASK, installPolicyFor("apk add --no-cache some-unknown-pkg"))
        assertEquals(InstallPolicy.ASK, installPolicyFor("pip install requests"))
        assertEquals(InstallPolicy.ASK, installPolicyFor("npm install -g server"))
        assertEquals(InstallPolicy.DENY, installPolicyFor("rm -rf /"))
        assertEquals(InstallPolicy.DENY, installPolicyFor("apk add --allow-untrusted foo"))
    }
}
