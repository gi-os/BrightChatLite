package com.gios.brightchatlite

import com.gios.brightchatlite.model.ChatMessage
import com.gios.brightchatlite.model.ReactionType
import com.gios.brightchatlite.model.ThreadRow
import com.gios.brightchatlite.model.buildThreadRows
import com.gios.brightchatlite.model.foldReactions
import com.gios.brightchatlite.model.mergeIntoThread
import com.gios.brightchatlite.model.mergePage
import com.gios.brightchatlite.model.replaceKeepingPending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadTest {
    private fun msg(guid: String, date: Long, fromMe: Boolean = false, sender: String? = "+1", tempGuid: String? = null, text: String = guid) =
        ChatMessage(guid, text, date, fromMe, if (fromMe) null else sender, tempGuid = tempGuid)

    @Test fun echoReplacesOptimisticRow() {
        val pending = msg("temp-1", 10, fromMe = true, tempGuid = "temp-1")
        val list = listOf(msg("a", 1), pending)
        val merged = mergeIntoThread(list, msg("real", 11, fromMe = true, tempGuid = "temp-1"))
        assertEquals(listOf("a", "real"), merged.map { it.guid })
    }

    @Test fun echoAfterPollNeverDuplicates() {
        // The poll already brought the real row in, then the send's response lands.
        val list = listOf(msg("temp-1", 10, true, tempGuid = "temp-1"), msg("real", 11, true, tempGuid = "temp-1"))
        val merged = mergeIntoThread(list, msg("real", 11, true, tempGuid = "temp-1"))
        assertEquals(1, merged.count { it.guid == "real" })
        assertEquals(1, merged.size)
    }

    @Test fun fetchKeepsInFlightSends() {
        val current = listOf(msg("a", 1), msg("temp-2", 5, true, tempGuid = "temp-2"))
        assertEquals(listOf("a", "b", "temp-2"), replaceKeepingPending(current, listOf(msg("a", 1), msg("b", 2))).map { it.guid })
        val acked = replaceKeepingPending(current, listOf(msg("a", 1), msg("x", 5, true, tempGuid = "temp-2")))
        assertEquals(listOf("a", "x"), acked.map { it.guid })
    }

    @Test fun pageMergeUpdatesReceipts() {
        val current = listOf(msg("a", 1, true))
        val merged = mergePage(current, listOf(msg("a", 1, true).copy(dateRead = 99), msg("b", 2)))
        assertEquals(99, merged.first { it.guid == "a" }.dateRead)
        assertEquals(2, merged.size)
    }

    @Test fun tapbacksFoldOntoTargets() {
        val target = msg("t", 1)
        val love = msg("r1", 2, fromMe = true).copy(associatedMessageGuid = "p:0/t", associatedMessageType = "love")
        val laugh = msg("r2", 3, sender = "+2").copy(associatedMessageGuid = "p:0/t", associatedMessageType = "laugh")
        val unlove = msg("r3", 4, fromMe = true).copy(associatedMessageGuid = "p:0/t", associatedMessageType = "-love")
        val folded = foldReactions(listOf(laugh, target, love, unlove))
        assertEquals(1, folded.size)
        assertEquals(listOf(ReactionType.LAUGH), folded[0].reactions.map { it.type })
    }

    @Test fun unknownGroupEventsDropped() {
        val folded = foldReactions(listOf(msg("a", 1), msg("x", 2).copy(itemType = 6), msg("rename", 3).copy(itemType = 2, groupTitle = "Fam")))
        assertEquals(listOf("a", "rename"), folded.map { it.guid })
    }

    @Test fun rowsGroupRunsDaysAndReceipt() {
        val day = 24L * 3600 * 1000
        val msgs = listOf(
            msg("1", 1000, sender = "+1"),
            msg("2", 2000, sender = "+1"),
            msg("3", 3000, fromMe = true).copy(dateDelivered = 3500),
            msg("4", day + 10, fromMe = true).copy(dateRead = day + 50),
        )
        val rows = buildThreadRows(msgs, isGroup = false, dayOf = { it / day * day }, timeOf = { "T$it" })
        val kinds = rows.map { if (it is ThreadRow.DayHeader) "D" else (it as ThreadRow.Turn).message.guid + (if (it.showLabel) "*" else "") }
        assertEquals(listOf("D", "1*", "2", "3*", "D", "4*"), kinds)
        val receipts = rows.filterIsInstance<ThreadRow.Turn>().mapNotNull { it.receipt }
        assertEquals(listOf("Read T${day + 50}"), receipts)
    }

    @Test fun longGapBreaksRunAndGroupsHaveNoReceipt() {
        val msgs = listOf(msg("1", 0), msg("2", 2L * 3600 * 1000), msg("3", 2L * 3600 * 1000 + 1, fromMe = true).copy(dateDelivered = 1))
        val rows = buildThreadRows(msgs, isGroup = true, dayOf = { 0L }, timeOf = { "" }).filterIsInstance<ThreadRow.Turn>()
        assertTrue(rows.all { it.showLabel })
        assertTrue(rows.all { it.receipt == null })
    }

    @Test fun failedSendSaysNotDelivered() {
        val rows = buildThreadRows(listOf(msg("1", 1, fromMe = true).copy(error = 4)), false, { 0L }, { "" })
        assertEquals("Not delivered", (rows.last() as ThreadRow.Turn).receipt)
    }
}
