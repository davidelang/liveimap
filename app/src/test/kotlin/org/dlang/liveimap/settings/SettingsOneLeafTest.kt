package org.dlang.liveimap.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOneLeafTest {
    @Test
    fun oneVisibleLeafReplacesTheHeader() {
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
        parseSettingsDocument(settingsTreeText()).pages.forEach { walk(it) }
        val read = { _: String -> "" }
        fun shown(id: String, sdk: Int) = leafShownInstead(groups.first { it.id == id }, read, sdk)?.setting
        assertEquals("replyAboveQuote", shown("replying", 30))
        assertEquals("customLayout", shown("custom_panel_layout", 30))
        assertEquals("favorites", shown("favorites", 30))
        assertEquals("theme", shown("theme", 30))
        assertNull(shown("theme", 31))
        for (id in listOf(
            "mailboxes", "account", "server", "sending", "logging", "layout", "address_book",
            "forwarding", "composing",
        )) {
            assertNull(id, shown(id, 30))
        }
    }

    @Test
    fun hiddenSiblingsLeaveTheOneLeaf() {
        val hidden = Condition.Equals("flag", JsonValue.Bool(true))
        val plain = group("Plain", listOf(leaf("kept"), leaf("hidden", hidden)))
        assertEquals("kept", leafShownInstead(plain, { "false" }, 30)?.setting)
        assertNull(leafShownInstead(group("Plain", listOf(leaf("a"), leaf("b"))), { "" }, 30))
        val nested = group("Mailboxes", listOf(group("Special-use", listOf(leaf("sent")))))
        assertNull(leafShownInstead(nested, { "" }, 30))
        assertNull(leafShownInstead(group("Account", listOf(leaf("only"))), { "" }, 30))
        assertNull(leafShownInstead(group("Server", listOf(leaf("only"))), { "" }, 30))
        assertEquals("only", leafShownInstead(group("Replying", listOf(leaf("only"))), { "" }, 30)?.setting)
    }

    @Test
    fun hiddenLeafAndEmptyGroupAreNotDrawn() {
        val read = { _: String -> "" }
        val hidden = leaf("secret", hidden = true)
        val kept = leaf("kept")
        assertFalse(childDrawn(hidden, read, 30))
        assertTrue(childDrawn(kept, read, 30))
        val whenHidden = leaf("flagged", Condition.Equals("flag", JsonValue.Bool(true)))
        assertFalse(childDrawn(whenHidden, { "false" }, 30))
        assertTrue(childDrawn(whenHidden, { "true" }, 30))
        assertFalse(childDrawn(group("Quiet", listOf(hidden)), read, 30))
        val mixed = group("Quiet", listOf(hidden, kept))
        assertTrue(childDrawn(mixed, read, 30))
        assertEquals("kept", leafShownInstead(mixed, read, 30)?.setting)
    }

    @Test
    fun manualListsTheParserKeys() {
        val text = listOf(
            File("src/main/assets/settings-tree.md"),
            File("app/src/main/assets/settings-tree.md"),
        ).first { it.isFile }.readText()
        for (key in listOf(
            "sections", "hidden", "ui", "account",
            "group", "header", "introWhen", "description",
            "clientCert", "paneLayout", "pinerc", "settingsFile", "toolbar",
            "many", "order", "not", "either", "checkServer", "password", "folder",
            "pinercFormat",
            "titleWhen", "titleArgs", "sdkAtLeast", "notEquals",
            "choiceTitle", "countSet", "rulesWho", "\$accounts",
            "single visible leaf",
        )) {
            assertTrue(key, text.contains(key))
        }
    }
}

private fun leaf(setting: String, visibleWhen: Condition? = null, hidden: Boolean = false) = TreeNode.Leaf(
    TreeLeaf(
        setting = setting,
        typeName = "Bool",
        scopeName = "account",
        kind = "bool",
        default = JsonValue.Bool(false),
        title = "t",
        visibleWhen = visibleWhen,
        hidden = hidden,
    ),
)

private fun group(title: String, children: List<TreeNode>) = TreeNode.Group(
    id = title,
    title = title,
    resource = "t",
    header = "label",
    description = null,
    children = children,
)
