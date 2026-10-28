package org.dlang.liveimap.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConditionTest {
    @Test
    fun equalsEmptySdkAndAll() {
        val read = mapOf("jmapFolders" to "false", "imapHost" to "", "multiPane" to "Off")
        fun holds(json: String) = parseCondition(parseJson(json), "test").holds({ read[it].orEmpty() }, 30)
        assertTrue(holds("""{"setting":"jmapFolders","equals":false}"""))
        assertFalse(holds("""{"setting":"jmapFolders","equals":true}"""))
        assertTrue(holds("""{"setting":"imapHost","empty":true}"""))
        assertTrue(holds("""{"setting":"multiPane","notEquals":"On"}"""))
        assertFalse(holds("""{"sdkAtLeast":31}"""))
        assertTrue(parseCondition(parseJson("""{"sdkAtLeast":31}"""), "test").holds({ "" }, 31))
        assertTrue(holds("""{"all":[{"setting":"jmapFolders","equals":false},{"setting":"imapHost","empty":true}]}"""))
        assertTrue(holds("""{"any":[{"setting":"jmapFolders","equals":true},{"setting":"imapHost","empty":true}]}"""))
    }
}
