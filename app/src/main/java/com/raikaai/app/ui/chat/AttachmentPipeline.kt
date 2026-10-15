package com.raikaai.app.ui.chat

import com.raikaai.app.attachments.PreparedAttachment
import com.raikaai.app.data.AttachmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Attachment helpers shared by the send / edit-regen / regen paths,
 * previously inlined in ChatViewModel. Disk I/O always runs off the main
 * thread; prompt assembly is pure.
 */
object AttachmentPipeline {

    /** Reads a file off the main thread. Null path, missing file, or any I/O error → null. */
    suspend fun readBytesOffMain(path: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            path?.let {
                try {
                    File(it).takeIf { f -> f.exists() }?.readBytes()
                } catch (_: Exception) {
                    null
                }
            }
        }

    /** Default prompt when the user sends only attachment(s) without text. */
    fun defaultPromptForAttachments(prepared: List<PreparedAttachment>): String {
        val standaloneImages = prepared.filter { it.attachment.kind == AttachmentType.IMAGE && !it.attachment.name.contains(" (p") }
        val hasDocs = prepared.any { it.attachment.kind == AttachmentType.FILE && it.extractedText != null }
        val hasAnyImages = prepared.any { it.attachment.kind == AttachmentType.IMAGE }
        return when {
            standaloneImages.isNotEmpty() && hasDocs -> "Describe the images and summarize the attached documents."
            hasDocs -> "Summarize the attached document(s)."
            hasAnyImages -> if (prepared.count { it.attachment.kind == AttachmentType.IMAGE } > 1) "Describe these images." else "Describe this image."
            prepared.any { it.attachment.kind == AttachmentType.FILE } -> "What can you tell me about the attached file(s)?"
            else -> "Hello"
        }
    }

    /**
     * Assembles the user message text: body (or attachment fallback prompt)
     * plus extracted document text and unreadable-file notes.
     */
    fun buildPromptText(
        body: String,
        hasAudio: Boolean,
        prepared: List<PreparedAttachment>,
    ): String =
        buildString {
            append(
                when {
                    body.isNotBlank() -> body
                    hasAudio -> "Please listen to this audio clip and respond."
                    else -> defaultPromptForAttachments(prepared)
                },
            )
            prepared.mapNotNull { pa ->
                pa.extractedText?.takeIf { it.isNotBlank() }?.let { text ->
                    "\n\n[Attached file \"${pa.attachment.name}\":]\n$text"
                }
            }.forEach { append(it) }
            prepared
                .filter { it.attachment.kind == AttachmentType.FILE && it.extractedText == null }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { "\"${it.attachment.name}\"" }
                ?.let { append("\n\n[Note: attached file(s) $it could not be read as text.]") }
        }

    fun imagePathsOf(attachments: List<com.raikaai.app.data.ChatAttachment>): List<String> =
        attachments.filter { it.kind == AttachmentType.IMAGE }.map { it.path }
}
