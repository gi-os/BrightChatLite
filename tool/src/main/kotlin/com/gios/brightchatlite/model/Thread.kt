package com.gios.brightchatlite.model

/**
 * Pure thread logic, ported from BrightChat's `ThreadMerge.kt` and
 * `ChatViewModel.foldReactions`, plus the run grouping its thread screen did inline.
 * No Android imports so it can be unit tested.
 */

/** The guid prefix a not-yet-acknowledged send carries. */
const val PENDING_PREFIX = "temp-"

/**
 * Replaces the thread with a freshly fetched page without dropping sends still in flight.
 * A pending row survives unless the fetch carries the real message for it, recognized by
 * the server echoing our tempGuid back.
 */
fun replaceKeepingPending(current: List<ChatMessage>, fetched: List<ChatMessage>): List<ChatMessage> {
    val pending = current.filter { it.guid.startsWith(PENDING_PREFIX) }
    if (pending.isEmpty()) return fetched
    val acknowledged = HashSet<String>(fetched.size * 2)
    for (m in fetched) {
        acknowledged.add(m.guid)
        m.tempGuid?.let(acknowledged::add)
    }
    return fetched + pending.filterNot { it.guid in acknowledged }
}

/**
 * Folds one message into the thread: one row per guid, always. An incoming message matches
 * an existing row by guid, or the optimistic row by tempGuid; every other row carrying the
 * same guid is dropped so a duplicate key cannot exist.
 */
fun mergeIntoThread(list: List<ChatMessage>, incoming: ChatMessage): List<ChatMessage> {
    val idx = list.indexOfFirst {
        it.guid == incoming.guid || (incoming.tempGuid != null && it.guid == incoming.tempGuid)
    }
    if (idx < 0) return list + incoming
    val out = ArrayList<ChatMessage>(list.size)
    for ((i, existing) in list.withIndex()) {
        when {
            i == idx -> out.add(incoming)
            existing.guid == incoming.guid -> Unit
            else -> out.add(existing)
        }
    }
    return out
}

/** Merges a fetched page into what is shown, newest data winning, de-duplicated by guid. */
fun mergePage(current: List<ChatMessage>, page: List<ChatMessage>): List<ChatMessage> {
    var out = current
    for (m in page) out = mergeIntoThread(out, m)
    return out
}

/**
 * Tapback messages folded onto their targets, unknown group events dropped, oldest first.
 * iMessage holds one tapback per person per message, so a newer one replaces the last and a
 * removal (`-love`) clears it.
 */
fun foldReactions(raw: List<ChatMessage>): List<ChatMessage> {
    val rows = raw.filterNot { it.isGroupEvent && it.groupEvent == null }
    if (rows.none { it.isReaction }) return rows.sortedBy { it.date }
    val active = LinkedHashMap<Pair<String, String>, Reaction?>()
    for (r in rows.filter { it.isReaction }.sortedBy { it.date }) {
        val target = r.reactionTargetGuid ?: continue
        val type = r.reactionType ?: continue
        val emoji = if (type == ReactionType.EMOJI) r.associatedMessageEmoji else null
        val reactor = if (r.fromMe) "me" else (r.sender ?: "?")
        active[target to reactor] = if (r.isReactionRemoval) null else Reaction(type, r.fromMe, r.sender, emoji)
    }
    val byTarget = HashMap<String, MutableList<Reaction>>()
    for ((key, reaction) in active) {
        if (reaction != null) byTarget.getOrPut(key.first) { mutableListOf() }.add(reaction)
    }
    return rows.asSequence()
        .filterNot { it.isReaction }
        .map { m -> byTarget[m.guid]?.let { m.copy(reactions = it) } ?: m }
        .sortedBy { it.date }
        .toList()
}

/** One rendered line of a thread. */
sealed interface ThreadRow {
    val key: String

    /** A day divider ("Today", "Monday", "7/1/26"). */
    data class DayHeader(val dayStart: Long, override val key: String) : ThreadRow

    /** A centered system line (group rename, member change). */
    data class Event(val message: ChatMessage, override val key: String) : ThreadRow

    /**
     * A message turn. [showLabel] is true on the first message of a same-speaker run, so a
     * name sits above a run rather than above every line. [receipt] is the dim line under
     * my newest sent message in a 1:1 ("Delivered", "Read 3:14 PM", "Not delivered").
     */
    data class Turn(
        val message: ChatMessage,
        val showLabel: Boolean,
        val receipt: String?,
        override val key: String,
    ) : ThreadRow
}

/** Gap after which a same-speaker run is broken and the name shown again. */
const val RUN_BREAK_MS: Long = 60L * 60L * 1000L

/**
 * Groups folded, date-ordered messages into rows: a day header whenever the local day
 * changes, a label on the first turn of each same-speaker run (a run also breaks after
 * [RUN_BREAK_MS] or across an event line), and a receipt under my newest sent message
 * when [isGroup] is false.
 *
 * [dayOf] maps an epoch millis to the start of its local day; [timeOf] formats a clock
 * time for "Read 3:14 PM". Both are injected so this stays testable without a time zone.
 */
fun buildThreadRows(
    messages: List<ChatMessage>,
    isGroup: Boolean,
    dayOf: (Long) -> Long,
    timeOf: (Long) -> String,
): List<ThreadRow> {
    val out = ArrayList<ThreadRow>(messages.size + 8)
    val receiptGuid = if (isGroup) null else messages.lastOrNull { it.fromMe && !it.isGroupEvent }?.guid
    var lastDay = Long.MIN_VALUE
    var lastSpeaker: String? = null
    var lastDate = 0L
    for (m in messages) {
        val day = dayOf(m.date)
        if (day != lastDay) {
            out.add(ThreadRow.DayHeader(day, "day-$day"))
            lastDay = day
            lastSpeaker = null
        }
        if (m.isGroupEvent) {
            out.add(ThreadRow.Event(m, m.guid))
            lastSpeaker = null
            continue
        }
        val speaker = if (m.fromMe) "me" else (m.sender ?: "?")
        val showLabel = speaker != lastSpeaker || m.date - lastDate > RUN_BREAK_MS
        lastSpeaker = speaker
        lastDate = m.date
        val receipt = if (m.guid == receiptGuid) receiptText(m, timeOf) else null
        out.add(ThreadRow.Turn(m, showLabel, receipt, m.guid))
    }
    return out
}

/** The receipt line for a message I sent, or null when there is nothing to say yet. */
fun receiptText(message: ChatMessage, timeOf: (Long) -> String): String? = when {
    message.error != 0 -> "Not delivered"
    message.guid.startsWith(PENDING_PREFIX) -> "Sending…"
    message.dateRead > 0 -> "Read ${timeOf(message.dateRead)}"
    message.dateDelivered > 0 -> "Delivered"
    else -> null
}
