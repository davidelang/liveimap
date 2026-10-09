package org.dlang.liveimap.debug.acceptance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceFilterTest {
    @Test
    fun fullKeepsBothCases() {
        val cases = listOf(newer, mid)
        assertEquals(
            cases,
            selectCases(cases, AcceptanceSuite.Full, emptyList(), 0, ""),
        )
    }

    @Test
    fun thisTurnKeepsOnlyTheNewerStamp() {
        val selected = selectCases(
            listOf(newer, mid, older),
            AcceptanceSuite.ThisTurn,
            listOf("recent-20261008-1348", "recent-20261008-1918"),
            2,
            "",
        )
        assertEquals(listOf(newer), selected)
    }

    @Test
    fun lastTwoKeepsTheMidStampAndDropsTheOlderOne() {
        val selected = selectCases(
            listOf(newer, mid, older),
            AcceptanceSuite.LastN,
            listOf("recent-20261008-1918", "recent-20261008-1348", "recent-20261007-1908"),
            2,
            "",
        )
        assertEquals(listOf(newer, mid), selected)
        assertFalse(selected.any { it.name == older.name })
    }

    @Test
    fun featureFoldersDropsSearch() {
        val selected = selectCases(
            listOf(newer, searchCase),
            AcceptanceSuite.Full,
            emptyList(),
            1,
            "folders",
        )
        assertEquals(listOf(newer), selected)
    }

    @Test
    fun emptyRecentPlansSelectsNothingForThisTurn() {
        val selected = selectCases(
            listOf(newer, mid),
            AcceptanceSuite.ThisTurn,
            emptyList(),
            2,
            "",
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun allowHostRequiresTheHostAndDecimalPort() {
        val hosts = setOf("lab.example:10143")
        assertTrue(allowHost(hosts, "lab.example", 10143))
        assertFalse(allowHost(emptySet(), "lab.example", 10143))
        assertFalse(allowHost(setOf("lab.example"), "lab.example", 10143))
        assertFalse(allowHost(hosts, "lab.example", 10144))
        assertFalse(allowHost(setOf("other.example:10143"), "lab.example", 10143))
    }

    @Test
    fun allowUserIsTheFiveLabNames() {
        assertTrue(allowUser("liveimap"))
        assertTrue(allowUser("liveimap2"))
        assertTrue(allowUser("liveimap-empty"))
        assertTrue(allowUser("liveimap-utf8"))
        assertTrue(allowUser("liveimap-scale"))
        assertFalse(allowUser("ada"))
    }

    @Test
    fun allowEntryChecksMarkThenHostThenRealUser() {
        val hosts = setOf("lab.example:10143")
        assertEquals(
            "unmarked server",
            allowEntry(false, false, hosts, "lab.example", 10143, "liveimap"),
        )
        assertEquals(
            "host not allowlisted",
            allowEntry(true, true, emptySet(), "lab.example", 10143, "liveimap"),
        )
        assertEquals(
            "user not allowlisted",
            allowEntry(true, true, hosts, "lab.example", 10143, "ada"),
        )
        assertNull(allowEntry(true, false, hosts, "lab.example", 10143, "ada"))
    }

    @Test
    fun scaleSkipComesBeforeReadOnly() {
        val scale = AcceptanceCase("scale", "folders", listOf("case-20261008-1918"), true, true)
        val outcome = caseOutcome(readOnly = true, scaleSeeded = false, scale)
        assertEquals(CaseStep.Skip, outcome.step)
        assertEquals("scale content not seeded", outcome.reason)
    }

    @Test
    fun writingCaseSkipsOnAReadOnlyServer() {
        val writing = AcceptanceCase("write", "folders", listOf("case-20261008-1918"), true, false)
        val outcome = caseOutcome(readOnly = true, scaleSeeded = false, writing)
        assertEquals(CaseStep.Skip, outcome.step)
        assertEquals("read-only server", outcome.reason)
    }

    @Test
    fun otherCasesRun() {
        val reading = AcceptanceCase("read", "folders", listOf("case-20261008-1918"), false, false)
        val outcome = caseOutcome(readOnly = true, scaleSeeded = false, reading)
        assertEquals(CaseStep.Run, outcome.step)
        assertEquals("", outcome.reason)
    }

    @Test
    fun writeReportWritesJsonAndTextWithoutAPasswordField() {
        val dir = File(System.getProperty("java.io.tmpdir"), "acceptance-filter-" + System.nanoTime())
        val report = AcceptanceReport(
            AcceptanceSuite.Full,
            listOf(
                ReportRow(
                    name = "list folders",
                    feature = "folders",
                    plans = listOf("case-20261008-1918"),
                    result = "Run",
                    reason = "",
                ),
                ReportRow(
                    name = "say \"hi\"",
                    feature = "search\\x",
                    plans = listOf("a\\b\"c"),
                    result = "Skip",
                    reason = "read-only server",
                ),
            ),
        )
        assertFalse(dir.exists())
        writeReport(dir, report, "20261008-1918")
        val jsonFile = File(dir, "acceptance-20261008-1918-Full.json")
        val textFile = File(dir, "acceptance-20261008-1918-Full.txt")
        assertTrue(jsonFile.isFile)
        assertTrue(textFile.isFile)
        val json = jsonFile.readText()
        assertTrue(json.contains("\"suite\":\"Full\""))
        assertTrue(json.contains("\"name\":\"list folders\""))
        assertTrue(json.contains("\"feature\":\"folders\""))
        assertTrue(json.contains("\"plans\":[\"case-20261008-1918\"]"))
        assertTrue(json.contains("\"result\":\"Run\""))
        assertTrue(json.contains("\"reason\":\"\""))
        assertTrue(json.contains("\"name\":\"say \\\"hi\\\"\""))
        assertTrue(json.contains("\"feature\":\"search\\\\x\""))
        assertTrue(json.contains("\"plans\":[\"a\\\\b\\\"c\"]"))
        assertFalse(json.contains("password"))
        val text = textFile.readText()
        assertEquals(
            "list folders\tRun\t\nsay \"hi\"\tSkip\tread-only server\n",
            text,
        )
        assertFalse(text.contains("password"))
        jsonFile.delete()
        textFile.delete()
        dir.delete()
    }

    private val newer = AcceptanceCase(
        "newer",
        "folders",
        listOf("case-20261008-1918"),
        false,
        false,
    )

    private val mid = AcceptanceCase(
        "mid",
        "folders",
        listOf("case-20261008-1348"),
        false,
        false,
    )

    private val older = AcceptanceCase(
        "older",
        "folders",
        listOf("case-20261007-1908"),
        false,
        false,
    )

    private val searchCase = AcceptanceCase(
        "search-one",
        "search",
        listOf("case-20261008-1918"),
        false,
        false,
    )
}
