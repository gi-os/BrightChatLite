package com.gios.brightchatlite.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.gios.brightchatlite.data.ChatRepository
import com.gios.brightchatlite.model.Attachment
import com.gios.brightchatlite.model.ChatMessage
import com.gios.brightchatlite.model.Contacts
import com.gios.brightchatlite.model.Conversation
import com.gios.brightchatlite.model.ThreadRow
import com.gios.brightchatlite.model.TimeFormat
import com.gios.brightchatlite.model.buildThreadRows
import com.gios.brightchatlite.model.foldReactions
import com.gios.brightchatlite.model.mergeIntoThread
import com.gios.brightchatlite.model.mergePage
import com.gios.brightchatlite.model.replaceKeepingPending
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadViewModel(
    private val repo: ChatRepository,
    private val connectivity: LightConnectivity,
    val conversation: Conversation,
) : WheelViewModel<Unit>() {

    /** Raw messages as fetched and sent, tapbacks included; folded for display. */
    private val _raw = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _raw
        .map { foldReactions(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val contacts: StateFlow<Contacts> = repo.contacts

    val favorite: StateFlow<Boolean> = repo.favorites
        .map { favs -> conversation.guids.any { it in favs } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _modal = MutableStateFlow<String?>(null)
    val modal: StateFlow<String?> = _modal.asStateFlow()

    private var limit = PAGE
    private var poller: Job? = null
    private var newestIncoming = 0L
    private var markedOnce = false

    init {
        viewModelScope.launch {
            val cached = repo.cachedMessages(conversation.guids, limit)
            if (_raw.value.isEmpty()) _raw.value = cached
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        poller?.cancel()
        poller = viewModelScope.launch {
            var first = true
            while (isActive) {
                val online = runCatching { connectivity.currentStatus.isConnected }.getOrDefault(true)
                if (online) fetch(replace = first)
                _status.value = if (online) _status.value else "Offline"
                first = false
                delay(THREAD_POLL_MS)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        poller?.cancel()
    }

    override fun onAppPause() {
        poller?.cancel()
    }

    private suspend fun fetch(replace: Boolean) {
        runCatching { repo.fetchMessages(conversation.guids, if (replace) limit else POLL_SIZE) }
            .onSuccess { page ->
                _status.value = null
                _raw.value = if (replace) replaceKeepingPending(_raw.value, page) else mergePage(_raw.value, page)
                // Reading the thread here is reading it: mark read when something new came in.
                val incoming = page.filter { !it.fromMe }.maxOfOrNull { it.date } ?: 0L
                if (incoming > newestIncoming || !markedOnce) {
                    markedOnce = true
                    newestIncoming = maxOf(newestIncoming, incoming)
                    repo.markRead(conversation)
                }
            }
            .onFailure { _status.value = ChatRepository.describe(it) }
    }

    /** Fetches a bigger page so older messages show above. */
    fun loadEarlier() {
        if (limit >= MAX_LIMIT) return
        limit = (limit + PAGE).coerceAtMost(MAX_LIMIT)
        viewModelScope.launch { fetch(replace = true) }
    }

    val canLoadEarlier: Boolean get() = limit < MAX_LIMIT

    /** Shows the message straight away, then swaps in the server's copy (or marks it failed). */
    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val temp = repo.newTempGuid()
        val optimistic = ChatMessage(
            guid = temp,
            text = body,
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            tempGuid = temp,
            chatGuid = conversation.guid,
        )
        _raw.value = _raw.value + optimistic
        viewModelScope.launch {
            runCatching { repo.send(conversation, body, temp) }
                .onSuccess { sent ->
                    val echoed = if (sent.tempGuid == null) sent.copy(tempGuid = temp) else sent
                    _raw.value = mergeIntoThread(_raw.value, echoed)
                }
                .onFailure { e ->
                    _raw.value = _raw.value.map { if (it.guid == temp) it.copy(error = -1) else it }
                    _modal.value = "Not sent. ${ChatRepository.describe(e)}."
                }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch { repo.toggleFavorite(conversation.guid) }
    }

    fun dismissModal() {
        _modal.value = null
    }

    /** Decoded, downsampled inline image, cached for the process. */
    suspend fun image(attachment: Attachment, maxWidthPx: Int): ImageBitmap? {
        imageCache.get(attachment.guid)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = repo.attachmentBytes(attachment.guid)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                bitmap?.asImageBitmap()?.also { imageCache.put(attachment.guid, it) }
            }.getOrNull()
        }
    }

    companion object {
        private const val PAGE = 60
        private const val MAX_LIMIT = 600
        private const val POLL_SIZE = 25
        private const val THREAD_POLL_MS = 5_000L
        private val imageCache = LruCache<String, ImageBitmap>(24)
    }
}

/** One conversation: messages oldest to newest, newest at the bottom, with a reply button. */
class ThreadScreen(
    sealedActivity: SealedLightActivity,
    private val conversation: Conversation,
) : LightScreen<Unit, ThreadViewModel>(sealedActivity) {

    private val repo = ChatRepository.get(lightContext)
    private val timeFormat = TimeFormat()

    override val viewModelClass: Class<ThreadViewModel> get() = ThreadViewModel::class.java

    override fun createViewModel() = ThreadViewModel(repo, lightContext.connectivity, conversation)

    private fun reply() {
        navigateTo({ TextEditorScreen(it, "Message", "", "SEND") }) { text -> viewModel.send(text) }
    }

    @Composable
    override fun Content() {
        val messages by viewModel.messages.collectAsState()
        val contacts by viewModel.contacts.collectAsState()
        val favorite by viewModel.favorite.collectAsState()
        val status by viewModel.status.collectAsState()
        val modal by viewModel.modal.collectAsState()
        val rows = remember(messages) {
            buildThreadRows(messages, conversation.isGroup, timeFormat::dayStart, timeFormat::clock)
        }
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { viewModel.wheel.collect { scroll.animateScrollBy(it * ConversationListScreen.WHEEL_STEP_PX) } }

        // Stick to the bottom: on open, and when a new message lands while already there.
        var stuck by remember { mutableStateOf(true) }
        val lastKey = rows.lastOrNull()?.key
        LaunchedEffect(lastKey) {
            if (stuck || rows.lastOrNull().let { it is ThreadRow.Turn && it.message.fromMe }) {
                withFrameNanos { }
                withFrameNanos { }
                scroll.scrollTo(scroll.maxValue)
            }
        }
        LaunchedEffect(scroll.value, scroll.maxValue) { stuck = scroll.value >= scroll.maxValue - 40 }

        ChatSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text(contacts.title(conversation)),
                    rightButton = LightBarButton.LightIcon(
                        if (favorite) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        onClick = viewModel::toggleFavorite,
                    ),
                )
                status?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (rows.isEmpty()) {
                    Column(modifier = Modifier.weight(1f)) { CenterMessage(if (status == null) "Loading…" else "No messages saved") }
                } else {
                    LightScrollView(
                        scrollState = scroll,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        if (viewModel.canLoadEarlier && messages.size >= 20) {
                            LightText(
                                text = "Earlier messages",
                                variant = LightTextVariant.Fine,
                                underline = true,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable { viewModel.loadEarlier() }
                                    .padding(vertical = 0.5f.gridUnitsAsDp()),
                            )
                        }
                        rows.forEach { row ->
                            key(row.key) {
                                when (row) {
                                    is ThreadRow.DayHeader -> DayHeader(timeFormat.dayHeader(row.dayStart))
                                    is ThreadRow.Event -> EventLine(row.message, contacts)
                                    is ThreadRow.Turn -> Turn(row, contacts)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
                    }
                }
                LightBottomBar(listOf(LightBarButton.Text("REPLY", onClick = ::reply)))
            }
            modal?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissModal) }
        }
    }

    @Composable
    private fun DayHeader(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Fine,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun EventLine(message: ChatMessage, contacts: Contacts) {
        val actor = if (message.fromMe) "You" else message.sender?.let(contacts::sender) ?: "Someone"
        val text = message.groupEventText(actor) ?: return
        LightText(
            text = text,
            variant = LightTextVariant.Fine,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 0.5f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun Turn(row: ThreadRow.Turn, contacts: Contacts) {
        val m = row.message
        val align = if (m.fromMe) TextAlign.End else TextAlign.Start
        val horizontal = if (m.fromMe) Alignment.End else Alignment.Start
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (row.showLabel) 0.8f.gridUnitsAsDp() else 0.3f.gridUnitsAsDp()),
            horizontalAlignment = horizontal,
        ) {
            if (row.showLabel) {
                val label = when {
                    m.fromMe -> "You"
                    m.sender != null -> contacts.sender(m.sender)
                    conversation.participants.size == 1 -> contacts.sender(conversation.participants[0])
                    else -> null
                }
                label?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true, align = align) }
            }
            m.attachments.forEach { a ->
                if (a.isImage) InlineImage(a) else AttachmentLabel(a.fileLabel, align)
            }
            m.bodyText?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Paragraph,
                    align = align,
                    modifier = Modifier.fillMaxWidth(0.85f),
                )
            }
            if (m.reactions.isNotEmpty()) {
                val marks = m.reactions.joinToString("  ") { r ->
                    val who = if (r.fromMe) "You" else r.sender?.let(contacts::sender) ?: ""
                    "${r.mark} $who".trim()
                }
                LightText(text = marks, variant = LightTextVariant.Fine, lighten = true, align = align)
            }
            row.receipt?.let {
                LightText(text = it, variant = LightTextVariant.Fine, lighten = it != "Not delivered", align = align)
            }
        }
    }

    @Composable
    private fun AttachmentLabel(text: String, align: TextAlign) {
        LightText(text = "[$text]", variant = LightTextVariant.Detail, lighten = true, align = align)
    }

    @Composable
    private fun InlineImage(attachment: Attachment) {
        var bitmap by remember(attachment.guid) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(attachment.guid) { mutableStateOf(false) }
        LaunchedEffect(attachment.guid) {
            if (attachment.totalBytes > ChatRepository.MAX_IMAGE_BYTES) {
                failed = true
                return@LaunchedEffect
            }
            val img = viewModel.image(attachment, IMAGE_MAX_WIDTH_PX)
            if (img == null) failed = true else bitmap = img
        }
        val img = bitmap
        when {
            img != null -> Image(
                bitmap = img,
                contentDescription = attachment.transferName ?: "Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .padding(vertical = 0.3f.gridUnitsAsDp()),
            )
            failed -> AttachmentLabel(attachment.fileLabel, TextAlign.Start)
            else -> AttachmentLabel("Loading photo…", TextAlign.Start)
        }
    }

    companion object {
        private const val IMAGE_MAX_WIDTH_PX = 720
    }
}
