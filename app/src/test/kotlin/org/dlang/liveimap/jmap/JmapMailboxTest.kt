package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JmapMailboxTest {
    @Test
    fun topLevelRequest() {
        assertEquals(topLevel, jmapMailboxLevelRequest("A1", null))
    }

    @Test
    fun childLevelRequest() {
        assertEquals(
            topLevel.replace("\"parentId\":null", "\"parentId\":\"mb1\""),
            jmapMailboxLevelRequest("A1", "mb1"),
        )
    }

    @Test
    fun quotedAccountId() {
        val quoted = topLevel.replace("\"A1\"", "\"a\\\"b\"")
        assertEquals(quoted, jmapMailboxLevelRequest("a\"b", null))
    }

    @Test
    fun blankAccountId() {
        for (account in listOf("", " ", "\t", "\n")) {
            assertFails("jmap account id is empty") { jmapMailboxLevelRequest(account, null) }
            assertFails("jmap account id is empty") { jmapMailboxChildProbe(account, listOf("mb1")) }
        }
    }

    @Test
    fun emptyParentId() {
        assertFails("jmap parent id is empty") { jmapMailboxLevelRequest("A1", "") }
    }

    @Test
    fun childProbe() {
        assertEquals(childProbe, jmapMailboxChildProbe("A1", listOf("mb1", "mb2")))
    }

    @Test
    fun emptyMailboxIds() {
        assertFails("jmap mailbox ids are empty") { jmapMailboxChildProbe("A1", emptyList()) }
    }

    @Test
    fun conditionOrderFollowsTheList() {
        val request = jmapMailboxChildProbe("A1", listOf("mb2", "mb1", "mb3"))
        val conditions = """[{"parentId":"mb2"},{"parentId":"mb1"},{"parentId":"mb3"}]"""
        assertEquals(true, request.contains(conditions))
    }

    @Test
    fun sampleInbox() {
        val mailboxes = parseJmapMailboxes(sampleGet)
        assertEquals(1, mailboxes.size)
        val inbox = mailboxes[0]
        assertEquals("mb1", inbox.id)
        assertEquals("Inbox", inbox.name)
        assertNull(inbox.parentId)
        assertEquals("inbox", inbox.role)
        assertEquals(1L, inbox.sortOrder)
        assertEquals(3L, inbox.totalEmails)
        assertEquals(2L, inbox.unreadEmails)
    }

    @Test
    fun shortObjectDefaultsOptionalFields() {
        val text = getList(
            """{"id":"mb1","name":"Inbox","parentId":null,"role":"inbox","sortOrder":1,"totalEmails":3,"unreadEmails":2},{"id":"mb2","name":"Later"}""",
        )
        val mailboxes = parseJmapMailboxes(text)
        assertEquals(listOf("mb1", "mb2"), mailboxes.map { it.id })
        val later = mailboxes[1]
        assertEquals("Later", later.name)
        assertNull(later.parentId)
        assertNull(later.role)
        assertEquals(0L, later.sortOrder)
        assertEquals(0L, later.totalEmails)
        assertEquals(0L, later.unreadEmails)
    }

    @Test
    fun callIdNeedNotBeOne() {
        val text = sampleGet.replace("\"1\"]]}", "\"9\"]]}")
        val inbox = parseJmapMailboxes(text).single()
        assertEquals("mb1", inbox.id)
        assertEquals("Inbox", inbox.name)
    }

    @Test
    fun blankResponse() {
        assertFails("jmap mailbox response is empty") { parseJmapMailboxes("") }
        assertFails("jmap mailbox response is empty") { parseJmapMailboxes(" \n") }
    }

    @Test
    fun responseNotObject() {
        for (text in listOf("[]", "nope", "{", sampleGet + "x", "1")) {
            assertFails("jmap mailbox response is not an object") { parseJmapMailboxes(text) }
        }
    }

    @Test
    fun lacksGet() {
        assertFails("jmap mailbox response lacks get") { parseJmapMailboxes("{}") }
        assertFails("jmap mailbox response lacks get") {
            parseJmapMailboxes("""{"methodResponses":[["Mailbox/query",{"ids":["mb1"]},"0"]]}""")
        }
    }

    @Test
    fun lacksList() {
        assertFails("jmap mailbox response lacks list") {
            parseJmapMailboxes("""{"methodResponses":[["Mailbox/get",{},"1"]]}""")
        }
        assertFails("jmap mailbox response lacks list") {
            parseJmapMailboxes("""{"methodResponses":[["Mailbox/get",[],"1"]]}""")
        }
    }

    @Test
    fun listNotArray() {
        assertFails("jmap mailbox list is not an array") {
            parseJmapMailboxes("""{"methodResponses":[["Mailbox/get",{"list":{}},"1"]]}""")
        }
    }

    @Test
    fun mailboxNotObject() {
        assertFails("jmap mailbox is not an object") { parseJmapMailboxes(getList("1")) }
        assertFails("jmap mailbox is not an object") { parseJmapMailboxes(getList("null")) }
    }

    @Test
    fun idAndName() {
        assertFails("jmap mailbox lacks id") { parseJmapMailboxes(getList("""{"name":"Inbox"}""")) }
        assertFails("jmap mailbox lacks name") { parseJmapMailboxes(getList("""{"id":"mb1"}""")) }
        assertFails("jmap mailbox id is not text") {
            parseJmapMailboxes(getList("""{"id":1,"name":"Inbox"}"""))
        }
        assertFails("jmap mailbox name is not text") {
            parseJmapMailboxes(getList("""{"id":"mb1","name":false}"""))
        }
    }

    @Test
    fun parentAndRole() {
        val mailbox = parseJmapMailboxes(
            getList("""{"id":"mb1","name":"Inbox","parentId":"mb0","role":null}"""),
        ).single()
        assertEquals("mb0", mailbox.parentId)
        assertNull(mailbox.role)
        val absent = parseJmapMailboxes(getList("""{"id":"mb1","name":"Inbox"}""")).single()
        assertNull(absent.parentId)
        assertNull(absent.role)
        assertFails("jmap mailbox parentId is not text") {
            parseJmapMailboxes(getList("""{"id":"mb1","name":"Inbox","parentId":1}"""))
        }
        assertFails("jmap mailbox role is not text") {
            parseJmapMailboxes(getList("""{"id":"mb1","name":"Inbox","role":true}"""))
        }
    }

    @Test
    fun numbers() {
        val mailbox = parseJmapMailboxes(
            getList(
                """{"id":"mb1","name":"Inbox","sortOrder":0,"totalEmails":9223372036854775807,"unreadEmails":2}""",
            ),
        ).single()
        assertEquals(0L, mailbox.sortOrder)
        assertEquals(Long.MAX_VALUE, mailbox.totalEmails)
        assertEquals(2L, mailbox.unreadEmails)
        for (bad in listOf("1.0", "-1", "1e2", "true", "null", "\"3\"", "9223372036854775808")) {
            assertFails("jmap mailbox number is not a number") {
                parseJmapMailboxes(getList("""{"id":"mb1","name":"Inbox","sortOrder":$bad}"""))
            }
        }
    }

    @Test
    fun extraArrayDoesNotDropTheMailbox() {
        val mailbox = parseJmapMailboxes(
            getList("""{"id":"mb1","name":"Inbox","extra":[1,{"a":false}]}"""),
        ).single()
        assertEquals("mb1", mailbox.id)
        assertEquals(0L, mailbox.sortOrder)
    }

    @Test
    fun parentIds() {
        val ids = jmapMailboxParentIds(
            """[{"parentId":"mb1"},{"parentId":null},{"parentId":"mb1"}]""",
        )
        assertEquals(setOf("mb1"), ids)
    }
}

private fun assertFails(message: String, call: () -> Unit) {
    try {
        call()
        throw AssertionError("expected JmapFailure")
    } catch (failure: JmapFailure) {
        assertEquals(message, failure.text)
    }
}

private fun getList(items: String): String {
    return """{"methodResponses":[["Mailbox/get",{"list":[$items]},"1"]]}"""
}

private val topLevel = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Mailbox/query",{"accountId":"A1","filter":{"parentId":null},"sort":[{"property":"sortOrder","isAscending":true},{"property":"name","isAscending":true}]},"0"],["Mailbox/get",{"accountId":"A1","#ids":{"resultOf":"0","name":"Mailbox/query","path":"/ids"},"properties":["id","name","parentId","role","sortOrder","totalEmails","unreadEmails"]},"1"]]}"""

private val childProbe = """{"using":["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"],"methodCalls":[["Mailbox/query",{"accountId":"A1","filter":{"operator":"OR","conditions":[{"parentId":"mb1"},{"parentId":"mb2"}]}},"0"],["Mailbox/get",{"accountId":"A1","#ids":{"resultOf":"0","name":"Mailbox/query","path":"/ids"},"properties":["parentId"]},"1"]]}"""

private val sampleGet = """{"methodResponses":[["Mailbox/query",{"ids":["mb1"]},"0"],["Mailbox/get",{"list":[{"id":"mb1","name":"Inbox","parentId":null,"role":"inbox","sortOrder":1,"totalEmails":3,"unreadEmails":2}]},"1"]]}"""
