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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // Unsaved edits: Up goes through Back so the screen's BackHandler can ask instead of dropping them.
    val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val up: () -> Unit = {
        if (org.dlang.liveimap.settings.UnsavedEdits.pending && back != null) back.onBackPressed() else onUp()
    }
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = up) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.scaffold_up),
                )
            }
        },
        windowInsets = mailBarInsets(),
    )
}

internal object IndexLeaveReport {
    var text by mutableStateOf<String?>(null)
        private set

    fun report(text: String) {
        this.text = text
    }

    fun clear(shown: String) {
        if (text == shown) text = null
    }
}

@Composable
fun LiveImapScaffold(content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    val pending = IndexLeaveReport.text
    LaunchedEffect(pending) {
        val message = pending ?: return@LaunchedEffect
        host.showSnackbar(message)
        IndexLeaveReport.clear(message)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(host) },
    ) {
        content()
    }
}
