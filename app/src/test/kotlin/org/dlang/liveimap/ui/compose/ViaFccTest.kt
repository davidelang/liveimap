package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/** F92(c)/F135: routed mail is filed in the routing account's own Sent folder. */
class ViaFccTest {
    @Test fun routedMailUsesTheOtherAccountsSent() {
        assertEquals("Sent Items", viaFccMailbox("INBOX.Sent", "INBOX.Sent", "Sent Items"))
        assertEquals("Archive/Out", viaFccMailbox("INBOX.Sent", "Archive/Out", "Sent Items"))
        assertEquals("", viaFccMailbox("INBOX.Sent", "", "Sent Items")) // rule says no Fcc
    }
}
