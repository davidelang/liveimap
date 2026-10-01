package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.settings.SettingsScreen
import org.dlang.liveimap.ui.compose.ComposeScreen
import org.dlang.liveimap.ui.folder.FolderListScreen
import org.dlang.liveimap.ui.index.MessageIndexScreen
import org.dlang.liveimap.ui.reader.MessageReaderScreen

private sealed class MailRoute {
    data object Folders : MailRoute()
    data class Index(val mailbox: String) : MailRoute()
    data class Reader(val mailbox: String, val uid: Long) : MailRoute()
    data object Compose : MailRoute()
    data object Settings : MailRoute()
}

@Composable
fun NavHost() {
    var route by remember { mutableStateOf<MailRoute>(MailRoute.Folders) }
    var composeSeed by remember {
        mutableStateOf(ComposeSeed(kind = ComposeKind.New, mailbox = null))
    }

    val title = when (route) {
        MailRoute.Folders -> "Folders"
        is MailRoute.Index -> "Index"
        is MailRoute.Reader -> "Reader"
        MailRoute.Compose -> "Compose"
        MailRoute.Settings -> "Settings"
    }

    LiveImapScaffold(title = title) {
        Column {
            TextButton(
                onClick = {
                    route = if (route is MailRoute.Settings) {
                        MailRoute.Folders
                    } else {
                        MailRoute.Settings
                    }
                },
            ) {
                Text(if (route is MailRoute.Settings) "Folders" else "Settings")
            }
            when (val current = route) {
                MailRoute.Folders -> FolderListScreen(
                    onOpenMailbox = { mailbox ->
                        route = MailRoute.Index(mailbox)
                    },
                )
                is MailRoute.Index -> MessageIndexScreen(
                    mailbox = current.mailbox,
                    onOpen = { uid ->
                        route = MailRoute.Reader(current.mailbox, uid)
                    },
                    onCompose = { seed ->
                        composeSeed = seed
                        route = MailRoute.Compose
                    },
                )
                is MailRoute.Reader -> MessageReaderScreen(
                    mailbox = current.mailbox,
                    uid = current.uid,
                    onCompose = { seed ->
                        composeSeed = seed
                        route = MailRoute.Compose
                    },
                )
                MailRoute.Compose -> ComposeScreen(
                    seed = composeSeed,
                    onDone = {
                        val mailbox = composeSeed.mailbox
                        route = if (mailbox != null) {
                            MailRoute.Index(mailbox)
                        } else {
                            MailRoute.Folders
                        }
                    },
                )
                MailRoute.Settings -> SettingsScreen()
            }
        }
    }
}
