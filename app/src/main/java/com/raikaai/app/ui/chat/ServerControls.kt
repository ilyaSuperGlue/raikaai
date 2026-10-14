package com.raikaai.app.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.raikaai.app.core.server.ChatServerService
import com.raikaai.app.core.server.OpenAiServer
import com.raikaai.app.data.Settings
import com.raikaai.app.data.SettingsRepository
import com.raikaai.app.data.parseRemoteModelPricing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Embedded-server controls and remote-spend tracking, previously embedded in
 * ChatViewModel. The ViewModel keeps thin delegating accessors so UI call
 * sites are untouched.
 */
class ServerControls(
    private val app: Application,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope,
    private val settings: StateFlow<Settings>,
) {
    val serverStatus: StateFlow<OpenAiServer.Status> = OpenAiServer.status

    private val _remoteSpendUsd = mutableStateOf(0.0)
    val remoteSpendUsd: State<Double> = _remoteSpendUsd

    fun refreshSpend(micros: Long) {
        _remoteSpendUsd.value = micros / 1_000_000.0
    }

    fun toggleServer(context: Context = app) {
        val current = serverStatus.value
        if (current is OpenAiServer.Status.Running || current is OpenAiServer.Status.Starting) {
            ChatServerService.stop(context)
        } else {
            ChatServerService.start(context)
        }
    }

    fun setServerPort(v: Int) {
        scope.launch { settingsRepo.setServerPort(v) }
    }

    fun setServerBindAll(v: Boolean) {
        scope.launch { settingsRepo.setServerBindAll(v) }
    }

    fun setServerAuthToken(v: String) {
        scope.launch { settingsRepo.setServerAuthToken(v) }
    }

    /** Records the estimated USD cost of one remote generation. */
    fun recordRemoteCost(modelId: String, promptTokens: Int, completionTokens: Int) {
        if (modelId.isBlank() || (promptTokens <= 0 && completionTokens <= 0)) return
        val pricing = parseRemoteModelPricing(settings.value.remoteModelPricingJson)[modelId] ?: return
        val usd = promptTokens * pricing.promptPerToken + completionTokens * pricing.completionPerToken
        if (usd <= 0) return
        scope.launch {
            settingsRepo.addRemoteSpendMicros((usd * 1_000_000).toLong().coerceAtLeast(1L))
        }
    }

    fun resetRemoteSpend() {
        scope.launch { settingsRepo.resetRemoteSpend() }
    }
}
