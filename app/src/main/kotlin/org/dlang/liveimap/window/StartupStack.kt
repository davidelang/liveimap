package org.dlang.liveimap.window

/** Why the configured startup view could not be built. The window shows it; nothing is silently substituted. */
enum class StartupProblem { AccountMissing, FolderMissing, UnknownView, NoFavorite, FavoriteInvalid }

/** The first favorite of the startup account: a folder (message list) or a node of the folder list. */
data class StartupFavorite(val node: Boolean, val mailbox: String)

/**
 * [stack] is a normal stack: the drawer at the bottom, then the chosen view's layers, so the first screen
 * has Back and the menu like any other. [accountId] is the account the drawer must hold.
 * On a problem the stack is the drawer alone, and [problem] says why.
 */
data class StartupPlan(val stack: WindowStack, val accountId: String, val problem: StartupProblem?)

/**
 * [view]: "drawer", "folderList", "folder" or "favorite" (the default; resolved to [firstFavorite]). [configuredAccount] empty means the account
 * open in the drawer ([openAccount]). [folder] is the folder for "folder".
 */
fun startupStack(
    view: String,
    configuredAccount: String,
    openAccount: String,
    accounts: Set<String>,
    folder: String,
    firstFavorite: StartupFavorite? = null,
): StartupPlan {
    val account = configuredAccount.ifEmpty { openAccount }
    val drawer = WindowStack.drawer(account)
    if (configuredAccount.isNotEmpty() && configuredAccount !in accounts) {
        return StartupPlan(WindowStack.drawer(openAccount), openAccount, StartupProblem.AccountMissing)
    }
    return when (view.ifEmpty { "favorite" }) {
        "drawer" -> StartupPlan(drawer, account, null)
        "folderList" -> StartupPlan(drawer.pushFolderTree("root"), account, null)
        "folder" -> if (folder.isBlank()) {
            StartupPlan(drawer, account, StartupProblem.FolderMissing)
        } else {
            StartupPlan(drawer.pushMailboxIndex(folder), account, null)
        }
        // Built the way a drawer pick of that favorite builds it.
        "favorite" -> when {
            firstFavorite == null -> StartupPlan(drawer, account, StartupProblem.NoFavorite)
            firstFavorite.mailbox.isBlank() -> StartupPlan(drawer, account, StartupProblem.FavoriteInvalid)
            firstFavorite.node -> StartupPlan(drawer.choose(StackChoice.Folder, firstFavorite.mailbox), account, null)
            else -> StartupPlan(drawer.choose(StackChoice.Favorite, firstFavorite.mailbox).pushMailboxIndex(firstFavorite.mailbox), account, null)
        }
        else -> StartupPlan(drawer, account, StartupProblem.UnknownView)
    }
}
