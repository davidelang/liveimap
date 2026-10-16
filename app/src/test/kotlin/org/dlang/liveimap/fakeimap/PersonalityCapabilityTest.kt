package org.dlang.liveimap.fakeimap

import org.dlang.liveimap.session.Capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * JVM-side run over the recorded server personalities (tools/test-servers/generated/personalities, from
 * personality.py): every post-login CAPABILITY a real lab server sent goes through the app's capability model,
 * and the derived choices must agree with the raw tokens. Writes build/personality-choices.tsv for review.
 * Skipped when the personalities are not generated (fresh checkout).
 */
class PersonalityCapabilityTest {
    private val dir = File("../tools/test-servers/generated/personalities")
    private val capLine = Regex("^\\* CAPABILITY (.*)$|\\[CAPABILITY ([^\\]]*)\\]")

    private fun postLoginCaps(json: String): String? {
        // The authenticated CAPABILITY reply, else the [CAPABILITY ...] code of the LOGIN/AUTHENTICATE OK.
        val auth = Regex("\"auth\":\\s*true,\\s*\"cmd\":\\s*\"CAPABILITY\",\\s*\"items\":\\s*\\[\\s*\\{\\s*\"line\":\\s*\"([^\"]*)\"").find(json)
        val m = auth?.groupValues?.get(1) ?: Regex("\"line\":\\s*\"\\{TAG\\} OK \\[CAPABILITY ([^\\]]*)\\]").find(json)
            ?.let { return it.groupValues[1] } ?: return null
        val c = capLine.find(m) ?: return null
        return c.groupValues[1].ifEmpty { c.groupValues[2] }
    }

    @Test fun parseReportsMoveSearchAndFlags() {
        val full = Capabilities.parse("IMAP4rev1 MOVE ESEARCH IDLE SEARCH=FUZZY UIDPLUS")
        assertEquals("Move", full.moveKind())
        assertEquals("Esearch", full.searchKind())
        assertTrue(full.idle)
        assertTrue(full.searchFuzzy)
        assertTrue(full.uidPlus)
        val bare = Capabilities.parse("IMAP4rev1")
        assertEquals("CopyThenDelete", bare.moveKind())
        assertEquals("UidSearch", bare.searchKind())
        assertFalse(bare.idle)
        assertFalse(bare.searchFuzzy)
        assertFalse(bare.uidPlus)
    }

    @Test fun wrapperFilesAreAbsent() {
        val rels = listOf(
            "src/main/kotlin/org/dlang/liveimap/session/CapabilityChoice.kt",
            "src/test/kotlin/org/dlang/liveimap/session/CapabilityChoiceTest.kt",
            "src/main/kotlin/org/dlang/liveimap/jmap/JmapCapabilityChoice.kt",
            "src/test/kotlin/org/dlang/liveimap/jmap/JmapCapabilityChoiceTest.kt",
        )
        for (rel in rels) {
            assertFalse(rel, File(rel).exists() || File("app/$rel").exists())
        }
    }

    @Test fun recordedCapabilitiesDriveConsistentChoices() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no generated personalities", files.isNotEmpty())
        val out = StringBuilder("server\tmove\tsearch\tsort\tresync\tpreview\tfetch\tidle\tfuzzy\n")
        var n = 0
        val missing = mutableListOf<String>()
        for (f in files) {
            val caps = postLoginCaps(f.readText()) ?: run { missing += f.name; null } ?: continue
            val tok = caps.uppercase().split(' ').toSet()
            val c = Capabilities.parse(caps)
            val name = f.nameWithoutExtension
            assertEquals("$name MOVE", if ("MOVE" in tok) "Move" else "CopyThenDelete", c.moveKind())
            assertEquals("$name ESEARCH", if ("ESEARCH" in tok) "Esearch" else "UidSearch", c.searchKind())
            assertEquals("$name IDLE", "IDLE" in tok, c.idle)
            assertEquals("$name FUZZY", "SEARCH=FUZZY" in tok, c.searchFuzzy)
            assertEquals("$name UIDPLUS", "UIDPLUS" in tok, c.uidPlus)
            out.append("$name\t${c.moveKind()}\t${c.searchKind()}\t${c.sortKind()}\t${c.resyncKind()}\t${c.previewKind()}\t${c.fetchKind()}\t${c.idle}\t${c.searchFuzzy}\n")
            n++
        }
        assertEquals("personalities without a post-login CAPABILITY", emptyList<String>(), missing)
        File("build/personality-choices.tsv").writeText(out.toString())
    }
}
