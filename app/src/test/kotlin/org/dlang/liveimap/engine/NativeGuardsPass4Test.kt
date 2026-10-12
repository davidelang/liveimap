package org.dlang.liveimap.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source checks for native fixes that the JVM tests cannot call directly (F110, F111, F112, F113). */
class NativeGuardsPass4Test {
    private val src = File("src/main/cpp/liveimap_jni.cpp").readText()

    private fun nativeBody(name: String): String {
        val start = src.indexOf("_$name(")
        assertTrue(name, start >= 0)
        val end = src.indexOf("\nextern \"C\"", start).let { if (it < 0) src.length else it }
        return src.substring(start, end)
    }

    @Test
    fun imapEntryPointsRefuseADeadConnection() {
        // F110: every native that hands session->imap to libetpan locks through lockImapSession
        for (name in listOf("nativeSelect", "nativeFetchIndex", "nativeStoreFlags", "nativeUidExpunge", "nativePeekPart",
                "nativeSearchCriterion", "nativeNoop", "nativeCopyThenDelete", "nativeFetchSortFieldsRange")) {
            val body = nativeBody(name)
            assertTrue(name, body.contains("lockImapSession(env, handle)"))
            assertFalse(name, body.contains("= lockSession(env, handle)"))
        }
        assertTrue(src.contains("if (session->imap == nullptr) {"))
    }

    @Test
    fun overlongUtf8IsRejected() {
        assertTrue(src.contains("(n == 3 && cp < 0x800) || (n == 4 && cp < 0x10000)")) // F111
    }

    @Test
    fun multipartInsideMessageHasNoExtraLevel() {
        // F112: RFC 3501 6.4.5 numbering in both the tree builder and the section lookup
        assertTrue(src.contains("inner->bd_type == MAILIMAP_BODY_MPART ? section : section + \".1\""))
        assertTrue(src.contains("next->bd_type == MAILIMAP_BODY_MPART ? here : here + \".1\""))
        assertFalse(src.contains("mimeFromBody(env, inner, section + \".1\""))
    }

    @Test
    fun rowsNeedAnEnvelope() {
        assertTrue(src.contains("if (row.uid != 0 && row.hasEnvelope) {")) // F113
    }
}
