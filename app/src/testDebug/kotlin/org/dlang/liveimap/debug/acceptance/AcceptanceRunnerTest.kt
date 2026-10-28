package org.dlang.liveimap.debug.acceptance

import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceRunnerTest {
    @Test fun reportCarriesHeaderAndDurations() {
        val dir = kotlin.io.path.createTempDirectory("acc").toFile()
        val report = AcceptanceReport(AcceptanceSuite.Full, listOf(ReportRow("a", "f", listOf("p"), "pass", "", 12)), listOf("build" to "v1"))
        writeReport(dir, report, "20261012-142000")
        val json = java.io.File(dir, "acceptance-20261012-142000-Full.json").readText()
        assertTrue(json.startsWith("{\"build\":\"v1\","))
        assertTrue(json.contains("\"ms\":12"))
        assertTrue(java.io.File(dir, "acceptance-20261012-142000-Full.txt").readText().startsWith("# build: v1\n"))
        dir.deleteRecursively()
    }
}
