package com.gios.brightchatlite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.gios.brightchatlite.api.SetupQr
import com.gios.brightchatlite.data.ChatRepository
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val url: String = "",
    val password: String = "",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

class SetupViewModel(private val repo: ChatRepository) : WheelViewModel<Boolean>() {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val cfg = repo.config()
            if (cfg != null) {
                val api = if (repo.privateApi()) "Private API on: read receipts are sent" else "Private API off: reading here won't clear unread on the Mac"
                _state.update { it.copy(url = cfg.url, password = cfg.password, connected = true, status = "Connected. $api") }
            }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value.trim(), error = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    /** Fills both fields from a scanned code; false when the code is not a server setup. */
    fun applyQr(raw: String): Boolean {
        val setup = SetupQr.parse(raw) ?: run {
            _state.update { it.copy(error = "That QR code isn't a BlueBubbles server setup.") }
            return false
        }
        _state.update { it.copy(url = setup.url, password = setup.password, error = null) }
        return true
    }

    fun connect(onDone: () -> Unit) {
        val s = _state.value
        val url = SetupQr.normalizeUrl(s.url)
        if (url == null) {
            _state.update { it.copy(error = "Enter the server address, for example http://100.64.0.2:1234") }
            return
        }
        if (s.password.isBlank()) {
            _state.update { it.copy(error = "Enter the server password.") }
            return
        }
        _state.update { it.copy(busy = true, url = url, status = "Connecting…", error = null) }
        viewModelScope.launch {
            val result = repo.connect(url, s.password)
            result.onSuccess {
                _state.update { it.copy(busy = false, connected = true, status = "Connected") }
                repo.refreshConversations()
                onDone()
            }.onFailure { e ->
                _state.update { it.copy(busy = false, status = null, error = ChatRepository.describe(e)) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repo.disconnect()
            _state.value = SetupState(status = "Disconnected")
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

/**
 * Server setup, and settings once set up: the BlueBubbles address and password, typed or
 * read off a QR code, checked against `server/info` before they are saved.
 */
class SetupScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, SetupViewModel>(sealedActivity) {

    private val repo = ChatRepository.get(lightContext)

    override val viewModelClass: Class<SetupViewModel> get() = SetupViewModel::class.java

    override fun createViewModel() = SetupViewModel(repo)

    private fun <T> open(factory: (SealedLightActivity) -> SimpleLightScreen<T>, onResult: (T) -> Unit) =
        navigateTo(factory, onResult)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        ChatSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text("Server"),
                )
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "Chat Lite talks to your own BlueBubbles Server on a Mac signed into iMessage. " +
                            "Reach it over Tailscale or your home Wi-Fi.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                    )
                    LightTextField(
                        label = "Server address",
                        value = state.url,
                        placeholder = "http://100.64.0.2:1234",
                        onClick = {
                            open({ TextEditorScreen(it, "Server address", state.url, "DONE", singleLine = true, initialCaps = false) }) {
                                viewModel.setUrl(it)
                            }
                        },
                    )
                    LightTextField(
                        label = "Password",
                        value = "•".repeat(state.password.length.coerceAtMost(16)),
                        placeholder = "server password",
                        onClick = {
                            open({ TextEditorScreen(it, "Server password", state.password, "DONE", singleLine = true, initialCaps = false) }) {
                                viewModel.setPassword(it)
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    state.status?.let {
                        LightText(text = it, variant = LightTextVariant.Detail, lighten = true)
                    }
                    if (state.connected && !state.busy) {
                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                        LightText(
                            text = "Disconnect and clear cache",
                            variant = LightTextVariant.Detail,
                            underline = true,
                            modifier = Modifier.lightClickable { viewModel.disconnect() },
                        )
                    }
                    Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.Text("SCAN QR", onClick = {
                            open({ QrScanScreen(it) }) { raw -> viewModel.applyQr(raw) }
                        }),
                        LightBarButton.Text(if (state.busy) "…" else "CONNECT", onClick = {
                            if (!state.busy) viewModel.connect { goBack(true) }
                        }),
                    ),
                )
            }
            state.error?.let {
                Box(modifier = Modifier.fillMaxSize()) {
                    LightFullscreenModal(message = it, onClose = viewModel::dismissError)
                }
            }
        }
    }
}
