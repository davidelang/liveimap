package org.dlang.liveimap.ui.help

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.dlang.liveimap.R

const val MANUAL_URL = "https://davidelang.github.io/liveimap/manual/"

fun manualPage(page: String): String = MANUAL_URL + page

@Composable
fun IconHelpDialog(
    title: String,
    body: String,
    page: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(manualPage(page))))
                onDismiss()
            }) { Text(stringResource(R.string.help_open_manual)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.help_close)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HelpIconButton(
    label: String,
    help: String,
    page: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = {},
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
        ) {
            content()
        }
        Box(
            Modifier
                .matchParentSize()
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = { open = true },
                )
                .semantics { contentDescription = label },
        )
    }
    if (open) {
        IconHelpDialog(
            title = label,
            body = help,
            page = page,
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HelpExtendedFab(
    label: String,
    help: String,
    page: String,
    onClick: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ExtendedFloatingActionButton(
            text = { Text(label) },
            icon = icon,
            onClick = {},
            expanded = expanded,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Box(
            Modifier
                .matchParentSize()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { open = true },
                )
                .semantics { contentDescription = label },
        )
    }
    if (open) {
        IconHelpDialog(
            title = label,
            body = help,
            page = page,
            onDismiss = { open = false },
        )
    }
}
