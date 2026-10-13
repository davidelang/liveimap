package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * David, 2026-10-12: every button must look like a button. The app's TextButton call sites use this outlined
 * button instead of Material's plain-text one (same parameters, so call sites are unchanged).
 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
}
