package org.dlang.liveimap.ui.help
import org.dlang.liveimap.i18n.S

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import org.dlang.liveimap.ui.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

const val MANUAL_URL = "https://davidelang.github.io/liveimap/manual/"
const val PRIVACY_URL = "https://liveimap.lang.hm/privacy/"

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
                org.dlang.liveimap.ui.openWebPage(context, manualPage(page))
                onDismiss()
            }) { Text(S("Online manual")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S("Close")) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.HelpClickOverlay(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .matchParentSize()
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = label
            },
    )
}

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
        HelpClickOverlay(
            label = label,
            enabled = enabled,
            onClick = onClick,
            onLongClick = { open = true },
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
        HelpClickOverlay(
            label = label,
            enabled = true,
            onClick = onClick,
            onLongClick = { open = true },
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

@Composable
fun HelpDropdownMenuItem(
    label: String,
    help: String,
    page: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        DropdownMenuItem(
            text = text,
            onClick = {},
            modifier = Modifier.clearAndSetSemantics { },
            enabled = enabled,
            leadingIcon = leadingIcon,
        )
        HelpClickOverlay(
            label = label,
            enabled = enabled,
            onClick = onClick,
            onLongClick = { open = true },
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
