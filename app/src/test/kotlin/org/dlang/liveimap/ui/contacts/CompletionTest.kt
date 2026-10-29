package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.androidSourceId
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CompletionTest {
    @Test
    fun exactNicknameInLaterSourceBeatsEarlierDisplayName() {
        val sources = listOf(
            CompletionSource(
                "Pine",
                listOf(CompletionEntry("other", "Ada Lovelace", "x@example.com")),
            ),
            CompletionSource(
                "Google",
                listOf(CompletionEntry("ada", "Someone Else", "s@example.com")),
            ),
        )
        val hits = completeAddress("ada", sources)
        assertEquals("Google", hits.first().sourceLabel)
        assertEquals("ada", hits.first().nickname)
        assertTrue(hits.any { it.sourceLabel == "Pine" })
        assertTrue(
            hits.indexOfFirst { it.sourceLabel == "Google" } <
                hits.indexOfFirst { it.sourceLabel == "Pine" },
        )
    }

    @Test
    fun twoExactNicknamesKeepEarlierSource() {
        val sources = listOf(
            CompletionSource(
                "Pine",
                listOf(CompletionEntry("ada", "Ada Pine", "a@example.com")),
            ),
            CompletionSource(
                "Google",
                listOf(CompletionEntry("Ada", "Ada Google", "b@example.com")),
            ),
        )
        val hits = completeAddress("ada", sources)
        assertEquals(listOf("Pine", "Google"), hits.map { it.sourceLabel })
        assertEquals("ada", hits.first().nickname)
    }

    @Test
    fun parenthesizedDistributionListIsOneSuggestion() {
        val sources = listOf(
            CompletionSource(
                "Pine",
                listOf(
                    CompletionEntry(
                        "team",
                        "Team",
                        "(ada@example.com, grace@example.com)",
                    ),
                ),
            ),
        )
        val hits = completeAddress("team", sources)
        assertEquals(1, hits.size)
        assertTrue(hits.single().distribution)
        assertEquals(
            listOf("ada@example.com", "grace@example.com"),
            hits.single().members.map { it.email },
        )
    }

    @Test
    fun distributionListExpandsOnlyAfterAMatch() {
        resetCompletionNicknameIndexes()
        val ada = CompletionEntry("ada", "Ada Lovelace", "ada@example.com")
        val team = CompletionEntry("team", "Team", "(ada, grace@example.com)")
        val crew = CompletionEntry("crew", "Crew", "(ada)")
        val sources = listOf(CompletionSource("Pine", listOf(team, crew, ada)))
        assertTrue(completeAddress("zzz", sources).isEmpty())
        assertEquals(0, completionNicknameIndexes)
        val one = completeAddress("team", sources)
        assertEquals(listOf("team"), one.map { it.nickname })
        assertTrue(one.single().distribution)
        assertEquals(listOf("ada@example.com", "grace@example.com"), one.single().members.map { it.email })
        assertEquals(1, completionNicknameIndexes)
        val both = completeAddress("crew", sources)
        assertEquals(listOf("crew"), both.map { it.nickname })
        assertEquals(listOf("ada@example.com"), both.single().members.map { it.email })
        assertEquals(2, completionNicknameIndexes)
        val matched = completeAddress("e", sources)
        assertEquals(listOf("team", "crew", "ada"), matched.map { it.nickname })
        assertEquals(listOf("ada@example.com", "grace@example.com"), matched[0].members.map { it.email })
        assertEquals(listOf("ada@example.com"), matched[1].members.map { it.email })
        assertFalse(matched[2].distribution)
        assertEquals(3, completionNicknameIndexes)
    }

    @Test
    fun distributionNicknameIndexMatchesAsciiCaseOnTurkishLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            resetCompletionNicknameIndexes()
            val sources = listOf(
                CompletionSource(
                    "Pine",
                    listOf(
                        CompletionEntry("Igor", "Igor", "igor@example.com"),
                        CompletionEntry("team", "Team", "(igor)"),
                    ),
                ),
            )
            val hits = completeAddress("team", sources)
            assertEquals(listOf("igor@example.com"), hits.single().members.map { it.email })
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun missingCompletionSourcesKeyDecodesToPineOnly() {
        val encoded = Settings().encode()
        assertTrue(encoded.contains("completionSources="))
        assertEquals(listOf("pine"), decodeSettings(encoded).completionSources)
        val removed = Settings(completionSources = listOf("android|x|y")).encode()
            .lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("completionSources=") }
            .joinToString("\n")
        assertEquals(listOf("pine"), decodeSettings(removed).completionSources)
    }

    @Test
    fun emptyCompletionSourcesValueDecodesToNone() {
        val appended = Settings().encode().trimEnd() + "\ncompletionSources=\n"
        assertEquals(emptyList<String>(), decodeSettings(appended).completionSources)
        val saved = Settings(completionSources = emptyList())
        assertTrue(saved.encode().lines().contains("completionSources="))
        assertEquals(emptyList<String>(), decodeSettings(saved.encode()).completionSources)
        assertEquals(saved.encode(), decodeSettings(saved.encode()).encode())
    }

    @Test
    fun completionSourcesKeepOrderAndEncoding() {
        val id = androidSourceId("com.google", "Ada/Lane")
        assertEquals("android|com.google|Ada%2FLane", id)
        val saved = Settings(completionSources = listOf(id, "pine"))
        val text = saved.encode()
        assertEquals(listOf(id, "pine"), decodeSettings(text).completionSources)
        assertEquals(text, decodeSettings(text).encode())
    }
}
