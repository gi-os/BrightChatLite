package com.gios.brightchatlite.model

/**
 * Address to name lookup, built from the BlueBubbles server's address book
 * (`GET /api/v1/contact`, the Mac's contacts). Ported from BrightChat's `Contacts`.
 *
 * The server stores numbers in many formats (`+1 (315) 212-2695`, `608-264-6591`,
 * `+16082138014`), so keys are normalized: phone numbers to their last 10 digits, emails
 * to lowercase. That collapses every variant onto one key and matches the E.164 handles the
 * message API returns.
 */
class Contacts(private val byKey: Map<String, String> = emptyMap()) {

    fun name(address: String): String? = byKey[key(address)]?.takeIf { it != "null" }

    fun title(conversation: Conversation): String =
        title(conversation.displayName, conversation.participants)

    /** Full name for a 1:1, first names for a group, the explicit group name if set. */
    fun title(displayName: String, participants: List<String>): String = when {
        displayName.isNotBlank() && displayName != "null" -> displayName
        participants.size == 1 -> label(participants[0], firstNameOnly = false)
        participants.isEmpty() -> "Unknown"
        else -> participants.joinToString(", ") { label(it, firstNameOnly = true) }
    }

    /** Known: a named group, or any participant in the address book. */
    fun knows(conversation: Conversation): Boolean =
        (conversation.displayName.isNotBlank() && conversation.displayName != "null") ||
            conversation.participants.any { name(it) != null }

    /** Sender label inside a thread; first name keeps group rows short. */
    fun sender(address: String): String = label(address, firstNameOnly = true)

    fun asMap(): Map<String, String> = byKey

    val isEmpty: Boolean get() = byKey.isEmpty()

    private fun label(address: String, firstNameOnly: Boolean): String {
        val name = name(address) ?: return Handles.pretty(address)
        return if (firstNameOnly) name.substringBefore(" ") else name
    }

    companion object {
        fun key(address: String): String = Handles.key(address)

        /** Builds the index from (address, name) pairs; the first name wins per key. */
        fun from(pairs: List<Pair<String, String>>): Contacts {
            val map = HashMap<String, String>()
            for ((address, name) in pairs) {
                val k = key(address)
                if (k.isNotEmpty() && name.isNotBlank() && name != "null") map.putIfAbsent(k, name)
            }
            return Contacts(map)
        }
    }
}

/** Handle normalization shared by the contact index and the new-message flow. */
object Handles {

    /** Lookup key: last 10 digits of a phone number, or a lowercased email. */
    fun key(address: String): String {
        val a = address.trim()
        if (a.contains("@")) return a.lowercase()
        val digits = a.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    /**
     * A typed recipient as the address BlueBubbles expects: an email lowercased, a phone
     * number reduced to digits with a leading `+` kept, a bare 10-digit number given the
     * North American `+1`. Null when the input is neither.
     */
    fun normalizeAddress(input: String): String? {
        val a = input.trim()
        if (a.isEmpty()) return null
        if (a.contains("@")) {
            val at = a.indexOf('@')
            return if (at > 0 && at < a.length - 1 && !a.any { it.isWhitespace() }) a.lowercase() else null
        }
        if (a.any { it.isLetter() }) return null
        val digits = a.filter { it.isDigit() }
        if (digits.length < 3) return null
        return when {
            a.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> digits
        }
    }

    /** `+13152122695` as `(315) 212-2695`; anything else unchanged. */
    fun pretty(address: String): String {
        val a = address.trim()
        if (a.contains("@")) return a
        val digits = a.filter { it.isDigit() }
        val national = when {
            digits.length == 11 && digits.startsWith("1") && (a.startsWith("+1") || a.startsWith("1")) -> digits.substring(1)
            digits.length == 10 && !a.startsWith("+") -> digits
            else -> return a
        }
        return "(${national.substring(0, 3)}) ${national.substring(3, 6)}-${national.substring(6)}"
    }
}
