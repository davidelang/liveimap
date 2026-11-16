package org.dlang.liveimap.engine

import org.junit.Ignore
import org.junit.Test

/** F101/F115 is covered by LabSafetyTest. This class no longer reads session source. */
class CloseOnlyOnLeaveTest {
    @Ignore("LabSafetyTest shows CLOSE only on leave")
    @Test
    fun onlyTheLeavePathCloses() {
    }
}
