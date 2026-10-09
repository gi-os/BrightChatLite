package com.gios.brightchatlite.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.gios.brightchatlite.api.ApiException
import com.gios.brightchatlite.api.BlueBubblesClient
import com.gios.brightchatlite.api.BlueBubblesJson
import com.gios.brightchatlite.api.ServerInfo
import com.gios.brightchatlite.model.ChatMessage
import com.gios.brightchatlite.model.Contacts
import com.gios.brightchatlite.model.Conversation
import com.gios.brightchatlite.model.PENDING_PREFIX
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class ServerConfig(val url: String, val password: String)

/**
 * The one place the tool's data lives: server settings and favorites in the SDK's
 * DataStore, conversations and messages cached in Room, attachments cached as files, and
 * the server reached through [BlueBubblesClient]. Shared by the screens and the background
 * [refreshCacheJob], so it is a process-wide singleton built from a [SealedLightContext].
 */
class ChatRepository private constructor(ctx: SealedLightContext) {

    // Only process-lived handles are kept (the database is built on the application
    // context, DataStore is a process singleton), never the screen's context itself.
    private val dao = ctx.buildDatabase(ChatDatabase::class.java, "brightchatlite.db").dao()
    private val dataStore = ctx.dataStore
    private val filesDir: File = ctx.filesDir
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _contacts = MutableStateFlow(Contacts())
    val contacts: StateFlow<Contacts> = _contacts.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /** The last refresh's failure, for the list's status line; null when it worked. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var loaded = false
    private var client: BlueBubblesClient? = null
    private var clientKey: ServerConfig? = null

    // ---- settings ----

    suspend fun config(): ServerConfig? {
        val prefs = dataStore.data.first()
        val url = prefs[KEY_URL]?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs[KEY_PASSWORD]?.let { PasswordCipher.decrypt(it) } ?: return null
        return ServerConfig(url, password)
    }

    suspend fun privateApi(): Boolean = dataStore.data.first()[KEY_PRIVATE_API] == true

    suspend fun lastSyncAt(): Long = dataStore.data.first()[KEY_LAST_SYNC] ?: 0L

    private suspend fun clientOrNull(): BlueBubblesClient? {
        val cfg = config() ?: return null
        if (cfg != clientKey) {
            client = BlueBubblesClient(cfg.url, cfg.password)
            clientKey = cfg
        }
        return client
    }

    private suspend fun requireClient(): BlueBubblesClient =
        clientOrNull() ?: throw IllegalStateException("No server configured")

    /** Checks the address and password against `server/info`, and saves them only if they work. */
    suspend fun connect(url: String, password: String): Result<ServerInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val info = BlueBubblesClient(url, password).serverInfo()
            val encrypted = PasswordCipher.encrypt(password)
            dataStore.edit {
                it[KEY_URL] = url
                it[KEY_PASSWORD] = encrypted
                it[KEY_PRIVATE_API] = info.privateApiReady
            }
            info
        }
    }

    /** Forgets the server and everything cached from it. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        dataStore.edit {
            it.remove(KEY_URL)
            it.remove(KEY_PASSWORD)
            it.remove(KEY_PRIVATE_API)
            it.remove(KEY_LAST_SYNC)
            it.remove(KEY_CONTACTS_AT)
        }
        dao.clearConversations()
        dao.clearMessages()
        File(filesDir, CONTACTS_FILE).delete()
        attachmentDir().deleteRecursively()
        _conversations.value = emptyList()
        _contacts.value = Contacts()
        client = null
        clientKey = null
    }

    // ---- favorites (local: BlueBubbles has no favorites concept) ----

    suspend fun toggleFavorite(guid: String) {
        val updated = dataStore.edit {
            val now = it[KEY_FAVORITES] ?: emptySet()
            it[KEY_FAVORITES] = if (guid in now) now - guid else now + guid
        }
        _favorites.value = updated[KEY_FAVORITES] ?: emptySet()
    }

    // ---- cache ----

    /** Loads the cached list, favorites and contacts once per process. */
    suspend fun loadCache() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loaded = true
        _favorites.value = dataStore.data.first()[KEY_FAVORITES] ?: emptySet()
        _conversations.value = dao.conversations().mapNotNull {
            runCatching { json.decodeFromString(Conversation.serializer(), it.payload) }.getOrNull()
        }
        val file = File(filesDir, CONTACTS_FILE)
        if (file.exists()) {
            runCatching {
                _contacts.value = Contacts(json.decodeFromString(CONTACT_MAP, file.readText()))
            }
        }
    }

    suspend fun cachedMessages(guids: List<String>, limit: Int): List<ChatMessage> = withContext(Dispatchers.IO) {
        dao.messages(guids, limit).mapNotNull {
            runCatching { json.decodeFromString(ChatMessage.serializer(), it.payload) }.getOrNull()
        }
    }

    private suspend fun storeMessages(messages: List<ChatMessage>) {
        val rows = messages.filter { it.chatGuid != null && !it.guid.startsWith(PENDING_PREFIX) && it.guid.isNotBlank() }
            .map { MessageEntity(it.guid, it.chatGuid!!, it.date, json.encodeToString(ChatMessage.serializer(), it.copy(reactions = emptyList()))) }
        if (rows.isNotEmpty()) dao.upsertMessages(rows)
    }

    // ---- network ----

    /**
     * Rebuilds the conversation list from one message sweep, caches the swept messages so
     * threads open instantly, and refreshes the contact index once a day.
     */
    suspend fun refreshConversations(): Result<List<Conversation>> = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val result = runCatching {
                val api = requireClient()
                val sweep = api.messageSweep(SWEEP_SIZE)
                val list = BlueBubblesJson.conversationsFromSweep(sweep)
                dao.replaceConversations(list.map {
                    ConversationEntity(it.guid, it.lastDate, json.encodeToString(Conversation.serializer(), it))
                })
                storeMessages(sweep.map { BlueBubblesJson.parseMessage(it) })
                dao.trimMessages(MESSAGE_CACHE_SIZE)
                _conversations.value = list
                dataStore.edit { it[KEY_LAST_SYNC] = System.currentTimeMillis() }
                refreshContactsIfStale(api)
                list
            }
            _lastError.value = result.exceptionOrNull()?.let(::describe)
            result
        }
    }

    private suspend fun refreshContactsIfStale(api: BlueBubblesClient) {
        val at = dataStore.data.first()[KEY_CONTACTS_AT] ?: 0L
        if (!_contacts.value.isEmpty && System.currentTimeMillis() - at < CONTACTS_TTL_MS) return
        runCatching {
            val built = Contacts.from(api.contacts())
            File(filesDir, CONTACTS_FILE).writeText(json.encodeToString(CONTACT_MAP, built.asMap()))
            _contacts.value = built
            dataStore.edit { it[KEY_CONTACTS_AT] = System.currentTimeMillis() }
        }
    }

    /** The newest [limit] messages across a conversation's rooms, fetched and cached. */
    suspend fun fetchMessages(guids: List<String>, limit: Int): List<ChatMessage> = withContext(Dispatchers.IO) {
        val api = requireClient()
        val out = guids.flatMap { api.messages(it, limit) }
        storeMessages(out)
        out
    }

    fun newTempGuid(): String = PENDING_PREFIX + UUID.randomUUID().toString()

    /**
     * Sends [text] into [conversation]. With AppleScript a forked group's dead rooms can fail
     * to resolve, so every room is tried in order until one takes it, as BrightChat does.
     */
    suspend fun send(conversation: Conversation, text: String, tempGuid: String): ChatMessage = withContext(Dispatchers.IO) {
        val api = requireClient()
        val method = if (privateApi()) "private-api" else "apple-script"
        val targets = if (method == "private-api") listOf(conversation.guid) else conversation.guids
        var last: Throwable? = null
        for (room in targets) {
            try {
                val sent = api.sendText(room, text, tempGuid, method)
                storeMessages(listOf(sent))
                return@withContext sent
            } catch (e: ApiException) {
                if (e.isAuthError) throw e
                last = e
            }
        }
        throw last ?: IllegalStateException("No room to send to")
    }

    /** Starts a 1:1 chat with [address] by sending [text]; returns the new conversation. */
    suspend fun newChat(address: String, text: String): Conversation = withContext(Dispatchers.IO) {
        val guid = requireClient().newChat(address, text)
        refreshConversations()
        _conversations.value.firstOrNull { guid in it.guids }
            ?: Conversation(guid, "", listOf(address), false, text, System.currentTimeMillis(), true)
    }

    /** Marks read on the Mac (Private API only); clears the local unread mark either way. */
    suspend fun markRead(conversation: Conversation) = withContext(Dispatchers.IO) {
        _conversations.update { list -> list.map { if (it.guid == conversation.guid) it.copy(unread = false) else it } }
        if (!privateApi()) return@withContext
        val api = clientOrNull() ?: return@withContext
        for (guid in conversation.guids) runCatching { api.markRead(guid) }
    }

    /** Re-reads `server/info` so a Private API switched on later is noticed. */
    suspend fun refreshServerInfo() = withContext(Dispatchers.IO) {
        val api = clientOrNull() ?: return@withContext
        runCatching { api.serverInfo() }.onSuccess { info ->
            dataStore.edit { it[KEY_PRIVATE_API] = info.privateApiReady }
        }
    }

    // ---- attachments ----

    private fun attachmentDir(): File = File(filesDir, "attachments")

    /** The attachment's bytes, from the file cache or downloaded into it. */
    suspend fun attachmentBytes(guid: String): ByteArray = withContext(Dispatchers.IO) {
        val dir = attachmentDir().apply { mkdirs() }
        val file = File(dir, guid.filter { it.isLetterOrDigit() || it == '-' })
        if (file.exists() && file.length() > 0) return@withContext file.readBytes()
        val bytes = requireClient().downloadAttachment(guid, MAX_IMAGE_BYTES)
        file.writeBytes(bytes)
        trimAttachmentCache(dir)
        bytes
    }

    private fun trimAttachmentCache(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > ATTACHMENT_CACHE_BYTES) f.delete()
        }
    }

    companion object {
        private val KEY_URL = stringPreferencesKey("server_url")
        private val KEY_PASSWORD = stringPreferencesKey("server_password_enc")
        private val KEY_PRIVATE_API = booleanPreferencesKey("private_api")
        private val KEY_FAVORITES = stringSetPreferencesKey("favorites")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync")
        private val KEY_CONTACTS_AT = longPreferencesKey("contacts_at")
        private const val CONTACTS_FILE = "contacts.json"
        private val CONTACT_MAP = MapSerializer(String.serializer(), String.serializer())

        /** Messages per list sweep: enough for the recently active chats, small enough for a tunnel. */
        const val SWEEP_SIZE = 300
        private const val MESSAGE_CACHE_SIZE = 5_000
        private const val CONTACTS_TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_IMAGE_BYTES = 12L * 1024 * 1024
        private const val ATTACHMENT_CACHE_BYTES = 60L * 1024 * 1024

        @Volatile
        private var instance: ChatRepository? = null

        fun get(ctx: SealedLightContext): ChatRepository =
            instance ?: synchronized(this) { instance ?: ChatRepository(ctx).also { instance = it } }

        /** A user-facing line for a failure. */
        fun describe(t: Throwable): String = when {
            t is ApiException && t.isAuthError -> "Wrong server password"
            t is ApiException -> "Server error (${t.code})"
            t is IllegalStateException -> t.message ?: "Not set up"
            else -> "Can't reach the server"
        }
    }
}
