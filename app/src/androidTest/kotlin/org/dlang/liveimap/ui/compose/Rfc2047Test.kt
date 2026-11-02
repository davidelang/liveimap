package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Rfc2047Test {
    @Test
    fun base64Utf8Word() {
        assertEquals(
            "You're Invited ✉️",
            decodeHeaderWords("=?UTF-8?B?WW91J3JlIEludml0ZWQg4pyJ77iP?=", "ISO-8859-1"),
        )
    }

    @Test
    fun base64Utf8WordAnyCase() {
        assertEquals(
            "You're Invited ✉️",
            decodeHeaderWords("=?utf-8?b?WW91J3JlIEludml0ZWQg4pyJ77iP?=", "ISO-8859-1"),
        )
    }

    @Test
    fun quotedWordMapsUnderscoreToSpace() {
        assertEquals("Hello World", decodeHeaderWords("=?utf-8?Q?Hello_World?=", "ISO-8859-1"))
    }

    @Test
    fun adjacentEncodedWordsJoin() {
        assertEquals("ab", decodeHeaderWords("=?utf-8?Q?a?= =?utf-8?Q?b?=", "ISO-8859-1"))
    }

    @Test
    fun plainSubjectStays() {
        assertEquals("plain subject", decodeHeaderWords("plain subject", "ISO-8859-1"))
    }

    @Test
    fun badBase64Stays() {
        assertEquals("=?UTF-8?B?****?=", decodeHeaderWords("=?UTF-8?B?****?=", "ISO-8859-1"))
    }

    @Test
    fun unknownCharsetStays() {
        assertEquals(
            "=?no-such-charset?B?QQ==?=",
            decodeHeaderWords("=?no-such-charset?B?QQ==?=", "ISO-8859-1"),
        )
    }

    @Test
    fun replyDraftDecodesSubjectAndFrom() {
        val raw = """
            From: =?utf-8?Q?a?= =?utf-8?Q?b?= <x@y.z>
            Subject: =?utf-8?Q?a?= =?utf-8?Q?b?=

            body
            """.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        val draft = replyDraft(
            replyAll = false,
            message = parseRfc822(raw, "ISO-8859-1"),
            accountEmail = "me@example.com",
            peekedText = "peeked",
            charset = "ISO-8859-1",
        )
        assertEquals("Re: ab", draft.subject)
        assertTrue(draft.body.contains("ab"))
    }
}
