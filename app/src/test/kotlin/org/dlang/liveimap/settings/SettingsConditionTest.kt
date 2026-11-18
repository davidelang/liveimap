package org.dlang.liveimap.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConditionTest {
    @Test
    fun equalsEmptySdkAndAll() {
        val read = mapOf("jmapFolders" to "false", "imapHost" to "", "previewSize" to "Off")
        fun holds(json: String) = parseCondition(parseJson(json), "test").holds({ read[it].orEmpty() }, 30)
        assertTrue(holds("""{"setting":"jmapFolders","equals":false}"""))
        assertFalse(holds("""{"setting":"jmapFolders","equals":true}"""))
        assertTrue(holds("""{"setting":"imapHost","empty":true}"""))
        assertTrue(holds("""{"setting":"previewSize","notEquals":"On"}"""))
        assertFalse(holds("""{"sdkAtLeast":31}"""))
        assertTrue(parseCondition(parseJson("""{"sdkAtLeast":31}"""), "test").holds({ "" }, 31))
        assertTrue(holds("""{"all":[{"setting":"jmapFolders","equals":false},{"setting":"imapHost","empty":true}]}"""))
        assertTrue(holds("""{"any":[{"setting":"jmapFolders","equals":true},{"setting":"imapHost","empty":true}]}"""))
    }

    @Test
    fun emptyTextDoesNotDecode() {
        fun holds(value: String) = parseCondition(parseJson("""{"setting":"value","empty":true}"""), "test").holds({ value }, 30)
        assertTrue(holds(""))
        assertFalse(holds("a@b"))
        assertFalse(holds("imap.lang.hm"))
        assertFalse(holds("H 0.5(list, message)"))
    }
}
