package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.dlang.liveimap.i18n.S
import org.dlang.liveimap.window.StackArrows

/**
 * Moves the visible window over the stack. Lower layers sit to the left of higher ones, so left shows a
 * lower layer and right goes back toward the top. The arrows only move the view; Back still pops.
 */
@Composable
internal fun StackArrowsBar(arrows: StackArrows, onMove: (down: Boolean) -> Unit, modifier: Modifier = Modifier) {
    if (!arrows.down && !arrows.up) return
    Column(modifier) {
        if (arrows.down) {
            FilledTonalIconButton(onClick = { onMove(true) }, modifier = Modifier.testTag("stack-down")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = S("Show the layer below", name = "stack_view_down"))
            }
        }
        if (arrows.up) {
            FilledTonalIconButton(onClick = { onMove(false) }, modifier = Modifier.testTag("stack-up")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = S("Show the layer above", name = "stack_view_up"))
            }
        }
    }
}
