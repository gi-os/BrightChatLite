package com.gios.brightchatlite

import com.gios.brightchatlite.api.ServerSetup
import com.gios.brightchatlite.api.SetupQr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SetupQrTest {
    @Test fun blueBubblesServerArray() {
        assertEquals(
            ServerSetup("https://abc.ngrok.io", "s3cret"),
            SetupQr.parse("""["s3cret","https://abc.ngrok.io/","fcm-project",{"x":1}]"""),
        )
    }

    @Test fun jsonObjectAndUrlForms() {
        assertEquals(ServerSetup("http://100.64.0.2:1234", "pw"), SetupQr.parse("""{"url":"100.64.0.2:1234","password":"pw"}"""))
        assertEquals(ServerSetup("http://mac.tail1234.ts.net:1234", "a b&c"), SetupQr.parse("http://mac.tail1234.ts.net:1234/?password=a%20b%26c"))
    }

    @Test fun rejectsOtherCodes() {
        assertNull(SetupQr.parse("otpauth://totp/x?secret=ABC"))
        assertNull(SetupQr.parse("https://example.com/"))
        assertNull(SetupQr.parse("""["only-password"]"""))
        assertNull(SetupQr.parse("hello"))
    }

    @Test fun normalizesTypedUrls() {
        assertEquals("http://192.168.1.20:1234", SetupQr.normalizeUrl(" 192.168.1.20:1234/ "))
        assertEquals("https://bb.example.com", SetupQr.normalizeUrl("HTTPS://bb.example.com/api/v1/"))
        assertNull(SetupQr.normalizeUrl("ftp://x"))
        assertNull(SetupQr.normalizeUrl("has space"))
        assertNull(SetupQr.normalizeUrl(""))
    }
}
