package com.gios.brightchatlite

import com.gios.brightchatlite.api.BlueBubblesJson
import com.gios.brightchatlite.model.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueBubblesJsonTest {

    private val sweep = """
    {"status":200,"message":"Success","data":[
      {"guid":"m5","text":"newest in group","dateCreated":500,"isFromMe":false,"handle":{"address":"+13152122695"},
       "dateRead":null,"itemType":0,"attachments":[],
       "chats":[{"guid":"iMessage;+;chatB","displayName":"Fam","style":43,"participants":[{"address":"+13152122695"},{"address":"a@b.c"}]}]},
      {"guid":"m4","text":null,"dateCreated":400,"isFromMe":false,"handle":{"address":"+19998887777"},"dateRead":0,
       "attachments":[{"guid":"att1","mimeType":"image/jpeg","transferName":"IMG_1.jpg","width":100,"height":50}],
       "chats":[{"guid":"iMessage;-;+19998887777","displayName":null,"style":45,"chatIdentifier":"+19998887777","participants":[]}]},
      {"guid":"m3","text":"old room","dateCreated":300,"isFromMe":true,"dateRead":0,"attachments":[],
       "chats":[{"guid":"iMessage;+;chatA","displayName":"Fam","style":43,"participants":[{"address":"a@b.c"},{"address":"+13152122695"}]}]},
      {"guid":"r1","text":"Loved “hi”","dateCreated":250,"isFromMe":false,"handle":{"address":"+15550001111"},"dateRead":1,
       "associatedMessageGuid":"p:0/m0","associatedMessageType":"love","attachments":[],
       "chats":[{"guid":"iMessage;-;+15550001111","displayName":"","style":45,"chatIdentifier":"+15550001111"}]},
      {"guid":"m0","text":"hi","dateCreated":200,"isFromMe":true,"attachments":[],
       "chats":[{"guid":"iMessage;-;+15550001111","displayName":"","style":45,"chatIdentifier":"+15550001111"}]}
    ]}
    """.trimIndent()

    @Test fun sweepBuildsNewestFirstList() {
        val list = BlueBubblesJson.conversationsFromSweep(BlueBubblesJson.dataArray(sweep))
        assertEquals(3, list.size)
        val fam = list[0]
        assertEquals("Fam", fam.displayName)
        assertTrue(fam.isGroup)
        // Forked rooms collapse; the live room (newest real message) is the send target.
        assertEquals(listOf("iMessage;+;chatB", "iMessage;+;chatA"), fam.guids)
        assertEquals("iMessage;+;chatB", fam.guid)
        assertTrue(fam.unread)
        assertEquals("newest in group", fam.lastText)
        assertEquals("+13152122695", fam.lastSender)

        val photo = list[1]
        assertEquals("[Photo]", photo.lastText)
        assertEquals(listOf("+19998887777"), photo.participants)
        assertEquals("", photo.displayName)
        assertTrue(photo.unread)

        val liz = list[2]
        assertEquals(250, liz.lastDate)
        assertEquals("hi", liz.lastText)
        assertFalse(liz.unread)
    }

    @Test fun parsesMessageFields() {
        val m = BlueBubblesJson.parseMessage(BlueBubblesJson.dataArray(sweep)[1])
        assertEquals(ChatMessage.ATTACHMENT_PLACEHOLDER, m.text)
        assertNull(m.bodyText)
        assertEquals("att1", m.images.single().guid)
        assertEquals("iMessage;-;+19998887777", m.chatGuid)
        val r = BlueBubblesJson.parseMessage(BlueBubblesJson.dataArray(sweep)[3])
        assertTrue(r.isReaction)
        assertEquals("m0", r.reactionTargetGuid)
    }

    @Test fun dropsRichLinkPayloads() {
        val o = BlueBubblesJson.parse(
            """{"guid":"x","text":"https://example.com","attachments":[{"guid":"p","transferName":"abc.pluginPayloadAttachment"}]}""",
        )!!
        val m = BlueBubblesJson.parseMessage(o, "chat")
        assertTrue(m.attachments.isEmpty())
        assertEquals("https://example.com", m.text)
        assertEquals("chat", m.chatGuid)
    }

    @Test fun contactsFlatten() {
        val data = BlueBubblesJson.dataArray(
            """{"data":[
              {"displayName":"Liz Lemon","phoneNumbers":[{"address":"+1 (315) 212-2695"}],"emails":[{"address":"liz@nbc.com"}]},
              {"displayName":null,"firstName":"Jack","lastName":"Donaghy","phoneNumbers":[{"address":"555-0100"}]},
              {"displayName":"","firstName":null,"lastName":null,"phoneNumbers":[{"address":"1"}]}
            ]}""",
        )
        val pairs = BlueBubblesJson.contacts(data)
        assertEquals(listOf("+1 (315) 212-2695" to "Liz Lemon", "liz@nbc.com" to "Liz Lemon", "555-0100" to "Jack Donaghy"), pairs)
    }

    @Test fun privateApiNeedsHelper() {
        assertTrue(BlueBubblesJson.privateApiReady(BlueBubblesJson.parse("""{"private_api":true,"helper_connected":true}""")))
        assertFalse(BlueBubblesJson.privateApiReady(BlueBubblesJson.parse("""{"private_api":true,"helper_connected":false}""")))
        assertFalse(BlueBubblesJson.privateApiReady(null))
    }
}
