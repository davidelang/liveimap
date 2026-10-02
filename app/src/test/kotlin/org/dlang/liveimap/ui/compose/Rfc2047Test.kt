package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Rfc2047Test {
    @Test
    fun base64Utf8Word() {
        assertEquals(
            "You're Invited ✉️",
            decodeHeaderWords("=?UTF-8?B?WW91J3JlIEludml0ZWQg4pyJ77iP?="),
        )
    }

    @Test
    fun base64Utf8WordAnyCase() {
        assertEquals(
            "You're Invited ✉️",
            decodeHeaderWords("=?utf-8?b?WW91J3JlIEludml0ZWQg4pyJ77iP?="),
        )
    }

    @Test
    fun quotedWordMapsUnderscoreToSpace() {
        assertEquals("Hello World", decodeHeaderWords("=?utf-8?Q?Hello_World?="))
    }

    @Test
    fun adjacentEncodedWordsJoin() {
        assertEquals("ab", decodeHeaderWords("=?utf-8?Q?a?= =?utf-8?Q?b?="))
    }

    @Test
    fun plainSubjectStays() {
        assertEquals("plain subject", decodeHeaderWords("plain subject"))
    }

    @Test
    fun badBase64Stays() {
        assertEquals("=?UTF-8?B?****?=", decodeHeaderWords("=?UTF-8?B?****?="))
    }

    @Test
    fun unknownCharsetStays() {
        assertEquals(
            "=?no-such-charset?B?QQ==?=",
            decodeHeaderWords("=?no-such-charset?B?QQ==?="),
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
            message = parseRfc822(raw),
            accountEmail = "me@example.com",
            peekedText = "peeked",
        )
        assertEquals("Re: ab", draft.subject)
        assertTrue(draft.body.contains("ab"))
    }
}
