package org.dlang.liveimap.session

import org.dlang.liveimap.fakeimap.FakeimapPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Every post-login capability list from a pinned fakeimap personality goes through the
 * app's capability model, and the derived choices must agree with the raw tokens.
 * Writes build/personality-choices.tsv for review.
 *
 * Requires the pin to be locked and materialized (same [FakeimapPin.requireReady] check
 * as the loopback tests). There is no fallback to the old recording-format personalities
 * under tools/test-servers/generated/personalities.
 */
class PersonalityCapabilityTest {
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
        FakeimapPin.requireReady()
        val dir = FakeimapPin.personalities()
        val files = Files.newDirectoryStream(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") && !it.fileName.toString().endsWith(".gaps.json") }
                .sortedBy { it.fileName.toString() }
        }
        if (files.isEmpty()) {
            error("fakeimap pin: no personality JSON files in $dir")
        }
        val out = StringBuilder("server\tmove\tsearch\tsort\tresync\tpreview\tfetch\tidle\tfuzzy\n")
        var missing = mutableListOf<String>()
        for (f in files) {
            val text = String(Files.readAllBytes(f), StandardCharsets.UTF_8)
            val caps = postLoginCaps(text)
            if (caps == null) {
                missing += f.fileName.toString()
                continue
            }
            val tok = caps.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
            val c = Capabilities.parse(caps)
            val name = f.fileName.toString().removeSuffix(".json")
            assertEquals("$name MOVE", if ("MOVE" in tok) "Move" else "CopyThenDelete", c.moveKind())
            assertEquals("$name ESEARCH", if ("ESEARCH" in tok) "Esearch" else "UidSearch", c.searchKind())
            assertEquals("$name IDLE", "IDLE" in tok, c.idle)
            assertEquals("$name FUZZY", "SEARCH=FUZZY" in tok, c.searchFuzzy)
            assertEquals("$name UIDPLUS", "UIDPLUS" in tok, c.uidPlus)
            out.append("$name\t${c.moveKind()}\t${c.searchKind()}\t${c.sortKind()}\t${c.resyncKind()}\t${c.previewKind()}\t${c.fetchKind()}\t${c.idle}\t${c.searchFuzzy}\n")
        }
        assertEquals("personalities without a post-login capability list", emptyList<String>(), missing)
        File("build/personality-choices.tsv").writeText(out.toString())
    }

    private fun postLoginCaps(json: String): String? {
        val block = Regex("\"post_login_capabilities\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(json)
            ?: return null
        val tokens = Regex("\"([^\"]+)\"").findAll(block.groupValues[1]).map { it.groupValues[1] }.toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ")
    }
}
