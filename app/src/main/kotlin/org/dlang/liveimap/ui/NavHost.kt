package org.dlang.liveimap.ui

import androidx.compose.runtime.Composable
import org.dlang.liveimap.settings.SettingsScreen

@Composable
fun NavHost() {
    LiveImapScaffold(title = "Settings") {
        SettingsScreen()
    }
}
