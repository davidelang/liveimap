package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The drawer drawn over the mail fill. [overlayOpen] (the drawer model's peek) alone decides whether the
 * sheet is open. [panelShowsDrawer]: a panel already shows the drawer, so the sheet holds no second copy.
 *
 * The sheet keeps one width whether or not it holds the drawer. Material's modal drawer anchors on the
 * sheet's measured width; an empty sheet measured 0 wide, so Closed and Open were the same spot, and when
 * a pick took the drawer out of the panel the sheet filled in and settled Open with no peek behind it.
 * The picked layer was in the stack but hidden under a drawer nothing would close.
 */
@Composable
internal fun MailDrawerHost(
    overlayOpen: Boolean,
    panelShowsDrawer: Boolean,
    onSheetClosed: () -> Unit,
    sheetInsets: WindowInsets,
    sheet: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val overlay by rememberUpdatedState(overlayOpen)
    val closed by rememberUpdatedState(onSheetClosed)
    // A swipe, a scrim tap, or Back closing the open sheet clears the peek.
    val state = rememberDrawerState(DrawerValue.Closed) { value ->
        if (value == DrawerValue.Closed && overlay) closed()
        true
    }
    // The sheet follows the peek, also when it settled somewhere on its own.
    LaunchedEffect(overlayOpen, state.currentValue) {
        if (overlayOpen) {
            if (state.currentValue != DrawerValue.Open) state.open()
        } else if (state.currentValue != DrawerValue.Closed) {
            state.close()
        }
    }
    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(DrawerDefaults.MaximumDrawerWidth).testTag("drawer-sheet"),
                windowInsets = sheetInsets,
            ) {
                if (!panelShowsDrawer) sheet()
            }
        },
        modifier = modifier,
        drawerState = state,
        gesturesEnabled = overlayOpen,
        content = content,
    )
}
