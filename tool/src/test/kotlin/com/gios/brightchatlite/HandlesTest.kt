package com.gios.brightchatlite

import com.gios.brightchatlite.model.Contacts
import com.gios.brightchatlite.model.Conversation
import com.gios.brightchatlite.model.Handles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandlesTest {
    private fun convo(name: String = "", vararg people: String) =
        Conversation("g", name, people.toList(), people.size > 1, "", 0, false)

    @Test fun keysCollapsePhoneFormats() {
        assertEquals("3152122695", Handles.key("+1 (315) 212-2695"))
        assertEquals("3152122695", Handles.key("+13152122695"))
        assertEquals("3152122695", Handles.key("315-212-2695"))
        assertEquals("liz@example.com", Handles.key(" Liz@Example.com "))
    }

    @Test fun contactsResolveAnyFormat() {
        val c = Contacts.from(listOf("+1 (315) 212-2695" to "Liz Lemon", "jack@nbc.com" to "Jack Donaghy"))
        assertEquals("Liz Lemon", c.name("+13152122695"))
        assertEquals("Liz Lemon", c.title(convo("", "+13152122695")))
        assertEquals("Liz, Jack", c.title(convo("", "+13152122695", "JACK@nbc.com")))
        assertEquals("Book club", c.title(convo("Book club", "+13152122695", "x@y.z")))
    }

    @Test fun nullNamesAreIgnored() {
        val c = Contacts.from(listOf("+13152122695" to "null", "a@b.c" to " "))
        assertNull(c.name("+13152122695"))
        assertTrue(c.isEmpty)
        assertEquals("Unknown", c.title(convo("null")))
    }

    @Test fun knownWhenNamedOrAnyMemberKnown() {
        val c = Contacts.from(listOf("+13152122695" to "Liz"))
        assertTrue(c.knows(convo("", "+13152122695")))
        assertTrue(c.knows(convo("", "+19999999999", "+13152122695")))
        assertTrue(c.knows(convo("Named group", "+19999999999", "+18888888888")))
        assertFalse(c.knows(convo("", "+19999999999")))
        assertFalse(c.knows(convo("null", "+19999999999")))
    }

    @Test fun unknownSenderFallsBackToPrettyNumber() {
        assertEquals("(999) 999-9999", Contacts().sender("+19999999999"))
        assertEquals("+447700900123", Handles.pretty("+447700900123"))
    }

    @Test fun normalizeTypedAddress() {
        assertEquals("+13152122695", Handles.normalizeAddress("(315) 212-2695"))
        assertEquals("+13152122695", Handles.normalizeAddress("1 315 212 2695"))
        assertEquals("+447700900123", Handles.normalizeAddress("+44 7700 900123"))
        assertEquals("liz@example.com", Handles.normalizeAddress("Liz@Example.com"))
        assertNull(Handles.normalizeAddress("liz"))
        assertNull(Handles.normalizeAddress("@example.com"))
        assertNull(Handles.normalizeAddress(""))
    }
}
