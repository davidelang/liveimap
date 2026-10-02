package org.dlang.liveimap.engine

import org.dlang.liveimap.session.OpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGateTest {
    @Test
    fun missingIdleIsRejected() {
        val line = "IMAP4rev1 NAMESPACE UIDPLUS LITERAL+ CHILDREN UNSELECT SORT THREAD=REFERENCES"
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Rejected)
        assertTrue(result !is OpenResult.Connected)
        assertEquals(line, (result as OpenResult.Rejected).capabilities)
    }

    @Test
    fun cyrus22Connects() {
        val line = "IMAP4rev1 NAMESPACE UIDPLUS LITERAL+ CHILDREN UNSELECT SORT THREAD=REFERENCES IDLE"
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Connected)
        assertEquals("CopyThenDelete", moveKind(line))
        assertEquals("Plain", listKind(line, false))
        assertEquals("FullSelect", resyncKind(line))
        assertEquals("UidSearch", searchKind(line))
        assertEquals("UidSort FROM", sortKind(line))
        assertEquals("BodyPeek", previewKind(line))
        assertEquals("BodyPeek", fetchKind(line))
        assertEquals("Extended", listKind("$line LIST-EXTENDED", false))
        assertEquals("Extended", listKind("$line LIST-EXTENDED", true))
        assertEquals("Condstore", resyncKind("$line CONDSTORE"))
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
        val result = capabilityGate(line)
        assertTrue(result is OpenResult.Connected)
        assertEquals("Move", moveKind(line))
        assertEquals("ExtendedWithStatus", listKind(line, true))
        assertEquals("ExtendedWithMessages", listKind(line, false))
        assertEquals("Qresync", resyncKind(line))
        assertEquals("Esearch", searchKind(line))
        assertEquals("Esort DISPLAY", sortKind(line))
        assertEquals("Preview", previewKind(line))
        assertEquals("BinaryPeek", fetchKind(line))
    }
}
