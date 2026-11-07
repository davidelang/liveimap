package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** What the window shows after drawer picks on a single panel: the picked layer, not the drawer. */
class MailDrawerHostTest {
    @get:Rule val rule = createComposeRule()

    // The single panel and the drawer sheet as the window draws them; a pick changes what the panel holds.
    private var panel by mutableStateOf("drawer")
    private var peek by mutableStateOf(false)

    private fun show() {
        rule.setContent {
            MailDrawerHost(
                overlayOpen = peek,
                panelShowsDrawer = panel == "drawer",
                onSheetClosed = { peek = false },
                sheetInsets = WindowInsets(0, 0, 0, 0),
                sheet = { Text("Settings", Modifier.testTag("sheet-settings")) },
            ) {
                Box(Modifier.fillMaxSize().testTag("panel-$panel")) { Text(panel) }
            }
        }
    }

    /** The drawer sheet is on screen: placed with some of it inside the window. */
    private fun sheetOnScreen(): Boolean = rule.onAllNodesWithTag("sheet-settings", useUnmergedTree = true)
        .fetchSemanticsNodes().any { it.layoutInfo.isPlaced && it.boundsInRoot.right > 0f && it.boundsInRoot.width > 0f }

    @Test fun aPickFromTheDrawerInThePanelShowsThePickedLayer() {
        show()
        rule.onNodeWithTag("panel-drawer").assertIsDisplayed()
        assertFalse("no sheet over a panel that shows the drawer", sheetOnScreen())
        panel = "settings" // the pick: the drawer stays at the bottom, Settings is the shown layer
        rule.waitForIdle()
        rule.onNodeWithTag("panel-settings").assertIsDisplayed()
        assertFalse("the drawer sheet must not open over the picked layer", sheetOnScreen())
    }

    @Test fun theMenuOpensTheSheetAndAPickClosesIt() {
        panel = "folders"
        show()
        assertFalse(sheetOnScreen())
        peek = true // the menu
        rule.waitForIdle()
        assertTrue("the menu shows the drawer over the panel", sheetOnScreen())
        peek = false; panel = "about" // a pick from the sheet
        rule.waitForIdle()
        rule.onNodeWithTag("panel-about").assertIsDisplayed()
        assertFalse("the pick closes the sheet", sheetOnScreen())
    }

    @Test fun backToTheDrawerAndAPickAgainStillShowsThePick() {
        panel = "folders"
        show()
        panel = "drawer" // Back down to the drawer, or the stack arrow
        rule.waitForIdle()
        panel = "inbox" // a favorite
        rule.waitForIdle()
        rule.onNodeWithTag("panel-inbox").assertIsDisplayed()
        assertFalse(sheetOnScreen())
    }
}
