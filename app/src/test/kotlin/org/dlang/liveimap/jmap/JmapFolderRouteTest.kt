package org.dlang.liveimap.jmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JmapFolderRouteTest {
    @Test
    fun noneIsNotTheJmapRoute() {
        val offer = folderOfferOrImap { JmapOffer.None }
        assertSame(JmapOffer.None, offer)
        assertFalse(jmapFolderRoute(offer, "user"))
    }

    @Test
    fun certificateChangedFromTheProbeIsNone() {
        val offer = folderOfferOrImap { throw JmapFailure("certificate changed") }
        assertSame(JmapOffer.None, offer)
        assertFalse(jmapFolderRoute(offer, "user"))
    }

    @Test
    fun mailWithUserIsTheJmapRoute() {
        val mail = JmapOffer.Mail(folderRouteSession())
        val offer = folderOfferOrImap { mail }
        assertSame(mail, offer)
        assertTrue(jmapFolderRoute(offer, "user"))
    }

    @Test
    fun mailWithBlankUsernameIsNotTheJmapRoute() {
        val mail = JmapOffer.Mail(folderRouteSession())
        val offer = folderOfferOrImap { mail }
        assertSame(mail, offer)
        assertFalse(jmapFolderRoute(offer, ""))
    }
}

private fun folderRouteSession(): JmapSession = JmapSession(
    username = "user",
    apiUrl = "https://example.com/jmap/",
    downloadUrl = "https://example.com/download/{accountId}/{blobId}/{name}",
    uploadUrl = "https://example.com/upload/{accountId}/",
    eventSourceUrl = "https://example.com/event/?types={types}",
    state = "s1",
    capabilityIds = setOf(JMAP_MAIL),
    primaryMailAccountId = "A1",
)
