package org.dlang.liveimap.engine

import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.SelectResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionKeeperTest {
    @Test
    fun reconnectReportsArrivalsHiddenByExpunges() {
        val before = SelectResult(7, 100, 50)
        assertEquals(
            listOf(MailboxChange.Expunge(48), MailboxChange.Exists(48), MailboxChange.Reconnected),
            reconnectChanges(before, SelectResult(7, 103, 48)),
        )
        assertEquals(
            listOf(MailboxChange.Expunge(50), MailboxChange.Exists(50), MailboxChange.Reconnected),
            reconnectChanges(before, SelectResult(7, 102, 50)),
        )
        assertEquals(listOf(MailboxChange.Reconnected), reconnectChanges(before, before))
        assertEquals(
            listOf(MailboxChange.Expunge(53), MailboxChange.Exists(53), MailboxChange.Reconnected),
            reconnectChanges(before, SelectResult(7, 105, 53)),
        )
        assertEquals(
            listOf(MailboxChange.Exists(53), MailboxChange.Reconnected),
            reconnectChanges(before, SelectResult(7, 103, 53)),
        )
        assertEquals(
            listOf(MailboxChange.Expunge(40), MailboxChange.Reconnected),
            reconnectChanges(before, SelectResult(7, 100, 40)),
        )
        assertEquals(
            listOf(MailboxChange.UidValidityReset),
            reconnectChanges(before, SelectResult(8, 1, 0)),
        )
    }
}
