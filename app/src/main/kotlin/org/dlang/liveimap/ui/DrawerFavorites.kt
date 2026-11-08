package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.FavoriteSpecialPostponed
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.settings.sameEntry

/** The account's whole folder list as a favorite: a node at the empty prefix. Shown with the account's name. */
internal fun isFolderListFavorite(favorite: FolderFavorite): Boolean = favorite.node && favorite.mailbox.isEmpty() && favorite.special.isEmpty()

/**
 * The rows the drawer used to draw for every account, in that order: its folder list (the account name),
 * INBOX, and the postponed folder when one is set. They are ordinary favorites now.
 */
internal fun accountDrawerRows(accountId: String, postponedMailbox: String): List<FolderFavorite> = listOfNotNull(
    FolderFavorite(node = true, mailbox = "", delimiter = '.', account = accountId),
    FolderFavorite(node = false, mailbox = "INBOX", delimiter = '.', account = accountId),
    postponedDrawerMailbox(postponedMailbox)?.let {
        FolderFavorite(node = false, mailbox = "", delimiter = '.', account = accountId, special = FavoriteSpecialPostponed)
    },
)

/**
 * Once per account ([alreadyAdded] is the stored marker): the account rows it lacks are appended at the end, in drawer
 * order. Existing favorites keep their order, names and tags. After this the
 * marker is set, so a row the user deletes stays deleted.
 */
internal fun withAccountDrawerRows(favorites: List<FolderFavorite>, accountId: String, postponedMailbox: String, alreadyAdded: Boolean): List<FolderFavorite> {
    if (alreadyAdded) return favorites
    val missing = accountDrawerRows(accountId, postponedMailbox).filter { row -> favorites.none { it.sameEntry(row) } }
    return favorites + missing
}
