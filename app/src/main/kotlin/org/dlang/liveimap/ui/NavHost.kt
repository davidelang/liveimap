package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import org.dlang.liveimap.ui.about.AboutScreen
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
    data object About : MailRoute()
}

@Composable
fun NavHost() {
    var route by remember { mutableStateOf<MailRoute>(MailRoute.Folders) }
    var composeSeed by remember {
        mutableStateOf(ComposeSeed(kind = ComposeKind.New, mailbox = null))
    }

    val foldersHighlighted = route is MailRoute.Folders ||
        route is MailRoute.Index ||
        route is MailRoute.Reader ||
        route is MailRoute.Compose

    LiveImapScaffold {
        Column {
            Row {
                TextButton(
                    onClick = {
                        if (route !is MailRoute.Folders) {
                            route = MailRoute.Folders
                        }
                    },
                    colors = if (foldersHighlighted) {
                        ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text("Folders")
                }
                TextButton(
                    onClick = {
                        if (route !is MailRoute.Settings) {
                            route = MailRoute.Settings
                        }
                    },
                    colors = if (route is MailRoute.Settings) {
                        ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text("Settings")
                }
                TextButton(
                    onClick = {
                        if (route !is MailRoute.About) {
                            route = MailRoute.About
                        }
                    },
                    colors = if (route is MailRoute.About) {
                        ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text("About")
                }
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
                    onBack = { route = MailRoute.Folders },
                )
                is MailRoute.Reader -> MessageReaderScreen(
                    mailbox = current.mailbox,
                    uid = current.uid,
                    onCompose = { seed ->
                        composeSeed = seed
                        route = MailRoute.Compose
                    },
                    onBack = { route = MailRoute.Index(current.mailbox) },
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
                MailRoute.About -> AboutScreen()
            }
        }
    }
}
