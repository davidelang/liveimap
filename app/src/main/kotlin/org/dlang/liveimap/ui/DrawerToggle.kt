package org.dlang.liveimap.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import org.dlang.liveimap.ui.help.HelpIconButton
import org.dlang.liveimap.i18n.S

/** The hamburger's action. Provided only to the panel whose bar carries it (see hamburgerPlan). */
internal val LocalDrawerToggle = compositionLocalOf<(() -> Unit)?> { null }

/** Where the bar's hamburger sits, so the copy drawn over the open drawer lands on the same spot. */
internal object DrawerToggleSpot {
    var window by mutableStateOf<Offset?>(null)
}

/** The bar's hamburger. Nothing when this panel does not carry it. */
@Composable
internal fun DrawerToggleButton() {
    val toggle = LocalDrawerToggle.current ?: return
    androidx.compose.foundation.layout.Box(
        Modifier.onGloballyPositioned { DrawerToggleSpot.window = it.positionInWindow() },
    ) {
        DrawerToggleIcon(toggle, Modifier.testTag("drawer-toggle"))
    }
}

@Composable
internal fun DrawerToggleIcon(onToggle: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier) {
        HelpIconButton(
            label = S("Menu"),
            help = S("Opens the account list."),
            page = "folders.html#menu",
            onClick = onToggle,
        ) {
            Icon(imageVector = Icons.Filled.Menu, contentDescription = S("Menu"))
        }
    }
}
