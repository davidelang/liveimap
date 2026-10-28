package org.dlang.liveimap.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStringsTest {
    @Test
    fun everyGeneratedNameIsInStringsXml() {
        val xml = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.isFile }
            .readText()
        for (name in SettingsTreeGenerated.resourceNames) {
            assertTrue(name, xml.contains("name=\"$name\""))
        }
    }
}
