package org.dlang.liveimap.ui.index

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
