package com.gios.brightchatlite.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.gios.brightchatlite.data.ChatRepository
import com.gios.brightchatlite.data.REFRESH_JOB_KEY
import com.gios.brightchatlite.model.Contacts
import com.gios.brightchatlite.model.Conversation
import com.gios.brightchatlite.model.Handles
import com.gios.brightchatlite.model.TimeFormat
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
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
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which list is showing: favorites and known chats together, or unknown senders. */
enum class ListTab { KNOWN, UNKNOWN }

class ConversationListViewModel(
    private val repo: ChatRepository,
    private val connectivity: LightConnectivity,
    private val lightContext: SealedLightContext,
) : WheelViewModel<Unit>() {

    val conversations: StateFlow<List<Conversation>> = repo.conversations
    val contacts: StateFlow<Contacts> = repo.contacts
    val favorites: StateFlow<Set<String>> = repo.favorites
    val lastError: StateFlow<String?> = repo.lastError

    /** Null until the stored settings have been read. */
    private val _configured = MutableStateFlow<Boolean?>(null)
    val configured: StateFlow<Boolean?> = _configured.asStateFlow()

    private val _tab = MutableStateFlow(ListTab.KNOWN)
    val tab: StateFlow<ListTab> = _tab.asStateFlow()

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _modal = MutableStateFlow<String?>(null)
    val modal: StateFlow<String?> = _modal.asStateFlow()

    private var poller: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        poller?.cancel()
        poller = viewModelScope.launch {
            repo.loadCache()
            val isConfigured = repo.config() != null
            _configured.value = isConfigured
            if (!isConfigured) return@launch
            LightWork.enqueuePeriodic(lightContext, REFRESH_JOB_KEY, repeatInterval = 15.minutes)
            repo.refreshServerInfo()
            // Live updates without a socket: poll while this screen is the one showing,
            // and only when there is a network to poll over.
            while (isActive) {
                val online = runCatching { connectivity.currentStatus.isConnected }.getOrDefault(true)
                _offline.value = !online
                if (online) repo.refreshConversations()
                delay(LIST_POLL_MS)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        poller?.cancel()
    }

    override fun onAppPause() {
        poller?.cancel()
    }

    fun toggleTab() {
        _tab.value = if (_tab.value == ListTab.KNOWN) ListTab.UNKNOWN else ListTab.KNOWN
    }

    fun showModal(message: String) {
        _modal.value = message
    }

    fun dismissModal() {
        _modal.value = null
    }

    fun newChat(address: String, text: String, onOpened: (Conversation) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.newChat(address, text) }
                .onSuccess(onOpened)
                .onFailure { showModal("Couldn't start the chat. ${ChatRepository.describe(it)}.") }
        }
    }

    companion object {
        private const val LIST_POLL_MS = 10_000L
    }
}

/** The list split the way BrightChat splits it, with favorites pinned to the top of Known. */
data class ListSections(val favorites: List<Conversation>, val known: List<Conversation>, val unknown: List<Conversation>)

fun splitConversations(all: List<Conversation>, contacts: Contacts, favorites: Set<String>): ListSections {
    val fav = ArrayList<Conversation>()
    val known = ArrayList<Conversation>()
    val unknown = ArrayList<Conversation>()
    for (c in all) {
        when {
            c.guids.any { it in favorites } -> fav.add(c)
            // With no address book from the server every chat would be "unknown"; show them all.
            contacts.isEmpty || contacts.knows(c) -> known.add(c)
            else -> unknown.add(c)
        }
    }
    return ListSections(fav, known, unknown)
}

@InitialScreen
class ConversationListScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ConversationListViewModel>(sealedActivity) {

    private val repo = ChatRepository.get(lightContext)
    private val timeFormat = TimeFormat()

    override val viewModelClass: Class<ConversationListViewModel> get() = ConversationListViewModel::class.java

    override fun createViewModel() = ConversationListViewModel(repo, lightContext.connectivity, lightContext)

    private fun openSetup() {
        navigateTo({ SetupScreen(it) })
    }

    private fun openThread(c: Conversation) {
        navigateTo({ ThreadScreen(it, c) })
    }

    private fun startNewMessage() {
        navigateTo({ TextEditorScreen(it, "To: phone or email", "", "NEXT", singleLine = true, initialCaps = false) }) { to ->
            val address = Handles.normalizeAddress(to)
            if (address == null) {
                viewModel.showModal("That isn't a phone number or an email address.")
                return@navigateTo
            }
            navigateTo({ TextEditorScreen(it, "To ${Handles.pretty(address)}", "", "SEND") }) { text ->
                if (text.isNotBlank()) viewModel.newChat(address, text.trim()) { openThread(it) }
            }
        }
    }

    @Composable
    override fun Content() {
        val configured by viewModel.configured.collectAsState()
        val all by viewModel.conversations.collectAsState()
        val contacts by viewModel.contacts.collectAsState()
        val favorites by viewModel.favorites.collectAsState()
        val tab by viewModel.tab.collectAsState()
        val offline by viewModel.offline.collectAsState()
        val lastError by viewModel.lastError.collectAsState()
        val modal by viewModel.modal.collectAsState()
        val sections = remember(all, contacts, favorites) { splitConversations(all, contacts, favorites) }
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { viewModel.wheel.collect { scroll.animateScrollBy(it * WHEEL_STEP_PX) } }

        ChatSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = if (configured == true) LightBarButton.LightIcon(LightIcons.COMPOSE_MESSAGE, onClick = ::startNewMessage) else null,
                    center = LightTopBarCenter.Text(if (tab == ListTab.KNOWN) "Chat" else "Unknown senders"),
                    rightButton = LightBarButton.LightIcon(LightIcons.SETTINGS, onClick = ::openSetup),
                )
                val status = when {
                    configured != true -> null
                    offline -> "Offline. Showing saved chats."
                    lastError != null -> "$lastError. Showing saved chats."
                    else -> null
                }
                if (status != null) {
                    LightText(
                        text = status,
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        configured == false -> CenterMessage("Connect your BlueBubbles server to see your iMessages.")
                        configured == null -> Unit
                        all.isEmpty() -> CenterMessage(if (lastError == null) "Loading chats…" else "No chats yet")
                        else -> {
                            val rows = if (tab == ListTab.KNOWN) sections.favorites + sections.known else sections.unknown
                            if (rows.isEmpty()) {
                                CenterMessage(if (tab == ListTab.KNOWN) "No chats from people you know" else "No unknown senders")
                            } else {
                                LightScrollView(
                                    scrollState = scroll,
                                    modifier = Modifier.fillMaxSize().padding(start = 1f.gridUnitsAsDp()),
                                ) {
                                    val favCount = if (tab == ListTab.KNOWN) sections.favorites.size else 0
                                    rows.forEachIndexed { i, c ->
                                        ConversationRow(
                                            conversation = c,
                                            title = contacts.title(c),
                                            favorite = i < favCount,
                                            time = timeFormat.listTime(c.lastDate),
                                            sender = if (c.isGroup && !c.lastFromMe) c.lastSender?.let(contacts::sender) else null,
                                            onClick = { openThread(c) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (configured == false) {
                    LightBottomBar(listOf(LightBarButton.Text("SET UP", onClick = ::openSetup)))
                } else if (configured == true) {
                    val unknownUnread = sections.unknown.count { it.unread }
                    val label = if (tab == ListTab.KNOWN) {
                        "UNKNOWN" + if (unknownUnread > 0) " •" else ""
                    } else {
                        "KNOWN"
                    }
                    LightBottomBar(listOf(LightBarButton.Text(label, onClick = viewModel::toggleTab)))
                }
            }
            modal?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissModal) }
        }
    }

    companion object {
        const val WHEEL_STEP_PX = 180f
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 2f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
        LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    title: String,
    favorite: Boolean,
    time: String,
    sender: String?,
    onClick: () -> Unit,
) {
    val preview = buildString {
        if (conversation.lastFromMe) append("You: ") else if (sender != null) append(sender).append(": ")
        append(conversation.lastText.replace('\n', ' '))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.6f.gridUnitsAsDp())
            .padding(end = 1f.gridUnitsAsDp()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            LightText(
                text = (if (conversation.unread) "• " else "") + (if (favorite) "★ " else "") + title,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
            LightText(text = time, variant = LightTextVariant.Fine, lighten = true, maxLines = 1)
        }
        Spacer(modifier = Modifier.height(0.2f.gridUnitsAsDp()))
        LightText(
            text = preview,
            variant = LightTextVariant.Detail,
            lighten = !conversation.unread,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
