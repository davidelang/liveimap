package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.settings.AddressBookEntry
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.androidSourceId
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.documentWith
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
    fun missingAddressBooksKeyDecodesToNone() {
        val encoded = Settings().encode()
        assertTrue(encoded.contains("\"addressBooks\":[]"))
        assertEquals(emptyList<AddressBookEntry>(), decodeSettings(encoded).addressBooks)
        val removed = documentWith(
            Settings(addressBooks = listOf(AddressBookEntry(kind = "android", accountType = "x", accountName = "y"))).encode(),
            "addressBooks",
            "[]",
        )
        assertEquals(emptyList<AddressBookEntry>(), decodeSettings(removed).addressBooks)
        assertEquals(emptyList<CompletionSource>(), completionSourcesInOrder(decodeSettings(removed).addressBooks, emptyMap(), emptyMap(), emptyMap()))
    }

    @Test
    fun emptyAddressBooksDecodeToNoSources() {
        val saved = Settings(addressBooks = emptyList())
        assertTrue(saved.encode().contains("\"addressBooks\":[]"))
        assertEquals(emptyList<AddressBookEntry>(), decodeSettings(saved.encode()).addressBooks)
        assertEquals(saved.encode(), decodeSettings(saved.encode()).encode())
        assertEquals(emptyList<CompletionSource>(), completionSourcesInOrder(saved.addressBooks, emptyMap(), emptyMap(), emptyMap()))
    }

    @Test
    fun addressBooksKeepOrderAndSourceEncoding() {
        val id = androidSourceId("com.google", "Ada/Lane")
        assertEquals("android|com.google|Ada%2FLane", id)
        val android = AddressBookEntry(kind = "android", accountType = "com.google", accountName = "Ada/Lane")
        val pine = AddressBookEntry(kind = "pine", mailbox = "ab")
        val saved = Settings(addressBooks = listOf(android, pine))
        val text = saved.encode()
        assertEquals(listOf(android, pine), decodeSettings(text).addressBooks)
        assertEquals(text, decodeSettings(text).encode())
        val sources = completionSourcesInOrder(
            decodeSettings(text).addressBooks,
            mapOf("ab" to emptyList()),
            mapOf(id to emptyList()),
            mapOf(id to "Ada"),
        )
        assertEquals(listOf("Ada", "ab"), sources.map { it.label })
    }
}
