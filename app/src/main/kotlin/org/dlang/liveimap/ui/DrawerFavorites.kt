package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.FolderFavorite

/** The account's whole folder list as a favorite: a node at the empty prefix. Shown with the account's name. */
internal fun isFolderListFavorite(favorite: FolderFavorite): Boolean = favorite.node && favorite.mailbox.isEmpty()

/**
 * The rows the drawer used to draw for every account, in that order: its folder list (the account name),
 * INBOX, and the postponed folder when one is set. They are ordinary favorites now.
 */
internal fun accountDrawerRows(postponedMailbox: String): List<FolderFavorite> = listOfNotNull(
    FolderFavorite(node = true, mailbox = "", delimiter = '.'),
    FolderFavorite(node = false, mailbox = "INBOX", delimiter = '.'),
    postponedDrawerMailbox(postponedMailbox)?.let { FolderFavorite(node = false, mailbox = it, delimiter = '.') },
)

/**
 * Once per account ([alreadyAdded] is the stored marker): the account rows it lacks are appended at the end, in drawer
 * order. Existing favorites keep their order, names and tags. After this the
 * marker is set, so a row the user deletes stays deleted.
 */
internal fun withAccountDrawerRows(favorites: List<FolderFavorite>, postponedMailbox: String, alreadyAdded: Boolean): List<FolderFavorite> {
    if (alreadyAdded) return favorites
    val missing = accountDrawerRows(postponedMailbox).filter { row ->
        favorites.none { it.node == row.node && it.mailbox == row.mailbox }
    }
    return favorites + missing
}
