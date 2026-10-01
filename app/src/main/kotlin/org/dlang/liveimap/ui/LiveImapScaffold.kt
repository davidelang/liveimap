package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object UiDims {
    val expanderWidth = 30.dp
}

@Composable
fun LiveImapScaffold(content: @Composable () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.displayCutout),
    ) { padding ->
        Box(Modifier.padding(padding)) {
            content()
        }
    }
}
