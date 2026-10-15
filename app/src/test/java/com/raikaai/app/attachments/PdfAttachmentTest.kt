package com.raikaai.app.attachments

import com.raikaai.app.data.ChatAttachment
import com.raikaai.app.ui.chat.AttachmentPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

class PdfAttachmentTest {

    @Test
    fun `extracts text from uncompressed PDF stream`() {
        val tempPdf = File.createTempFile("test_plain", ".pdf")
        try {
            val pdfContent = """
                %PDF-1.4
                1 0 obj
                << /Length 85 >>
                stream
                BT
                /F1 12 Tf
                (Hello from uncompressed PDF) Tj
                T*
                [(Second) -200 (Line)] TJ
                ET
                endstream
                endobj
                %%EOF
            """.trimIndent()
            tempPdf.writeBytes(pdfContent.toByteArray(Charsets.ISO_8859_1))

            val extracted = AttachmentProcessor.extractPdfText(tempPdf, 5000)
            assertNotNull(extracted)
            assertTrue("Expected to contain text, got: $extracted", extracted!!.contains("Hello from uncompressed PDF"))
            assertTrue("Expected to contain second line, got: $extracted", extracted.contains("Second Line"))
        } finally {
            tempPdf.delete()
        }
    }

    @Test
    fun `extracts text from FlateDecode compressed PDF stream`() {
        val tempPdf = File.createTempFile("test_flate", ".pdf")
        try {
            val streamText = "BT\n/F1 12 Tf\n(Compressed Report Title) Tj\nT*\n[(Chapter) -200 (One)] TJ\nET"
            val deflatedStream = ByteArrayOutputStream().apply {
                DeflaterOutputStream(this).use { it.write(streamText.toByteArray(Charsets.ISO_8859_1)) }
            }.toByteArray()

            val header = "%PDF-1.4\n1 0 obj\n<< /Filter /FlateDecode /Length ${deflatedStream.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
            val footer = "\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1)

            val fullPdf = ByteArrayOutputStream().apply {
                write(header)
                write(deflatedStream)
                write(footer)
            }.toByteArray()
            tempPdf.writeBytes(fullPdf)

            val extracted = AttachmentProcessor.extractPdfText(tempPdf, 5000)
            assertNotNull(extracted)
            assertTrue("Expected extracted compressed text, got: $extracted", extracted!!.contains("Compressed Report Title"))
            assertTrue("Expected second line, got: $extracted", extracted.contains("Chapter One"))
        } finally {
            tempPdf.delete()
        }
    }

    @Test
    fun `defaultPromptForAttachments chooses summarize document for PDF files with text`() {
        val prepared = listOf(
            PreparedAttachment(
                attachment = ChatAttachment(
                    type = "file",
                    path = "/data/chat_files/doc.pdf",
                    name = "financial_report.pdf",
                    mimeType = "application/pdf",
                ),
                extractedText = "Q3 Revenue reached $50M.",
            ),
        )
        val prompt = AttachmentPipeline.defaultPromptForAttachments(prepared)
        assertEquals("Summarize the attached document(s).", prompt)

        val fullPrompt = AttachmentPipeline.buildPromptText(
            body = "",
            hasAudio = false,
            prepared = prepared,
        )
        assertTrue(fullPrompt.startsWith("Summarize the attached document(s)."))
        assertTrue(fullPrompt.contains("[Attached file \"financial_report.pdf\":]"))
        assertTrue(fullPrompt.contains("Q3 Revenue reached $50M."))
    }

    @Test
    fun `fallback note is appended when file has unreadable text without dropping attachment`() {
        val prepared = listOf(
            PreparedAttachment(
                attachment = ChatAttachment(
                    type = "file",
                    path = "/data/chat_files/binary.pdf",
                    name = "binary.pdf",
                    mimeType = "application/pdf",
                ),
                extractedText = null,
            ),
        )
        val prompt = AttachmentPipeline.defaultPromptForAttachments(prepared)
        assertEquals("What can you tell me about the attached file(s)?", prompt)

        val fullPrompt = AttachmentPipeline.buildPromptText(
            body = "Please analyze this",
            hasAudio = false,
            prepared = prepared,
        )
        assertTrue(fullPrompt.contains("Please analyze this"))
        assertTrue(fullPrompt.contains("[Note: attached file(s) \"binary.pdf\" could not be read as text.]"))
    }
}
