package org.dlang.liveimap.fakeimap

import org.dlang.liveimap.session.capabilityChoice
import org.junit.Assert.assertEquals
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
        val auth = Regex("\"auth\": true, \"cmd\": \"CAPABILITY\", \"items\": \\[\\{\"line\": \"([^\"]*)\"").find(json)
        val m = auth?.groupValues?.get(1) ?: Regex("\"line\": \"\\{TAG\\} OK \\[CAPABILITY ([^\\]]*)\\]").find(json)
            ?.let { return it.groupValues[1] } ?: return null
        val c = capLine.find(m) ?: return null
        return c.groupValues[1].ifEmpty { c.groupValues[2] }
    }

    @Test fun recordedCapabilitiesDriveConsistentChoices() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no generated personalities", files.isNotEmpty())
        val out = StringBuilder("server\tmove\tsearch\tsort\tresync\tpreview\tfetch\tidle\tfuzzy\n")
        var n = 0
        for (f in files) {
            val caps = postLoginCaps(f.readText()) ?: continue
            val tok = caps.uppercase().split(' ').toSet()
            val c = capabilityChoice(caps)
            val name = f.nameWithoutExtension
            assertEquals("$name MOVE", if ("MOVE" in tok) "Move" else "CopyThenDelete", c.move)
            assertEquals("$name ESEARCH", if ("ESEARCH" in tok) "Esearch" else "UidSearch", c.search)
            assertEquals("$name IDLE", "IDLE" in tok, c.idle)
            assertEquals("$name FUZZY", "SEARCH=FUZZY" in tok, c.searchFuzzy)
            assertEquals("$name UIDPLUS", "UIDPLUS" in tok, c.uidPlus)
            out.append("$name\t${c.move}\t${c.search}\t${c.sort}\t${c.resync}\t${c.preview}\t${c.fetch}\t${c.idle}\t${c.searchFuzzy}\n")
            n++
        }
        assumeTrue("no personality had a post-login CAPABILITY", n > 0)
        File("build/personality-choices.tsv").writeText(out.toString())
    }
}
