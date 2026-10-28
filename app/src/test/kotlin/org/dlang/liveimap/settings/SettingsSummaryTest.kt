package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSummaryTest {
    @Test
    fun groupSummariesResolveWithoutAndroid() {
        val doc = parseSettingsDocument(settingsTreeText())
        val groups = ArrayList<TreeNode.Group>()
        fun walk(node: TreeNode) {
            when (node) {
                is TreeNode.Group -> {
                    groups.add(node)
                    node.children.forEach { walk(it) }
                }
                is TreeNode.Page -> node.children.forEach { walk(it) }
                is TreeNode.Editor -> node.children.forEach { walk(it) }
                is TreeNode.Leaf -> Unit
            }
        }
        doc.pages.forEach { walk(it) }
        assertEquals(35, groups.size)
        val fresh = AccountSettings()
        val account = groups.first { it.id == "account" }
        val piece = summaryPiece(account.summary, fresh, 2, 30) ?: error("account summary")
        val text = renderSummary(piece, { name, args -> if (args.isEmpty()) name else "$name:$args" }, { name, count -> "$name:$count" })
        assertEquals("settings_no_server", text)
        val logging = groups.first { it.id == "logging" }
        val log = summaryPiece(logging.summary, fresh, 1, 30) ?: error("logging")
        val logText = renderSummary(log, { name, args -> "$name:${args.joinToString("|")}" }, { name, count -> "$name:$count" })
        assertTrue(logText.startsWith("settings_logging_line:"))
    }
}
