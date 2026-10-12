package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnvelopeSenderTest {
    private fun msg(headers: String) = (headers + "\r\n\r\nbody\r\n").toByteArray()

    @Test fun plusAliasOfAccountIsUsed() =
        assertEquals("dlang+lists@lang.hm", envelopeSender("David <dlang+lists@lang.hm>", "dlang@lang.hm", emptyList()))

    @Test fun accountAddressGivesDefault() = assertEquals("", envelopeSender("DLang@lang.hm", "dlang@lang.hm", emptyList()))

    @Test fun listedAltAddressIsUsed() =
        assertEquals("david@example.org", envelopeSender("david@example.org", "dlang@lang.hm", listOf("david@example.org")))

    @Test fun foreignFromFallsBackToAccount() = assertEquals("", envelopeSender("someone@else.com", "dlang@lang.hm", emptyList()))

    @Test fun fromHeaderUnfolded() =
        assertEquals("a+x@b.c", headerFromAddress(msg("Subject: s\r\nFrom: \"A\"\r\n <a+x@b.c>\r\nTo: t@b.c")))

    @Test fun resentPrefersResentFrom() =
        assertEquals("me@b.c", headerFromAddress(msg("Resent-From: me@b.c\r\nResent-To: x@y.z\r\nFrom: orig@o.c")))

    @Test fun resentWithoutResentFromUsesAccount() =
        assertEquals("", headerFromAddress(msg("Resent-To: x@y.z\r\nFrom: orig@o.c")))

    @Test fun sentStatusKeepsDsnNote() {
        assertEquals("Sent\nno DSN", sentStatusText("Sent", "no DSN"))
        assertEquals("no DSN", sentStatusText(null, "no DSN"))
        assertNull(sentStatusText(null, null))
    }
}

class EnvelopeAddressOkTest {
    @org.junit.Test fun plainAndPlusOk() {
        org.junit.Assert.assertTrue(envelopeAddressOk("a+b@example.org"))
        org.junit.Assert.assertTrue(envelopeAddressOk("\"john doe\"@example.org"))
        org.junit.Assert.assertTrue(envelopeAddressOk("\"a\\\"b c\"@example.org"))
    }
    @org.junit.Test fun injectionRefused() {
        org.junit.Assert.assertFalse(envelopeAddressOk("a@x.org\r\nRCPT TO:<evil@x.org>"))
        org.junit.Assert.assertFalse(envelopeAddressOk("a@x.org\nDATA"))
        org.junit.Assert.assertFalse(envelopeAddressOk("a@x.org> NOTIFY=NEVER"))
        org.junit.Assert.assertFalse(envelopeAddressOk("a b@x.org"))
        org.junit.Assert.assertFalse(envelopeAddressOk("a\u007f@x.org"))
        org.junit.Assert.assertFalse(envelopeAddressOk("\"open@x.org"))
        org.junit.Assert.assertFalse(envelopeAddressOk(""))
    }
    @org.junit.Test fun badFromFallsBackToAccount() {
        org.junit.Assert.assertEquals("", envelopeSender("me+x@example.org\r\nRSET", "me@example.org", emptyList()))
    }
}
