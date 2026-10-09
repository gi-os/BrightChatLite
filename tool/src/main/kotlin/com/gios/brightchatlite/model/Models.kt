package com.gios.brightchatlite.model

import kotlinx.serialization.Serializable

/**
 * A conversation. BlueBubbles calls it a "chat". [guid] (for example
 * `iMessage;+;chat36268974474180030`) keys its messages and is the send target.
 *
 * Ported from BrightChat's `Conversation`, minus the Beeper, agent and joined-person
 * fields this tool does not have.
 */
@Serializable
data class Conversation(
    val guid: String,
    val displayName: String,
    /** Raw handle addresses (phone numbers or emails). */
    val participants: List<String>,
    val isGroup: Boolean,
    val lastText: String,
    /** Epoch millis; 0 when unknown. */
    val lastDate: Long,
    val lastFromMe: Boolean,
    /**
     * Every chat-room guid this conversation spans. Usually just [guid]. iMessage can fork
     * one group into sibling rooms (same name, same participants, different guid); those
     * are collapsed into one row, ordered so [guid] is the live room to send to.
     */
    val guids: List<String> = listOf(guid),
    /** The newest incoming message has not been read on any device. */
    val unread: Boolean = false,
    /** Who sent [lastText]; null when it was me. */
    val lastSender: String? = null,
) {
    /** Nameless fallback title. Prefer [com.gios.brightchatlite.model.Contacts.title]. */
    val title: String
        get() = when {
            displayName.isNotBlank() && displayName != "null" -> displayName
            participants.isNotEmpty() -> participants.joinToString(", ")
            else -> "Unknown"
        }
}

/** One file riding along with a message. [guid] keys the download endpoint. */
@Serializable
data class Attachment(
    val guid: String,
    val mimeType: String? = null,
    val transferName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val totalBytes: Long = 0,
) {
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true

    /** A short human type, for example "Video", "Audio", "Contact". */
    val typeLabel: String
        get() = when {
            mimeType == null -> "File"
            mimeType.startsWith("video/") -> "Video"
            mimeType.startsWith("audio/") -> "Audio"
            mimeType.contains("vcard") -> "Contact"
            mimeType == "application/pdf" -> "PDF"
            mimeType.startsWith("image/") -> "Photo"
            else -> "File"
        }

    /** The placeholder row shown for a file this tool does not render inline. */
    val fileLabel: String
        get() = transferName?.takeIf { it.isNotBlank() }?.let { "$typeLabel · $it" } ?: typeLabel
}

/**
 * The six classic iMessage tapbacks plus the iOS 17 emoji form. [apiValue] is the word the
 * server reports in `associatedMessageType` (`love`, or `-love` for a removal).
 */
enum class ReactionType(val apiValue: String, val mark: String) {
    LOVE("love", "♥"),
    LIKE("like", "+1"),
    DISLIKE("dislike", "-1"),
    LAUGH("laugh", "HA"),
    EMPHASIZE("emphasize", "!!"),
    QUESTION("question", "?"),
    EMOJI("emoji", "");

    val verb: String
        get() = when (this) {
            LOVE -> "loved"
            LIKE -> "liked"
            DISLIKE -> "disliked"
            LAUGH -> "laughed at"
            EMPHASIZE -> "emphasized"
            QUESTION -> "questioned"
            EMOJI -> "reacted to"
        }

    companion object {
        fun fromApiValue(raw: String?): ReactionType? {
            if (raw.isNullOrBlank()) return null
            val base = raw.removePrefix("-")
            return entries.firstOrNull { it.apiValue == base }
        }
    }
}

/** A tapback folded onto its target message. */
@Serializable
data class Reaction(
    val type: ReactionType,
    val fromMe: Boolean,
    val sender: String?,
    val emoji: String? = null,
) {
    val mark: String get() = if (type == ReactionType.EMOJI) emoji ?: "" else type.mark
}

enum class GroupEvent { RENAMED, MEMBER_ADDED, MEMBER_REMOVED, MEMBER_LEFT, PHOTO_CHANGED }

/** One message within a conversation. Field meanings follow the BlueBubbles message object. */
@Serializable
data class ChatMessage(
    val guid: String,
    val text: String,
    /** Epoch millis. */
    val date: Long,
    val fromMe: Boolean,
    /** Handle address of the sender; null when from me. */
    val sender: String?,
    val attachments: List<Attachment> = emptyList(),
    val dateDelivered: Long = 0,
    val dateRead: Long = 0,
    val itemType: Int = 0,
    val groupTitle: String? = null,
    val groupActionType: Int = 0,
    /** Non-zero when a message I sent failed to deliver. */
    val error: Int = 0,
    val associatedMessageGuid: String? = null,
    val associatedMessageType: String? = null,
    val associatedMessageEmoji: String? = null,
    /** Tapbacks folded onto this message for display; see [foldReactions]. */
    val reactions: List<Reaction> = emptyList(),
    /** The client tempGuid a send carried, echoed back by the server. */
    val tempGuid: String? = null,
    /** The chat-room guid this message belongs to. */
    val chatGuid: String? = null,
) {
    val images: List<Attachment> get() = attachments.filter { it.isImage }

    val isGroupEvent: Boolean get() = itemType != 0

    val groupEvent: GroupEvent?
        get() = when {
            itemType == 2 -> GroupEvent.RENAMED
            itemType == 1 && groupActionType == 0 -> GroupEvent.MEMBER_ADDED
            itemType == 1 && groupActionType == 1 -> GroupEvent.MEMBER_REMOVED
            itemType == 3 && groupActionType == 0 -> GroupEvent.MEMBER_LEFT
            itemType == 3 -> GroupEvent.PHOTO_CHANGED
            else -> null
        }

    fun groupEventText(actor: String): String? = when (groupEvent) {
        GroupEvent.RENAMED ->
            groupTitle?.takeIf { it.isNotBlank() }?.let { "$actor named the conversation “$it”" }
                ?: "$actor removed the conversation name"
        GroupEvent.MEMBER_ADDED -> "$actor added someone to the conversation"
        GroupEvent.MEMBER_REMOVED -> "$actor removed someone from the conversation"
        GroupEvent.MEMBER_LEFT -> "$actor left the conversation"
        GroupEvent.PHOTO_CHANGED -> "$actor changed the group photo"
        null -> null
    }

    val reactionType: ReactionType? get() = ReactionType.fromApiValue(associatedMessageType)

    val isReaction: Boolean
        get() = !associatedMessageGuid.isNullOrBlank() && reactionType != null

    val isReactionRemoval: Boolean get() = associatedMessageType?.startsWith("-") == true

    /** The plain guid a tapback targets, without iMessage's `p:<n>/` and `bp:` prefixes. */
    val reactionTargetGuid: String?
        get() {
            val raw = associatedMessageGuid ?: return null
            raw.indexOf('/').let { if (it >= 0) return raw.substring(it + 1) }
            if (raw.startsWith("bp:")) return raw.substring(3)
            return raw
        }

    /** The body line, or null when the text is only the attachment placeholder. */
    val bodyText: String?
        get() = if (text == ATTACHMENT_PLACEHOLDER && attachments.isNotEmpty()) null else text.ifEmpty { null }

    /** One-line list summary: the text, or a bracketed descriptor for attachment-only messages. */
    val previewText: String
        get() {
            val t = if (text == ATTACHMENT_PLACEHOLDER) "" else text
            if (t.isNotBlank()) return t
            val imageCount = images.size
            if (imageCount > 0) return if (imageCount == 1) "[Photo]" else "[$imageCount Photos]"
            if (attachments.size == 1) return "[${attachments.first().typeLabel}]"
            if (attachments.isNotEmpty()) return "[${attachments.size} Attachments]"
            return if (text == ATTACHMENT_PLACEHOLDER) "[Attachment]" else ""
        }

    companion object {
        const val ATTACHMENT_PLACEHOLDER = "[Attachment]"
    }
}
