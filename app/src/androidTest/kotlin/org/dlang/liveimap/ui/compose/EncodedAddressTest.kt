package org.dlang.liveimap.ui.compose

class EncodedAddressTest {
    private fun parsed(header: String) = parseRfc822("$header\r\nSubject: x\r\n\r\nbody".toByteArray(), "ISO-8859-1")

    @org.junit.Test fun encodedCrLfStaysOutOfTheAddrSpec() {
        val m = parsed("Reply-To: <=?utf-8?q?me@example.com=0D=0ARCPT_TO:=3Cspy@evil=3E?=>")
        val rcpts = envelopeRecipients(addresses(m, "Reply-To", "ISO-8859-1"), emptyList(), emptyList())
        org.junit.Assert.assertTrue(rcpts.none { it.contains('\r') || it.contains('\n') })
        // The encoded word stays as sent: one bogus but harmless recipient, never a second SMTP command.
        org.junit.Assert.assertEquals(1, rcpts.size)
        org.junit.Assert.assertTrue(rcpts[0], rcpts[0].startsWith("=?utf-8?q?"))
    }

    @org.junit.Test fun encodedCommaDoesNotSplit() {
        val m = parsed("To: =?utf-8?q?Ann=2C_spy=40evil.example?= <ann@example.org>")
        val list = addresses(m, "To", "ISO-8859-1")
        org.junit.Assert.assertEquals(1, list.size)
        org.junit.Assert.assertEquals("ann@example.org", addrSpec(list[0]))
        org.junit.Assert.assertTrue(list[0], list[0].startsWith("\"Ann, spy@evil.example\""))
    }

    @org.junit.Test fun displayNameDecoded() {
        val m = parsed("From: =?utf-8?q?Caf=C3=A9?= <c@example.org>")
        org.junit.Assert.assertEquals("Café <c@example.org>", addresses(m, "From", "ISO-8859-1")[0])
    }
}
