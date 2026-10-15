package com.raikaai.app.core.container

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerUnpackTest {

    @Test
    fun relativeTargetPassesThrough() {
        assertEquals("busybox", relativizeGuestLink("bin/sh", "busybox"))
        assertEquals("../lib/x.so", relativizeGuestLink("bin/sh", "../lib/x.so"))
    }

    @Test
    fun absoluteBusyboxLinkBecomesRelative() {
        // The Alpine case: bin/sh -> /bin/busybox
        assertEquals("busybox", relativizeGuestLink("bin/sh", "/bin/busybox"))
    }

    @Test
    fun absoluteLinkAcrossDirsUsesDotDot() {
        assertEquals("../../bin/busybox", relativizeGuestLink("usr/bin/env", "/bin/busybox"))
        assertEquals("libz.so.1.3.1", relativizeGuestLink("usr/lib/libz.so.1", "/usr/lib/libz.so.1.3.1"))
    }

    @Test
    fun outsideRootfsStaysAbsolute() {
        assertEquals("/proc/self", relativizeGuestLink("bin/x", "/proc/self"))
        assertEquals("/dev/null", relativizeGuestLink("bin/x", "/dev/null"))
        assertEquals("/sys/y", relativizeGuestLink("bin/x", "/sys/y"))
    }

    @Test
    fun rootTargetStaysAbsolute() {
        assertEquals("/", relativizeGuestLink("bin/x", "/"))
    }
}
