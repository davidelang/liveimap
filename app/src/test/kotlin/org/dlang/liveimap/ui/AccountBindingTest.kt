package org.dlang.liveimap.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** F121: only the drawer chooses the active account; every screen stays bound to its own account. */
class AccountBindingTest {
    @Test
    fun longPressManagesTheFavoriteInItsOwnAccountAndShowsWhere() {
        assertEquals("dlang@lang.hm on mail.lang.hm\nINBOX.lists.debian", favoriteWhereText(FavoriteWhere("dlang@lang.hm", "mail.lang.hm", "INBOX.lists.debian")))
        assertEquals("(top level)", favoriteWhereText(FavoriteWhere("", "", "")))
    }
}

class ComposeAccountBindingTest {
    @Test
    fun composeAndRetriesRunInTheOpenersAccount() {
        val seed = org.dlang.liveimap.session.ComposeSeed(org.dlang.liveimap.session.ComposeKind.Reply, "INBOX", listOf(5L), 9L, "acct-b")
        assertEquals("acct-b", seed.refs("acct-b").single().accountId)
    }
}
