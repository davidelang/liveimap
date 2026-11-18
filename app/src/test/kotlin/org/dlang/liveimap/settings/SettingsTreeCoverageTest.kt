package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTreeCoverageTest {
    @Test
    fun realTreeSettingNamesMatchTheRegistry() {
        val text = settingsTreeText()
        val doc = parseSettingsDocument(text)
        assertEquals(emptyList<String>(), validateSettingsTree(text))
        val names = HashSet<String>()
        fun walk(node: TreeNode) {
            when (node) {
                is TreeNode.Leaf -> names.add(node.leaf.setting)
                is TreeNode.Group -> node.children.forEach { walk(it) }
                is TreeNode.Page -> node.children.forEach { walk(it) }
                is TreeNode.Editor -> node.children.forEach { walk(it) }
                is TreeNode.Note -> Unit
            }
        }
        doc.pages.forEach { walk(it) }
        installGeneratedSettings()
        val registry = settingsRegistry.keys
        assertEquals(emptySet<String>(), names - registry)
        assertEquals(emptySet<String>(), registry - names)
    }
}
