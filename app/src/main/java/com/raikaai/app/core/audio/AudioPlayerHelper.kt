package com.raikaai.app.core.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.raikaai.app.util.KLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Lightweight helper to manage audio playback of voice prompts and audio notes.
 */
class AudioPlayerHelper(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    private val _currentlyPlayingPath = MutableStateFlow<String?>(null)
    val currentlyPlayingPath: StateFlow<String?> = _currentlyPlayingPath

    fun play(filePath: String, onCompletion: (() -> Unit)? = null) {
        if (_currentlyPlayingPath.value == filePath) {
            stop()
            return
        }

        stop()

        val file = File(filePath)
        if (!file.exists()) {
            KLog.w("AudioPlayer", "File does not exist: $filePath")
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                setOnCompletionListener {
                    _currentlyPlayingPath.value = null
                    onCompletion?.invoke()
                }
                start()
            }
            _currentlyPlayingPath.value = filePath
        } catch (e: Exception) {
            KLog.e("AudioPlayer", "Error playing audio file $filePath", e)
            _currentlyPlayingPath.value = null
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _currentlyPlayingPath.value = null
    }

    fun isPlaying(filePath: String): Boolean {
        return _currentlyPlayingPath.value == filePath
    }
}
