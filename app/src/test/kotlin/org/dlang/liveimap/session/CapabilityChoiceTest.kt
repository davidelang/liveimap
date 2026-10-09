package org.dlang.liveimap.session

import org.dlang.liveimap.engine.capabilityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityChoiceTest {
    @Test
    fun imap4rev1Only() {
        val choice = capabilityChoice("IMAP4rev1")
        assertEquals("CopyThenDelete", choice.move)
        assertEquals("Plain", choice.listUnread)
        assertEquals("Plain", choice.listRead)
        assertEquals("FullSelect", choice.resync)
        assertEquals("UidSearch", choice.search)
        assertEquals("UidSort", choice.sort)
        assertEquals("FROM", choice.fromKey)
        assertEquals("TO", choice.toKey)
        assertEquals("SUBJECT", choice.subjectKey)
        assertEquals("BodyPeek", choice.preview)
        assertEquals("BodyPeek", choice.fetch)
        assertFalse(choice.serverSort)
        assertFalse(choice.namespace)
        assertFalse(choice.uidPlus)
        assertFalse(choice.idle)
        assertFalse(choice.threadReferences)
        assertFalse(choice.threadOrderedSubject)
        assertTrue(capabilityGate("IMAP4rev1") is OpenResult.Connected)
    }

    @Test
    fun listExtended() {
        val choice = capabilityChoice("IMAP4rev1 LIST-EXTENDED")
        assertEquals("Extended", choice.listUnread)
        assertEquals("Extended", choice.listRead)
    }

    @Test
    fun listExtendedWithStatus() {
        val choice = capabilityChoice("IMAP4rev1 LIST-EXTENDED LIST-STATUS")
        assertEquals("ExtendedWithStatus", choice.listUnread)
        assertEquals("ExtendedWithMessages", choice.listRead)
    }

    @Test
    fun condstore() {
        assertEquals("Condstore", capabilityChoice("IMAP4rev1 CONDSTORE").resync)
    }

    @Test
    fun qresync() {
        assertEquals("Qresync", capabilityChoice("IMAP4rev1 QRESYNC").resync)
    }

    @Test
    fun sortAlone() {
        val choice = capabilityChoice("IMAP4rev1 SORT")
        assertTrue(choice.serverSort)
        assertEquals("UidSort", choice.sort)
        assertEquals("FROM", choice.fromKey)
    }

    @Test
    fun sortDisplay() {
        val choice = capabilityChoice("IMAP4rev1 SORT ESORT SORT=DISPLAY")
        assertEquals("Esort", choice.sort)
        assertEquals("DISPLAYFROM", choice.fromKey)
        assertEquals("DISPLAYTO", choice.toKey)
        assertEquals("SUBJECT", choice.subjectKey)
    }

    @Test
    fun threadOrderedSubject() {
        val choice = capabilityChoice("IMAP4rev1 THREAD=ORDEREDSUBJECT")
        assertTrue(choice.threadOrderedSubject)
        assertFalse(choice.threadReferences)
    }

    @Test
    fun fullLine() {
        val line = "IMAP4rev1 NAMESPACE UIDPLUS IDLE UNSELECT SORT THREAD=REFERENCES MOVE ESEARCH " +
            "LIST-EXTENDED LIST-STATUS ESORT SORT=DISPLAY PREVIEW BINARY QRESYNC CONDSTORE"
        val choice = capabilityChoice(line)
        assertEquals("Move", choice.move)
        assertEquals("ExtendedWithStatus", choice.listUnread)
        assertEquals("ExtendedWithMessages", choice.listRead)
        assertEquals("Qresync", choice.resync)
        assertEquals("Esearch", choice.search)
        assertEquals("Esort", choice.sort)
        assertEquals("DISPLAYFROM", choice.fromKey)
        assertEquals("DISPLAYTO", choice.toKey)
        assertEquals("SUBJECT", choice.subjectKey)
        assertEquals("Preview", choice.preview)
        assertEquals("BinaryPeek", choice.fetch)
        assertTrue(choice.serverSort)
        assertTrue(choice.namespace)
        assertTrue(choice.uidPlus)
        assertTrue(choice.idle)
        assertTrue(choice.threadReferences)
        assertFalse(choice.threadOrderedSubject)
    }

    @Test
    fun withoutSortAndEsearch() {
        val full = "IMAP4rev1 NAMESPACE UIDPLUS IDLE UNSELECT SORT THREAD=REFERENCES MOVE ESEARCH " +
            "LIST-EXTENDED LIST-STATUS ESORT SORT=DISPLAY PREVIEW BINARY QRESYNC CONDSTORE"
        val line = Capabilities.parse(full).without(listOf("sort", "esearch")).names.joinToString(" ")
        val choice = capabilityChoice(line)
        assertFalse(choice.serverSort)
        assertEquals("UidSearch", choice.search)
        assertEquals("Move", choice.move)
    }

    @Test
    fun emptyRejectedAndImap4rev2Connects() {
        assertTrue(capabilityGate("") is OpenResult.Rejected)
        assertTrue(capabilityGate("IMAP4rev2") is OpenResult.Connected)
    }
}
