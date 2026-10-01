package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object UiDims {
    val expanderWidth = 30.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveImapScaffold(title: String, content: @Composable () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(title = { Text(title) })
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            content()
        }
    }
}
