package org.dlang.liveimap.engine

import org.dlang.liveimap.session.Capabilities
import org.dlang.liveimap.session.OpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGateTest {
    @Test
    fun missingIdleConnects() {
        val line = "IMAP4rev1 NAMESPACE UIDPLUS LITERAL+ CHILDREN UNSELECT SORT THREAD=REFERENCES"
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Connected)
        assertTrue(result !is OpenResult.Rejected)
    }

    @Test
    fun imap4rev1AloneConnects() {
        assertTrue(capabilityGate("IMAP4rev1") is OpenResult.Connected)
    }

    @Test
    fun imap4rev2AloneConnects() {
        assertTrue(capabilityGate("IMAP4rev2") is OpenResult.Connected)
    }

    @Test
    fun neitherImapRevisionIsRejected() {
        val line = "NAMESPACE UIDPLUS LITERAL+ CHILDREN UNSELECT SORT THREAD=REFERENCES IDLE"
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Rejected)
        assertTrue(result !is OpenResult.Connected)
        assertEquals(line, (result as OpenResult.Rejected).capabilities)
    }

    @Test
    fun missingUnselectConnects() {
        val line = "IMAP4rev1 NAMESPACE UIDPLUS LITERAL+ CHILDREN SORT THREAD=REFERENCES IDLE"
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Connected)
    }

    @Test
    fun cyrus22Connects() {
        val line = "IMAP4rev1 NAMESPACE UIDPLUS LITERAL+ CHILDREN UNSELECT SORT THREAD=REFERENCES IDLE"
        val caps = Capabilities.parse(line)
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Connected)
        assertEquals("CopyThenDelete", caps.moveKind())
        assertEquals("Plain", caps.listKind(false))
        assertEquals("FullSelect", caps.resyncKind())
        assertEquals("UidSearch", caps.searchKind())
        assertEquals("UidSort", caps.sortKind())
        assertEquals("BodyPeek", caps.previewKind())
        assertEquals("BodyPeek", caps.fetchKind())
        val extended = Capabilities.parse("$line LIST-EXTENDED")
        assertEquals("Extended", extended.listKind(false))
        assertEquals("Extended", extended.listKind(true))
        assertEquals("Condstore", Capabilities.parse("$line CONDSTORE").resyncKind())
    }

    @Test
    fun moonLineConnects() {
        val line = "ACL ANNOTATE-EXPERIMENT-1 APPENDLIMIT BINARY CATENATE CHILDREN COMPRESS=DEFLATE " +
            "CONDSTORE CREATE-SPECIAL-USE ESEARCH ESORT IDLE LIST-EXTENDED LIST-METADATA LIST-MYRIGHTS " +
            "LIST-STATUS LITERAL+ MAILBOX-REFERRALS METADATA MOVE MULTIAPPEND MULTISEARCH NAMESPACE " +
            "OBJECTID PARTIAL PREVIEW QRESYNC QUOTA QUOTA=RES-STORAGE QUOTA=RES-MESSAGE " +
            "QUOTA=RES-ANNOTATION-STORAGE QUOTA=RES-MAILBOX QUOTASET REPLACE RIGHTS=kxten SAVEDATE " +
            "SEARCH=FUZZY SEARCHRES SORT SORT=DISPLAY SORT=MODSEQ SORT=UID SPECIAL-USE STATUS=SIZE " +
            "THREAD=ORDEREDSUBJECT THREAD=REFERENCES THREAD=REFS UIDONLY UIDPLUS UNSELECT URL-PARTIAL " +
            "URLAUTH URLAUTH=BINARY WITHIN XLIST"
        val caps = Capabilities.parse(line)
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Connected)
        assertEquals("Move", caps.moveKind())
        assertEquals("ExtendedWithStatus", caps.listKind(true))
        assertEquals("ExtendedWithMessages", caps.listKind(false))
        assertEquals("Qresync", caps.resyncKind())
        assertEquals("Esearch", caps.searchKind())
        assertEquals("Esort", caps.sortKind())
        assertEquals("Preview", caps.previewKind())
        assertEquals("BinaryPeek", caps.fetchKind())
    }
}
