package com.raikaai.app.core.container

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class ContainerFileToolsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("container_test_workspace").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun writeFileCreatesNestedDirectoriesAndOverwrites() {
        val relPath = "src/components/Button.tsx"
        val code = "export const Button = () => <button>Click</button>;"

        val res = ContainerFileOps.writeFile(relPath, code, tempDir)
        assertEquals(relPath, res.path)
        assertEquals(1, res.lines)
        assertTrue(res.bytes > 0)

        val target = File(tempDir, relPath)
        assertTrue(target.exists())
        assertEquals(code, target.readText())

        // Overwrite with multiline
        val newCode = "import React from 'react';\n\nexport const Button = () => {\n  return <button>Click</button>;\n};"
        val res2 = ContainerFileOps.writeFile(relPath, newCode, tempDir)
        assertEquals(5, res2.lines)
        assertEquals(newCode, target.readText())
    }

    @Test
    fun editFileReplacesSingleOccurrence() {
        val relPath = "script.py"
        ContainerFileOps.writeFile(relPath, "def hello():\n    print('world')\n", tempDir)

        val res = ContainerFileOps.editFile(
            relativePath = relPath,
            oldString = "print('world')",
            newString = "print('raikaai')",
            replaceAll = false,
            baseDir = tempDir,
        )
        assertEquals(1, res.occurrences)
        assertEquals("def hello():\n    print('raikaai')\n", File(tempDir, relPath).readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun editFileFailsIfMultipleOccurrencesWithoutReplaceAll() {
        val relPath = "data.txt"
        ContainerFileOps.writeFile(relPath, "apple\nbanana\napple\ncherry", tempDir)

        ContainerFileOps.editFile(
            relativePath = relPath,
            oldString = "apple",
            newString = "orange",
            replaceAll = false,
            baseDir = tempDir,
        )
    }

    @Test
    fun editFileWithReplaceAllReplacesEveryOccurrence() {
        val relPath = "data.txt"
        ContainerFileOps.writeFile(relPath, "apple\nbanana\napple\ncherry", tempDir)

        val res = ContainerFileOps.editFile(
            relativePath = relPath,
            oldString = "apple",
            newString = "orange",
            replaceAll = true,
            baseDir = tempDir,
        )
        assertEquals(2, res.occurrences)
        assertEquals("orange\nbanana\norange\ncherry", File(tempDir, relPath).readText())
    }

    @Test
    fun readFileReturnsNumberedLinesAndSlices() {
        val relPath = "notes.txt"
        val content = (1..10).joinToString("\n") { "line $it" }
        ContainerFileOps.writeFile(relPath, content, tempDir)

        // Read all
        val read1 = ContainerFileOps.readFile(relPath, offset = 1, limit = 20, baseDir = tempDir)
        assertEquals(10, read1.totalLines)
        assertEquals(1, read1.startLine)
        assertEquals(10, read1.endLine)
        assertFalse(read1.truncated)
        assertTrue(read1.content.startsWith("1: line 1\n2: line 2"))

        // Read slice
        val read2 = ContainerFileOps.readFile(relPath, offset = 4, limit = 3, baseDir = tempDir)
        assertEquals(10, read2.totalLines)
        assertEquals(4, read2.startLine)
        assertEquals(6, read2.endLine)
        assertTrue(read2.truncated)
        assertEquals("4: line 4\n5: line 5\n6: line 6", read2.content)
    }

    @Test
    fun readEmptyFileReturnsEmptyMarker() {
        val relPath = "empty.txt"
        ContainerFileOps.writeFile(relPath, "", tempDir)
        val read = ContainerFileOps.readFile(relPath, baseDir = tempDir)
        assertEquals(0, read.totalLines)
        assertEquals("(empty file)", read.content)
    }

    @Test(expected = FileNotFoundException::class)
    fun readFileThrowsOnMissingFile() {
        ContainerFileOps.readFile("nonexistent.txt", baseDir = tempDir)
    }

    @Test(expected = SecurityException::class)
    fun resolveWorkspaceFileBlocksPathTraversal() {
        ContainerFileOps.resolveWorkspaceFile("../../secrets.json", tempDir)
    }

    @Test
    fun resolveWorkspaceFileNormalizesWorkspacePrefix() {
        val f1 = ContainerFileOps.resolveWorkspaceFile("/workspace/app.js", tempDir)
        val f2 = ContainerFileOps.resolveWorkspaceFile("workspace/app.js", tempDir)
        val f3 = ContainerFileOps.resolveWorkspaceFile("app.js", tempDir)
        val f4 = ContainerFileOps.resolveWorkspaceFile("./app.js", tempDir)
        val f5 = ContainerFileOps.resolveWorkspaceFile("/workspace/./app.js", tempDir)
        assertEquals(f1.absolutePath, f2.absolutePath)
        assertEquals(f1.absolutePath, f3.absolutePath)
        assertEquals(f1.absolutePath, f4.absolutePath)
        assertEquals(f1.absolutePath, f5.absolutePath)
    }
}
