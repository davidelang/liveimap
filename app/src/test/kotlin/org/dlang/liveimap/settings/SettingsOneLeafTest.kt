package org.dlang.liveimap.settings

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOneLeafTest {
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
    fun aNoteIsDrawnAndIsNotTheOneLeaf() {
        val read = { _: String -> "" }
        val sentence = TreeNode.Note(text = "Hello", english = "Hello", line = false)
        val rule = TreeNode.Note(text = "", english = "", line = true)
        assertTrue(childDrawn(sentence, read, 30))
        assertTrue(childDrawn(rule, read, 30))
        val hidden = sentence.copy(visibleWhen = Condition.Equals("flag", JsonValue.Bool(true)))
        assertFalse(childDrawn(hidden, { "false" }, 30))
        assertTrue(childDrawn(hidden, { "true" }, 30))
        val withNote = group("Replying", listOf(leaf("only"), sentence))
        assertTrue(childDrawn(withNote, read, 30))
        assertNull(leafShownInstead(withNote, read, 30))
        val onlyNote = group("Replying", listOf(sentence))
        assertTrue(childDrawn(onlyNote, read, 30))
        assertNull(leafShownInstead(onlyNote, read, 30))
        val noteHidden = group("Replying", listOf(leaf("only"), hidden))
        assertEquals("only", leafShownInstead(noteHidden, { "false" }, 30)?.setting)
        val text = """
            {"sections":[
              {"section":"account","title":"Account","children":[
                {"group":"g","title":"G","children":[
                  {"text":"Hello","size":-2,"bold":true,"font":"serif"},
                  {"setting":"kept","type":"bool","default":false,"title":"T"},
                  {"line":true}
                ]},
                {"editor":"rules","opens":"screen","settings":[{"text":"In settings","font":"monospace"}],"children":[{"line":true}]}
              ]},
              {"section":"ui","title":"UI","children":[{"line":true,"visibleWhen":{"setting":"kept","equals":true}}]}
            ]}
        """.trimIndent()
        val doc = parseSettingsDocument(text)
        val leaves = ArrayList<String>()
        fun walk(node: TreeNode) {
            when (node) {
                is TreeNode.Leaf -> leaves.add(node.leaf.setting)
                is TreeNode.Group -> node.children.forEach { walk(it) }
                is TreeNode.Page -> node.children.forEach { walk(it) }
                is TreeNode.Editor -> node.children.forEach { walk(it) }
                is TreeNode.Note -> Unit
            }
        }
        doc.pages.forEach { walk(it) }
        assertEquals(listOf("kept"), leaves)
        val account = doc.pages[0] as TreeNode.Page
        val parsed = (account.children[0] as TreeNode.Group).children[0] as TreeNode.Note
        assertEquals("Hello", parsed.text)
        assertEquals("Hello", parsed.english)
        assertEquals(-2, parsed.size)
        assertTrue(parsed.bold)
        assertEquals("serif", parsed.font)
        assertFalse(parsed.line)
        val editor = account.children[1] as TreeNode.Editor
        val inSettings = editor.children[0] as TreeNode.Note
        assertEquals("monospace", inSettings.font)
        assertEquals("In settings", inSettings.english)
        val uiLine = (doc.pages[1] as TreeNode.Page).children[0] as TreeNode.Note
        assertTrue(uiLine.line)
        assertFalse(childDrawn(uiLine, { "false" }, 30))
        assertTrue(childDrawn(uiLine, { "true" }, 30))
        val parsedGroup = account.children[0] as TreeNode.Group
        assertNull(leafShownInstead(parsedGroup, { "" }, 30))
    }

    @Test
    fun noteSizeStepsToTheNextLargerOrSmallerThemeStyle() {
        val scale = listOf(
            TextStyle(fontSize = 57.sp),
            TextStyle(fontSize = 45.sp),
            TextStyle(fontSize = 36.sp),
            TextStyle(fontSize = 32.sp),
            TextStyle(fontSize = 28.sp),
            TextStyle(fontSize = 24.sp),
            TextStyle(fontSize = 22.sp),
            TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            TextStyle(fontSize = 16.sp),
            TextStyle(fontSize = 14.sp),
            TextStyle(fontSize = 12.sp),
            TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            TextStyle(fontSize = 11.sp),
        )
        val anchor = scale[9]
        assertSame(anchor, steppedThemeStyle(scale, anchor, 0))
        assertSame(scale[6], steppedThemeStyle(scale, anchor, 1))
        assertSame(scale[5], steppedThemeStyle(scale, anchor, 2))
        assertSame(scale[10], steppedThemeStyle(scale, anchor, -1))
        assertSame(scale[11], steppedThemeStyle(scale, anchor, -2))
        assertTrue(steppedThemeStyle(scale, anchor, 1).fontSize > anchor.fontSize)
        assertTrue(steppedThemeStyle(scale, anchor, -1).fontSize < anchor.fontSize)
        assertSame(scale.first(), steppedThemeStyle(scale, anchor, 9))
        assertSame(scale.last(), steppedThemeStyle(scale, anchor, -9))
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
            "monospace",
            "serif",
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
