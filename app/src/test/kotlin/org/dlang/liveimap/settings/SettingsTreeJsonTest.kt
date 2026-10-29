package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** A trailing comma in settings-tree.json is the same tree without that comma. */
class SettingsTreeJsonTest {
    @Test
    fun trailingCommaOnAListOrObject() {
        assertEquals(parseJson("[1,2]"), parseJson("[1,2,]"))
        assertEquals(parseJson("[1,2]"), parseJson("[ 1 , 2 , ]"))
        assertEquals(parseJson("""{"bar":1,"baz":2}"""), parseJson("""{"bar":1,"baz":2,}"""))
        assertEquals(parseJson("""{"bar":1,"baz":2}"""), parseJson("""{ "bar" : 1 , "baz" : 2 , }"""))
        assertEquals(parseJson("[]"), parseJson("[]"))
        assertEquals(parseJson("{}"), parseJson("{}"))
        assertEquals(
            parseJson("""{"a":[1,{"b":2}]}"""),
            parseJson("""{"a":[1,{"b":2,},],}"""),
        )
    }

    @Test
    fun commaNotAfterAValueIsAnError() {
        for (bad in listOf("[,]", "[1,,2]", "{,}", """{"a":1,,}""")) {
            try {
                parseJson(bad)
                fail(bad)
            } catch (_: SettingsTreeParseException) {
            }
        }
    }

    @Test
    fun commaInsideAStringAndTextAfterTheValue() {
        val obj = parseJson("""{"note":"1,2,"}""") as JsonValue.Obj
        assertEquals("1,2,", (obj.fields.getValue("note") as JsonValue.Str).value)
        try {
            parseJson("[1,2,] extra")
            fail("trailing")
        } catch (e: SettingsTreeParseException) {
            assertEquals("settings-tree.json trailing text", e.message)
        }
    }
}
