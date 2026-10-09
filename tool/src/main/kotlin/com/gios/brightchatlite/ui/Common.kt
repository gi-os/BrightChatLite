package com.gios.brightchatlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/** The system theme (dark or light, as LightOS has it) over a filled background. */
@Composable
fun ChatSurface(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

/**
 * Full-screen text entry on the LP3 keyboard. Returns the submitted text through
 * [goBack]; backing out returns nothing.
 */
class TextEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String = "",
    private val submitLabel: String = "DONE",
    private val singleLine: Boolean = false,
    private val initialCaps: Boolean = true,
) : SimpleLightScreen<String>(sealedActivity) {

    private var done = false

    private fun finish(value: String?) {
        if (done) return
        done = true
        goBack(value)
    }

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        ChatSurface {
            LightTextInputEditor(
                title = title,
                state = state,
                onSubmit = { finish(it.toString()) },
                onBack = { finish(null) },
                keyboardOptionsFlow = keyboardOptions,
                submitLabel = submitLabel,
                singleLine = singleLine,
                initialCaps = initialCaps,
            )
        }
    }
}

/** Scans one QR code with the SDK's scanner and returns its raw text. */
class QrScanScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    // The scanner can report the same code on consecutive frames; only the first may pop
    // the screen, or a second goBack would pop the screen underneath too.
    private var done = false

    private fun finish(value: String?) {
        if (done) return
        done = true
        goBack(value)
    }

    @Composable
    override fun Content() {
        ChatSurface {
            LightQrCodeScanner(
                title = "Scan server QR",
                onScanned = { finish(it) },
                onBack = { finish(null) },
            )
        }
    }
}
