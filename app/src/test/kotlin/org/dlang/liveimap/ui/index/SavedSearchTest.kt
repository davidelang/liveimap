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

    @Test
    fun deleteDropsTrimmedNameAndKeepsTheRest() {
        val news = SavedSearch("News", "ada", SimpleSearchField.From)
        val other = SavedSearch("Other", "x", SimpleSearchField.To)
        val start = listOf(news, other)
        val left = deleteSavedSearch(start, "News")
        assertEquals(listOf(other), left)
        assertTrue(left !== start)
        assertEquals(listOf(news, other), start)

        assertEquals(start, deleteSavedSearch(start, "news"))
        assertEquals(start, deleteSavedSearch(start, "   "))
        assertEquals(start, deleteSavedSearch(start, ""))
        assertEquals(start, deleteSavedSearch(start, "Missing"))
        assertEquals(2, start.size)
    }

    @Test
    fun deleteDropsEveryMatchingName() {
        val news = SavedSearch("News", "ada", SimpleSearchField.From)
        val other = SavedSearch("Other", "x", SimpleSearchField.To)
        val again = SavedSearch("News", "bob", SimpleSearchField.Subject)
        val start = listOf(news, other, again)
        val left = deleteSavedSearch(start, " News ")
        assertEquals(listOf(other), left)
        assertEquals(listOf(news, other, again), start)
    }

    @Test
    fun deleteSentMailLeavesTheOtherNames() {
        val news = SavedSearch("News", "ada", SimpleSearchField.From)
        val sent = SavedSearch("Sent Mail", "ada", SimpleSearchField.From)
        val other = SavedSearch("Other", "x", SimpleSearchField.To)
        val start = listOf(news, sent, other)
        assertEquals(listOf(news, other), deleteSavedSearch(start, "Sent Mail"))
        assertEquals(start, deleteSavedSearch(start, "Sent"))
        assertEquals(start, deleteSavedSearch(start, "Mail"))
        assertEquals(sent, start[1])
    }

    @Test
    fun saveAdvancedTrimsNameKeepsPlaceAndRejectsEmpty() {
        val start = emptyList<SavedAdvanced>()
        val savedNews = saveAdvanced(start, "  News ", "ada", SearchScope.Current)
        assertEquals(listOf(SavedAdvanced("News", "ada", SearchScope.Current)), savedNews)
        assertTrue(savedNews !== start)
        assertTrue(start.isEmpty())

        val replaced = saveAdvanced(savedNews, "News", "bob", SearchScope.All)
        assertEquals(listOf(SavedAdvanced("News", "bob", SearchScope.All)), replaced)
        assertEquals(SearchScope.Current, savedNews[0].scope)
        assertEquals("ada", savedNews[0].text)

        val withOther = saveAdvanced(replaced, "Other", "x", SearchScope.Subtree)
        assertEquals(
            listOf(
                SavedAdvanced("News", "bob", SearchScope.All),
                SavedAdvanced("Other", "x", SearchScope.Subtree),
            ),
            withOther,
        )
        assertEquals("bob", replaced[0].text)

        val newsAgain = saveAdvanced(withOther, "News", "ada", SearchScope.Subscribed)
        assertEquals(
            listOf(
                SavedAdvanced("News", "ada", SearchScope.Subscribed),
                SavedAdvanced("Other", "x", SearchScope.Subtree),
            ),
            newsAgain,
        )
        assertEquals(SearchScope.All, withOther[0].scope)

        val emptyName = saveAdvanced(withOther, "", "z", SearchScope.All)
        val blankName = saveAdvanced(withOther, "   ", "z", SearchScope.All)
        val emptyText = saveAdvanced(withOther, "Nope", "", SearchScope.All)
        assertEquals(withOther, emptyName)
        assertEquals(withOther, blankName)
        assertEquals(withOther, emptyText)
        assertEquals(2, withOther.size)
        assertEquals("News", withOther[0].name)
        assertEquals("Other", withOther[1].name)
    }

    @Test
    fun recallAdvancedIsExactAndSentMailStaysOneName() {
        val saved = saveAdvanced(
            saveAdvanced(emptyList(), "  News ", "ada", SearchScope.Current),
            "News",
            "bob",
            SearchScope.All,
        )
        val recalled = recallAdvanced(saved, "News")
        assertEquals("bob", recalled?.text)
        assertEquals(SearchScope.All, recalled?.scope)
        assertNull(recallAdvanced(saved, "news"))
        assertNull(recallAdvanced(saved, "   "))
        assertNull(recallAdvanced(saved, ""))

        val sent = saveAdvanced(emptyList(), "Sent Mail", "ada", SearchScope.Current)
        assertEquals(listOf(SavedAdvanced("Sent Mail", "ada", SearchScope.Current)), sent)
        assertEquals(sent[0], recallAdvanced(sent, "Sent Mail"))
        assertNull(recallAdvanced(sent, "Sent"))
        assertNull(recallAdvanced(sent, "Mail"))
    }

    @Test
    fun advancedNamesAreCaseSensitiveAndTextIsStoredAsGiven() {
        val first = saveAdvanced(emptyList(), "News", " ada ", SearchScope.Current)
        val both = saveAdvanced(first, "news", "And\nYes\tSubject\tada", SearchScope.Subtree)
        assertEquals(" ada ", both[0].text)
        assertEquals(SearchScope.Current, both[0].scope)
        assertEquals("news", both[1].name)
        assertEquals("And\nYes\tSubject\tada", both[1].text)
        assertEquals(SearchScope.Subtree, both[1].scope)
        assertEquals(first[0], recallAdvanced(both, " News "))
        assertEquals(both[1], recallAdvanced(both, "news"))
    }

    @Test
    fun deleteAdvancedDropsEveryTrimmedName() {
        val news = SavedAdvanced("News", "ada", SearchScope.Current)
        val other = SavedAdvanced("Other", "x", SearchScope.Subtree)
        val again = SavedAdvanced("News", "bob", SearchScope.All)
        val start = listOf(news, other, again)
        val left = deleteAdvanced(start, " News ")
        assertEquals(listOf(other), left)
        assertTrue(left !== start)
        assertEquals(listOf(news, other, again), start)

        assertEquals(start, deleteAdvanced(start, "news"))
        assertEquals(start, deleteAdvanced(start, "   "))
        assertEquals(start, deleteAdvanced(start, ""))
        assertEquals(start, deleteAdvanced(start, "Missing"))
        assertEquals(3, start.size)
    }
}
