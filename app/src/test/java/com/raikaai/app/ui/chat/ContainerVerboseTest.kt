package com.raikaai.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerVerboseTest {

    @Test
    fun runBlockShowsCommandAndOutput() {
        val block = formatContainerRunBlock(
            command = "ls /workspace",
            stdout = "a.txt\nb.txt\n",
            stderr = "",
            exitCode = 0,
            timedOut = false,
            autoApproved = false,
        )
        assertTrue(block.contains("`ls /workspace`"))
        assertTrue(block.contains("a.txt"))
        assertTrue(block.contains("exit=0"))
    }

    @Test
    fun runBlockMarksAutoApprovedAndStderr() {
        val block = formatContainerRunBlock(
            command = "cat x",
            stdout = "",
            stderr = "No such file",
            exitCode = 1,
            timedOut = false,
            autoApproved = true,
        )
        assertTrue(block.contains("auto"))
        assertTrue(block.contains("No such file"))
        assertTrue(block.contains("exit=1"))
    }

    @Test
    fun runBlockStaysCapped() {
        val block = formatContainerRunBlock(
            command = "yes",
            stdout = "y\n".repeat(5000),
            stderr = "e\n".repeat(2000),
            exitCode = 0,
            timedOut = true,
            autoApproved = false,
        )
        assertTrue(block.length <= 1600)
        assertTrue(block.contains("timed out"))
    }
}
