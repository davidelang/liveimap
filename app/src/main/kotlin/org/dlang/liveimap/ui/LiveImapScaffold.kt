package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.dlang.liveimap.R

object UiDims {
    val expanderWidth = 30.dp
}

@Composable
internal fun mailScreenInsets(): WindowInsets =
    WindowInsets.statusBars
        .union(WindowInsets.navigationBars)
        .union(WindowInsets.displayCutout)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun mailBarInsets(): WindowInsets =
    TopAppBarDefaults.windowInsets.union(
        WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpTopAppBar(title: String, onUp: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.scaffold_up),
                )
            }
        },
        windowInsets = mailBarInsets(),
    )
}

@Composable
fun LiveImapScaffold(content: @Composable () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        content()
    }
}
