package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTreeCoverageTest {
    @Test
    fun realTreeValidatesAndKeepsScopes() {
        val doc = parseSettingsDocument(settingsTreeText())
        assertEquals(emptyList<String>(), validateSettingsTree(settingsTreeText()))
        assertEquals(listOf("top", "account"), doc.pages.map { (it as TreeNode.Page).page })
        val leaves = ArrayList<TreeLeaf>()
        fun walk(node: TreeNode) {
            when (node) {
                is TreeNode.Leaf -> leaves.add(node.leaf)
                is TreeNode.Group -> node.children.forEach { walk(it) }
                is TreeNode.Page -> node.children.forEach { walk(it) }
                is TreeNode.Editor -> node.children.forEach { walk(it) }
            }
        }
        doc.pages.forEach { walk(it) }
        leaves.addAll(doc.stored)
        val byKey = leaves.associateBy { it.setting }
        for (key in listOf(
            "indexPageSize", "swipeThresholdPercent", "reconnectDelaysSec",
            "maxRawViewBytes", "maxImageTrustBytes", "moveChunk", "clientSortChunk",
            "indexBar", "selectionBar", "folderBar", "readerToolbar", "composeBar", "toolbarRows",
        )) {
            assertEquals(key, "ui", byKey.getValue(key).scopeName)
        }
        assertEquals("ui", byKey.getValue("settingsOpensOn").scopeName)
        assertEquals("drawer", (byKey.getValue("settingsOpensOn").default as JsonValue.Str).value)
        assertEquals("ui", byKey.getValue("settingsLastEditedAccount").scopeName)
        assertTrue(byKey.getValue("settingsLastEditedAccount").stored)
        for (key in listOf("imapHost", "smtpHost", "sieveHost", "sieveReadSeconds")) {
            assertEquals(key, "account", byKey.getValue(key).scopeName)
            assertTrue(byKey.getValue(key).server)
        }
        assertTrue(leaves.all { it.scopeName == "ui" || it.scopeName == "account" })
        assertTrue(leaves.all { it.typeName.isNotEmpty() && (it.stored || it.kind.isNotEmpty()) })
    }
}
