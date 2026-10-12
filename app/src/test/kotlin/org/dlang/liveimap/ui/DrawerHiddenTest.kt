package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.MultiPane
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerHiddenTest {
    @Test fun autoStartScreenHidesTheDrawerPane() =
        assertTrue(drawerHiddenFor(folderColumn = false, route = "folders", multiPane = MultiPane.Auto, autoDrawerPane = true))

    @Test fun autoIndexWithoutFolderColumnKeepsTheDrawerPane() =
        assertFalse(drawerHiddenFor(false, "index/{mailbox}", MultiPane.Auto, true))

    @Test fun autoWithoutRoomHides() = assertTrue(drawerHiddenFor(false, "index/{mailbox}", MultiPane.Auto, false))

    @Test fun folderColumnHides() = assertTrue(drawerHiddenFor(true, "index/{mailbox}", MultiPane.Wide, true))

    @Test fun wideKeepsTheDrawerBesideTheFolderList() =
        assertFalse(drawerHiddenFor(false, "folders", MultiPane.Wide, false))
}
