package org.dlang.liveimap.ui.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSearchTest {
    @Test
    fun trimmedNameKeepsPlaceAndRejectsEmpty() {
        val start = emptyList<SavedSearch>()
        val savedNews = saveSearch(start, "  News ", "ada", SimpleSearchField.From)
        assertEquals(listOf(SavedSearch("News", "ada", SimpleSearchField.From)), savedNews)
        assertTrue(savedNews !== start)
        assertTrue(start.isEmpty())

        val replaced = saveSearch(savedNews, "News", "bob", SimpleSearchField.Subject)
        assertEquals(listOf(SavedSearch("News", "bob", SimpleSearchField.Subject)), replaced)
        assertEquals(SimpleSearchField.From, savedNews[0].field)
        assertEquals("ada", savedNews[0].query)

        val withOther = saveSearch(replaced, "Other", "x", SimpleSearchField.To)
        assertEquals(
            listOf(
                SavedSearch("News", "bob", SimpleSearchField.Subject),
                SavedSearch("Other", "x", SimpleSearchField.To),
            ),
            withOther,
        )
        assertEquals("bob", replaced[0].query)

        val emptyName = saveSearch(withOther, "", "z", SimpleSearchField.Cc)
        val blankName = saveSearch(withOther, "   ", "z", SimpleSearchField.Cc)
        val emptyQuery = saveSearch(withOther, "Nope", "", SimpleSearchField.Cc)
        assertEquals(withOther, emptyName)
        assertEquals(withOther, blankName)
        assertEquals(withOther, emptyQuery)
        assertEquals(2, withOther.size)
        assertEquals("News", withOther[0].name)
        assertEquals("Other", withOther[1].name)
    }

    @Test
    fun recallIsExactAndSentMailStaysOneName() {
        val saved = saveSearch(
            saveSearch(emptyList(), "  News ", "ada", SimpleSearchField.From),
            "News",
            "bob",
            SimpleSearchField.Subject,
        )
        val recalled = recallSearch(saved, "News")
        assertEquals("bob", recalled?.query)
        assertEquals(SimpleSearchField.Subject, recalled?.field)
        assertNull(recallSearch(saved, "news"))
        assertNull(recallSearch(saved, "   "))
        assertNull(recallSearch(saved, ""))

        val sent = saveSearch(emptyList(), "Sent Mail", "ada", SimpleSearchField.From)
        assertEquals(listOf(SavedSearch("Sent Mail", "ada", SimpleSearchField.From)), sent)
        assertEquals(sent[0], recallSearch(sent, "Sent Mail"))
        assertNull(recallSearch(sent, "Sent"))
        assertNull(recallSearch(sent, "Mail"))
    }

    @Test
    fun namesAreCaseSensitiveAndQueryIsStoredAsGiven() {
        val first = saveSearch(emptyList(), "News", " ada ", SimpleSearchField.Cc)
        val both = saveSearch(first, "news", "bob", SimpleSearchField.Participant)
        assertEquals(" ada ", both[0].query)
        assertEquals(SimpleSearchField.Cc, both[0].field)
        assertEquals("news", both[1].name)
        assertEquals("bob", both[1].query)
        assertEquals(SimpleSearchField.Participant, both[1].field)
        assertEquals(first[0], recallSearch(both, " News "))
        assertEquals(both[1], recallSearch(both, "news"))
    }
}
