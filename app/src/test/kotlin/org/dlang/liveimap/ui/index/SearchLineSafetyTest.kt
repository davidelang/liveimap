package org.dlang.liveimap.ui.index

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F118: no search argument can carry CR, LF or NUL onto the IMAP wire. */
class SearchLineSafetyTest {
    @Test
    fun builderRejectsBareCrAndNul() {
        assertNull(encodeAdvancedQuery(AdvancedCombiner.And, listOf(AdvancedStep(false, "Subject", "a\rb"))))
        assertNull(encodeAdvancedQuery(AdvancedCombiner.And, listOf(AdvancedStep(false, "Subject", "a\u0000b"))))
        assertNull(encodeAdvancedQuery(AdvancedCombiner.And, listOf(AdvancedStep(false, "Subject", "a\nb"))))
        assertNotNull(encodeAdvancedQuery(AdvancedCombiner.And, listOf(AdvancedStep(false, "Subject", "budget"))))
    }

    @Test
    fun nativeChecksEveryLeafAndTheTextSearch() {
        val cpp = File("src/main/cpp/liveimap_jni.cpp").readText()
        assertTrue(cpp.contains("bool lineSafeArgument(const char * text)"))
        assertTrue(cpp.contains("if (*p == 0xC0 && p[1] == 0x80) return false;"))
        val criterion = cpp.substringAfter("struct mailimap_search_key * criterionKey(JNIEnv * env, const char * kind, const char * argument) {")
            .substringBefore("if (sameKind(kind, \"Uid\"))")
        assertTrue(criterion.contains("!lineSafeArgument(argument)"))
        val text = cpp.substringAfter("struct mailimap_search_key * textKey(TextSlot slot, const char * argument) {").take(200)
        assertTrue(text.contains("lineSafeArgument(argument)"))
        val searchText = cpp.substringAfter("nativeSearchText(").take(700)
        assertTrue(searchText.contains("lineSafeArgument(q.c())"))
    }
}
