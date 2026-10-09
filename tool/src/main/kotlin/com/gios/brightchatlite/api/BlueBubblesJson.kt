package com.gios.brightchatlite.api

import com.gios.brightchatlite.model.Attachment
import com.gios.brightchatlite.model.ChatMessage
import com.gios.brightchatlite.model.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * BlueBubbles response parsing, ported from the companion of BrightChat's
 * `BlueBubblesApi` (which used org.json). Pure Kotlin so it is unit tested against
 * captured response shapes.
 */
object BlueBubblesJson {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- small accessors that treat JSON null and the literal "null" as absent ----

    fun JsonObject.str(key: String): String {
        val p = this[key] as? JsonPrimitive ?: return ""
        if (p is JsonNull) return ""
        val v = p.contentOrNull ?: return ""
        return if (v == "null") "" else v
    }

    fun JsonObject.strOrNull(key: String): String? = str(key).takeIf { it.isNotBlank() }

    fun JsonObject.long(key: String): Long {
        val p = this[key] as? JsonPrimitive ?: return 0L
        if (p is JsonNull) return 0L
        return p.longOrNull ?: p.doubleOrNull?.toLong() ?: 0L
    }

    fun JsonObject.int(key: String): Int = long(key).toInt()

    fun JsonObject.bool(key: String): Boolean {
        val p = this[key] as? JsonPrimitive ?: return false
        if (p is JsonNull) return false
        return p.booleanOrNull ?: (p.longOrNull?.let { it != 0L } ?: false)
    }

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonElement.asObj(): JsonObject? = this as? JsonObject

    // ---- envelopes ----

    fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** The `data` array of a response envelope, empty when absent. */
    fun dataArray(text: String): List<JsonObject> =
        parse(text)?.arr("data")?.mapNotNull { it.asObj() } ?: emptyList()

    /** The `data` object of a response envelope. */
    fun dataObject(text: String): JsonObject? = parse(text)?.obj("data")

    // ---- server info ----

    /** `private_api && helper_connected`: the gate for mark-read. */
    fun privateApiReady(info: JsonObject?): Boolean =
        info != null && info.bool("private_api") && info.bool("helper_connected")

    // ---- chats ----

    /**
     * A chat's participant handles. 1:1 chats can come back with an empty `participants`
     * list, but the `chatIdentifier` is then the other party's address.
     */
    fun chatParticipants(chat: JsonObject): List<String> {
        val listed = chat.arr("participants")?.mapNotNull { el ->
            el.asObj()?.str("address")?.takeIf { it.isNotBlank() }
        } ?: emptyList()
        return listed.ifEmpty {
            chat.str("chatIdentifier").takeIf { it.isNotBlank() && !it.startsWith("chat") }
                ?.let { listOf(it) } ?: emptyList()
        }
    }

    /** Chat `style` 43 is a group, 45 a one-to-one. */
    fun isGroup(chat: JsonObject): Boolean = chat.int("style") == 43

    // ---- messages ----

    /** Body text, falling back to the attachment placeholder when blank. */
    fun messageText(o: JsonObject): String {
        val t = o.str("text").trim()
        if (t.isNotEmpty()) return t
        val attachments = o.arr("attachments")?.size ?: 0
        return if (attachments > 0) ChatMessage.ATTACHMENT_PLACEHOLDER else ""
    }

    fun parseMessage(o: JsonObject, chatGuid: String? = null): ChatMessage {
        val handle = o.obj("handle")
        val embeddedChat = o.arr("chats")?.firstOrNull()?.asObj()?.strOrNull("guid")
        return ChatMessage(
            guid = o.str("guid"),
            text = messageText(o),
            date = o.long("dateCreated"),
            fromMe = o.bool("isFromMe"),
            sender = handle?.strOrNull("address"),
            attachments = parseAttachments(o),
            dateDelivered = o.long("dateDelivered"),
            dateRead = o.long("dateRead"),
            itemType = o.int("itemType"),
            groupTitle = o.strOrNull("groupTitle"),
            groupActionType = o.int("groupActionType"),
            error = o.int("error"),
            associatedMessageGuid = o.strOrNull("associatedMessageGuid"),
            associatedMessageType = o.strOrNull("associatedMessageType"),
            associatedMessageEmoji = o.strOrNull("associatedMessageEmoji"),
            tempGuid = o.strOrNull("tempGuid"),
            chatGuid = chatGuid ?: embeddedChat,
        )
    }

    /**
     * Attachments, minus iMessage's `*.pluginPayloadAttachment` rich-link blobs: those are
     * link-preview metadata, not files, and the URL is already in the text.
     */
    fun parseAttachments(o: JsonObject): List<Attachment> {
        val arr = o.arr("attachments") ?: return emptyList()
        return arr.mapNotNull { el ->
            val a = el.asObj() ?: return@mapNotNull null
            val guid = a.strOrNull("guid") ?: return@mapNotNull null
            val name = a.strOrNull("transferName")
            if (name?.endsWith(".pluginPayloadAttachment", ignoreCase = true) == true) return@mapNotNull null
            Attachment(
                guid = guid,
                mimeType = a.strOrNull("mimeType"),
                transferName = name,
                width = a.int("width"),
                height = a.int("height"),
                totalBytes = a.long("totalBytes"),
            )
        }
    }

    // ---- the conversation list ----

    /**
     * Builds the conversation list from one DESC `message/query` sweep with
     * `with: [chats, chats.participants, attachment]`, as BrightChat does: the first (newest)
     * message seen per chat sets recency, the newest real (non-tapback, non-event) message
     * sets the preview, and the newest non-event message decides unread
     * (`!isFromMe && dateRead == 0`). BrightChat drives the list this way because
     * `chat/query` sorts on an unreliable cached `lastmessage`.
     *
     * Forked group rooms (same name and participants, different guid) collapse into one row
     * whose [Conversation.guid] is the room with the newest real message: the send target.
     */
    fun conversationsFromSweep(messages: List<JsonObject>): List<Conversation> {
        val byGuid = LinkedHashMap<String, Conversation>()
        val previewFinal = HashSet<String>()
        val unreadByGuid = HashMap<String, Boolean>()
        val realDateByGuid = HashMap<String, Long>()
        for (m in messages) {
            val chats = m.arr("chats") ?: continue
            val msg = parseMessage(m)
            for (el in chats) {
                val chat = el.asObj() ?: continue
                val guid = chat.str("guid")
                if (guid.isBlank()) continue
                if (!msg.isReaction) realDateByGuid[guid] = maxOf(realDateByGuid[guid] ?: 0L, msg.date)
                if (!msg.isGroupEvent && guid !in unreadByGuid) {
                    unreadByGuid[guid] = !msg.fromMe && msg.dateRead == 0L
                }
                val isSpeech = !msg.isReaction && !msg.isGroupEvent
                val existing = byGuid[guid]
                if (existing == null) {
                    byGuid[guid] = Conversation(
                        guid = guid,
                        displayName = chat.str("displayName"),
                        participants = chatParticipants(chat),
                        isGroup = isGroup(chat),
                        lastText = if (isSpeech) msg.previewText else reactionLine(msg),
                        lastDate = msg.date,
                        lastFromMe = msg.fromMe,
                        lastSender = if (msg.fromMe) null else msg.sender,
                    )
                    if (isSpeech) previewFinal.add(guid)
                } else if (isSpeech && guid !in previewFinal) {
                    byGuid[guid] = existing.copy(
                        lastText = msg.previewText,
                        lastFromMe = msg.fromMe,
                        lastSender = if (msg.fromMe) null else msg.sender,
                    )
                    previewFinal.add(guid)
                }
            }
        }
        val groups = LinkedHashMap<String, MutableList<Conversation>>()
        for ((guid, conv) in byGuid) {
            val key = groupIdentity(conv) ?: guid
            groups.getOrPut(key) { mutableListOf() }.add(conv.copy(unread = unreadByGuid[guid] == true))
        }
        return groups.values.map { rooms ->
            if (rooms.size == 1) return@map rooms[0]
            val display = rooms.maxByOrNull { it.lastDate } ?: rooms[0]
            val sendOrder = rooms.sortedByDescending { realDateByGuid[it.guid] ?: 0L }
            display.copy(
                guid = sendOrder.first().guid,
                guids = sendOrder.map { it.guid },
                unread = rooms.any { it.unread },
            )
        }.sortedByDescending { it.lastDate }
    }

    /** A list preview for a row whose newest activity is a tapback or a group event. */
    private fun reactionLine(msg: ChatMessage): String {
        val type = msg.reactionType
        if (msg.isReaction && type != null && !msg.isReactionRemoval) {
            return if (msg.fromMe) "You ${type.verb} a message" else "${type.verb.replaceFirstChar { it.uppercase() }} a message"
        }
        return ""
    }

    /** Name plus sorted participants, for groups only; 1:1s never merge. */
    fun groupIdentity(c: Conversation): String? {
        if (!c.isGroup || c.participants.isEmpty()) return null
        return "g|${c.displayName}|${c.participants.sorted().joinToString(",")}"
    }

    // ---- contacts ----

    /**
     * `GET /api/v1/contact` flattened to (address, name) pairs: every phone number and email
     * maps to the contact's display name, or first plus last name.
     */
    fun contacts(data: List<JsonObject>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (c in data) {
            val name = c.str("displayName").ifBlank {
                listOf(c.str("firstName"), c.str("lastName")).filter { it.isNotBlank() }.joinToString(" ")
            }
            if (name.isBlank()) continue
            for (field in listOf("phoneNumbers", "emails")) {
                val arr = c.arr(field) ?: continue
                for (el in arr) {
                    val address = (el as? JsonObject)?.str("address")
                        ?: (el as? JsonPrimitive)?.contentOrNull
                    if (!address.isNullOrBlank()) out.add(address to name)
                }
            }
        }
        return out
    }
}
