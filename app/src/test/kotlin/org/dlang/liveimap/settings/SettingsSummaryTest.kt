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
        val fresh = Settings()
        val account = groups.first { it.id == "account" }
        val piece = summaryPiece(account.summary, fresh, 2, 30) ?: error("account summary")
        val text = renderSummary(piece, { name, args -> if (args.isEmpty()) name else "$name:$args" }, { name, count -> "$name:$count" })
        assertEquals("settings_no_server", text)
        val shown = { piece: SummaryPiece ->
            renderSummary(
                piece,
                { name, args -> if (args.isEmpty()) name else "$name:${args.joinToString(",")}" },
                { name, count -> "$name:$count" },
            )
        }
        val folders = groups.first { it.id == "folders" }
        val foldersPiece = summaryPiece(folders.summary, fresh, 1, 30) ?: error("folders")
        assertEquals("settings_sort_line:label_arrival,reader_newest", shown(foldersPiece))
        val actions = groups.first { it.id == "actions" }
        val actionsPiece = summaryPiece(actions.summary, fresh, 1, 30) ?: error("actions")
        assertEquals("settings_swipe_line:drawer_delete,compose_reply_all", shown(actionsPiece))
        val logging = groups.first { it.id == "logging" }
        val log = summaryPiece(logging.summary, fresh, 1, 30) ?: error("logging")
        val logText = renderSummary(log, { name, args -> "$name:${args.joinToString("|")}" }, { name, count -> "$name:$count" })
        assertTrue(logText.startsWith("settings_logging_line:"))
    }
}
