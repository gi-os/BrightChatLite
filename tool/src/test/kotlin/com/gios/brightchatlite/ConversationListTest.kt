package com.gios.brightchatlite

import com.gios.brightchatlite.model.Conversation
import com.gios.brightchatlite.model.ConversationList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationListTest {
    private fun chat(guid: String, date: Long, text: String = guid, unread: Boolean = false, guids: List<String> = listOf(guid)) =
        Conversation(guid, "", listOf("+1$guid"), false, text, date, false, guids = guids, unread = unread)

    private fun group(guid: String, date: Long, name: String = "Trip") =
        Conversation(guid, name, listOf("+1", "+2"), true, guid, date, false)

    @Test fun aSmallSweepKeepsTheChatsItDidNotCover() {
        val cached = (1..20).map { chat("c$it", it * 10L) }
        val sweep = listOf(chat("c20", 500, "new"), chat("c19", 400, "newer"))
        val merged = ConversationList.merge(cached, sweep)
        assertEquals(20, merged.size)
        assertEquals(listOf("c20", "c19", "c18"), merged.take(3).map { it.guid })
        assertEquals("new", merged.first().lastText)
    }

    @Test fun anOlderPageOnlyAddsChatsTheListLacks() {
        val current = listOf(chat("a", 100, "fresh"), chat("b", 90))
        val olderPage = listOf(chat("a", 40, "stale"), chat("c", 30), chat("d", 20))
        val merged = ConversationList.merge(current, olderPage)
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.guid })
        assertEquals("fresh", merged.first().lastText)
    }

    @Test fun readStateUpdatesWithoutANewMessage() {
        val current = listOf(chat("a", 100, unread = true))
        val merged = ConversationList.merge(current, listOf(chat("a", 100, unread = false)))
        assertFalse(merged.single().unread)
    }

    @Test fun aCollapsedForkedGroupReplacesBothRooms() {
        val current = listOf(chat("room1", 50), chat("room2", 60), chat("other", 10))
        val collapsed = chat("room2", 70, guids = listOf("room2", "room1"))
        val merged = ConversationList.merge(current, listOf(collapsed))
        assertEquals(listOf("room2", "other"), merged.map { it.guid })
    }

    @Test fun theSameGroupUnderANewRoomGuidIsOneRow() {
        val merged = ConversationList.merge(listOf(group("old-room", 10)), listOf(group("new-room", 20)))
        assertEquals(listOf("new-room"), merged.map { it.guid })
        assertTrue(ConversationList.overlaps(group("x", 1), group("y", 2)))
        assertFalse(ConversationList.overlaps(group("x", 1, "Trip"), group("y", 2, "Work")))
    }

    @Test fun theListIsCapped() {
        val many = (1..1_200).map { chat("c$it", it.toLong()) }
        assertEquals(ConversationList.MAX_ROWS, ConversationList.merge(emptyList(), many).size)
    }
}
