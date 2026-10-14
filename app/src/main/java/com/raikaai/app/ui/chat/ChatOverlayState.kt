package com.raikaai.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * All overlay/dialog state for the chat screen, hoisted out of the giant
 * ChatScreen composable so the top bar, composer, and dialogs host can share
 * it without prop-drilling a dozen booleans. Plain holder (not a ViewModel):
 * lifetime is the composition; chat data itself stays in ChatViewModel.
 */
class ChatOverlayState {
    var showRagSheet by mutableStateOf(false)
    var showCompareDialog by mutableStateOf(false)
    var showModelDropdown by mutableStateOf(false)
    var showAttachmentMenu by mutableStateOf(false)
    var showUrlDialog by mutableStateOf(false)
    var urlInput by mutableStateOf("")
    var showImageGenDialog by mutableStateOf(false)
    var imageGenPrompt by mutableStateOf("")
    var imageGenSize by mutableStateOf("1024x1024")
    var previewData by mutableStateOf<Triple<String, String, Map<String, String>>?>(null)
    var previewImagePath by mutableStateOf<String?>(null)
}
