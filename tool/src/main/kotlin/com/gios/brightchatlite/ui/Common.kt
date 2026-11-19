package com.gios.brightchatlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

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

/** A full-screen yes/no question: [title] in the middle, then the two choices as tappable lines. */
@Composable
fun ConfirmView(
    title: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(text = title, variant = LightTextVariant.Copy, align = TextAlign.Center)
        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
        ActionLink(confirmLabel, onConfirm)
        ActionLink(cancelLabel, onCancel)
    }
}

/** A tappable, underlined line of Copy text, centered. */
@Composable
fun ActionLink(text: String, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        underline = true,
        align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().lightClickable { onClick() }.padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

/**
 * Full-screen text entry on the LP3 keyboard. Returns the submitted text through
 * [goBack]; backing out returns nothing. With [discardTitle] set, the on-screen back arrow
 * asks first when the text was changed.
 *
 * The system back pops the screen without asking it, and tools may not intercept it. So with
 * [discardTitle] set, text left that way is kept as a draft and comes back the next time the
 * same editor opens.
 */
class TextEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String = "",
    private val submitLabel: String = "DONE",
    private val singleLine: Boolean = false,
    private val initialCaps: Boolean = true,
    private val discardTitle: String? = null,
    /** Which conversation or recipient a draft belongs to; defaults to the title and starting text. */
    private val draftId: String? = null,
) : SimpleLightScreen<String>(sealedActivity) {

    private var done = false
    private val draftKey = draftId ?: "$title\u0000$initial"

    // Kept here, not remembered, so the text survives the question page and can be saved as a draft.
    private val state = TextFieldState(if (discardTitle != null) drafts[draftKey] ?: initial else initial)

    private fun finish(value: String?) {
        if (done) return
        done = true
        goBack(value)
    }

    override fun onScreenDestroy() {
        if (discardTitle == null) return
        val text = state.text.toString()
        if (!done && text != initial) drafts[draftKey] = text else drafts.remove(draftKey)
    }

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        var confirming by remember { mutableStateOf(false) }
        val changed = discardTitle != null && state.text.toString() != initial
        val back: () -> Unit = {
            if (changed) {
                confirming = true
            } else {
                finish(null)
            }
        }
        ChatSurface {
            if (confirming && discardTitle != null) {
                ConfirmView(
                    title = discardTitle,
                    confirmLabel = "Discard",
                    cancelLabel = "Keep editing",
                    onConfirm = { finish(null) },
                    onCancel = { confirming = false },
                )
            } else {
                LightTextInputEditor(
                    title = title,
                    state = state,
                    onSubmit = { finish(it.toString()) },
                    onBack = { back() },
                    keyboardOptionsFlow = keyboardOptions,
                    submitLabel = submitLabel,
                    singleLine = singleLine,
                    initialCaps = initialCaps,
                )
            }
        }
    }

    private companion object {
        /** Unsent text left by the system back, by editor. Lives as long as the tool's process. */
        val drafts = mutableMapOf<String, String>()
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
