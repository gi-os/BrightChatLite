package com.gios.brightchatlite.model

/**
 * The chat list is built from message sweeps, and a sweep is a fixed number of
 * messages rather than chats. A few busy threads can fill a whole sweep, so a list
 * rebuilt from one sweep can shrink to a handful of rows. This keeps every chat any
 * sweep has seen and lets each new sweep update only the rows it covers.
 */
object ConversationList {

    /** The most rows kept. Older rows past this are dropped from the cache. */
    const val MAX_ROWS = 1_000

    /**
     * Adds [incoming] to [current]. An incoming row replaces every current row it
     * covers when it is at least as new, so read state and previews update even when
     * no new message arrived. An older page never pushes a newer row out. The result is
     * newest first.
     */
    fun merge(current: List<Conversation>, incoming: List<Conversation>): List<Conversation> {
        if (incoming.isEmpty()) return current
        val kept = current.toMutableList()
        val added = ArrayList<Conversation>()
        for (row in incoming) {
            val covered = kept.filter { overlaps(it, row) }
            val newestCovered = covered.maxOfOrNull { it.lastDate } ?: Long.MIN_VALUE
            if (covered.isEmpty() || row.lastDate >= newestCovered) {
                kept.removeAll(covered)
                added.add(row)
            }
        }
        return (kept + added).sortedByDescending { it.lastDate }.take(MAX_ROWS)
    }

    /** True when both rows are the same chat: a shared room guid, or the same forked group. */
    fun overlaps(a: Conversation, b: Conversation): Boolean {
        if (a.guids.any { it in b.guids }) return true
        val ga = groupKey(a) ?: return false
        return ga == groupKey(b)
    }

    private fun groupKey(c: Conversation): String? {
        if (!c.isGroup || c.participants.isEmpty()) return null
        return "g|${c.displayName}|${c.participants.sorted().joinToString(",")}"
    }
}
