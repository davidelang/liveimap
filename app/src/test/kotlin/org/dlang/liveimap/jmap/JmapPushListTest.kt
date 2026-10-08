package org.dlang.liveimap.jmap

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class JmapPushListTest {
    @Test
    fun emailGetStateIsKept() {
        val page = parseJmapMessages(
            """{"methodResponses":[["Email/get",{"state":"s1","list":[]},"1"]]}""",
        )
        assertNull(page.total)
        assertEquals(emptyList<JmapMessage>(), page.messages)
        assertEquals("s1", page.emailState)
    }

    @Test
    fun missingOrBlankStateIsNull() {
        val missing = """{"methodResponses":[["Email/get",{"list":[]},"1"]]}"""
        assertNull(parseJmapMessages(missing).emailState)
        for (state in listOf("", " ")) {
            val text = """{"methodResponses":[["Email/get",{"state":"$state","list":[]},"1"]]}"""
            assertEquals(state, null, parseJmapMessages(text).emailState)
        }
        val tab = "{\"methodResponses\":[[\"Email/get\",{\"state\":\"\\t\",\"list\":[]},\"1\"]]}"
        val newline = "{\"methodResponses\":[[\"Email/get\",{\"state\":\"\\n\",\"list\":[]},\"1\"]]}"
        assertNull(parseJmapMessages(tab).emailState)
        assertNull(parseJmapMessages(newline).emailState)
    }

    @Test
    fun numericStateIsNotText() {
        val text = """{"methodResponses":[["Email/get",{"state":1,"list":[]},"1"]]}"""
        assertPushListFails("jmap message state is not text") { parseJmapMessages(text) }
    }

    @Test
    fun applyPushPostsOnlyWhenTheEventStateDiffers() {
        val same = PushListGate(200, stateThenPing("s1"))
        val sameModel = pushListModel(same)
        assertNull(sameModel.applyPush("s1", same::open))
        assertEquals(listOf(jmapEventSourceUrl(eventUrl)), same.opens)
        assertEquals(emptyList<String>(), same.bodies)

        val changed = PushListGate(200, stateThenPing("s2"))
        val changedModel = pushListModel(changed)
        val result = changedModel.applyPush("s1", changed::open)
        assertEquals("s2", result?.newState)
        assertEquals(listOf("e2"), result?.created)
        assertEquals(listOf(jmapEmailChangesRequest("A1", "s1")), changed.bodies)
        assertEquals(listOf(jmapEventSourceUrl(eventUrl)), changed.opens)
    }

    @Test
    fun blankUsernameDoesNotReturnAnOpener() {
        val gate = PushListGate(200, stateThenPing("s2"))
        val model = JmapFolderScreenModel(
            pushListSession(),
            " ",
            "secret",
            "pin",
            gate::post,
            { _: String, _: List<ByteArray>, _: String -> "" },
        )
        assertPushListFails("jmap username is empty") { model.pushOpen() }
        assertEquals(emptyList<String>(), gate.opens)
        assertEquals(emptyList<String>(), gate.bodies)
    }

    @Test
    fun oneReadReloadsCreatedOrUpdatedAndSkipsTheSameState() {
        val created = PushListGate(200, stateThenPing("s2"))
        val createdModel = pushListModel(created)
        val push = JmapListPush()
        val page = pushPage("s1", "e0", "e1")
        var reloads = 0
        val shown = jmapReadMessageListPush(
            page,
            "mb1",
            push,
            apply = { state -> createdModel.applyPush(state, created::open) },
            reload = {
                reloads += 1
                pushPage("s2", "e2")
            },
            stillOpen = { true },
        )
        assertEquals(1, reloads)
        assertEquals(listOf("e2"), shown.messages.map { it.id })
        assertEquals("s2", shown.emailState)
        assertEquals("s2", push.recorded)
        assertEquals(listOf(jmapEmailChangesRequest("A1", "s1")), created.bodies)
        val again = jmapReadMessageListPush(
            shown,
            "mb1",
            push,
            apply = { state -> createdModel.applyPush(state, created::open) },
            reload = {
                reloads += 1
                pushPage("s9")
            },
            stillOpen = { true },
        )
        assertSame(shown, again)
        assertEquals(1, reloads)
        assertEquals(1, created.bodies.size)

        val updatedPush = JmapListPush()
        var updatedReloads = 0
        val updatedPage = jmapReadMessageListPush(
            pushPage("s1", "e1"),
            "mb1",
            updatedPush,
            apply = {
                JmapEmailChanges("s1", "s4", false, emptyList(), listOf("e1"), emptyList())
            },
            reload = {
                updatedReloads += 1
                pushPage("s4", "e1")
            },
            stillOpen = { true },
        )
        assertEquals(1, updatedReloads)
        assertEquals("s4", updatedPage.emailState)
        assertNull(updatedPush.begin(updatedPage.emailState))
    }

    @Test
    fun destroyedIdsLeaveTheListAndTheNewStateIsNotReadAgain() {
        val push = JmapListPush()
        val page = pushPage("s1", "e0", "e1")
        var reloads = 0
        val shown = jmapReadMessageListPush(
            page,
            "mb1",
            push,
            apply = {
                JmapEmailChanges("s1", "s3", false, emptyList(), emptyList(), listOf("e0"))
            },
            reload = {
                reloads += 1
                pushPage("s9")
            },
            stillOpen = { true },
        )
        assertEquals(0, reloads)
        assertEquals(listOf("e1"), shown.messages.map { it.id })
        assertEquals("s3", shown.emailState)
        assertEquals("s3", push.recorded)
        assertNull(push.begin("s3"))
    }

    @Test
    fun nullResultLeavesTheMessages() {
        val push = JmapListPush()
        val page = pushPage("s1", "e1")
        val shown = jmapReadMessageListPush(
            page,
            "mb1",
            push,
            apply = { null },
            reload = { throw AssertionError("reload") },
            stillOpen = { true },
        )
        assertSame(page, shown)
        assertEquals(listOf("e1"), shown.messages.map { it.id })
        assertNull(push.begin("s1"))
    }

    @Test
    fun pushStatus401KeepsTheListAndDoesNotGiveUp() {
        val gate = PushListGate(401, "")
        val model = pushListModel(gate)
        val page = pushPage("s1", "e0", "e1")
        val push = JmapListPush()
        var gaveUp = false
        val shown = try {
            jmapReadMessageListPush(
                page,
                "mb1",
                push,
                apply = { state -> model.applyPush(state, gate::open) },
                reload = { throw AssertionError("reload") },
                stillOpen = { true },
            )
        } catch (failure: JmapFailure) {
            assertEquals("jmap push status 401", failure.text)
            jmapMessageListKeeps(page, failure)
        }
        assertEquals(false, gaveUp)
        assertEquals(listOf("e0", "e1"), shown.messages.map { it.id })
        assertEquals("s1", shown.emailState)
        assertEquals(emptyList<String>(), gate.bodies)
        assertEquals(listOf(jmapEventSourceUrl(eventUrl)), gate.opens)
        assertNull(push.begin("s1"))
    }

    @Test
    fun folderOpenAndSearchClearTheRecordedState() {
        val push = JmapListPush()
        assertEquals("s1", push.begin("s1"))
        assertNull(push.begin("s1"))
        push.clear()
        assertEquals("s1", push.begin("s1"))
        push.clear()
        val page = pushPage("s1", "e1")
        var reads = 0
        jmapReadMessageListPush(
            page,
            "mb1",
            push,
            apply = {
                reads += 1
                null
            },
            reload = { throw AssertionError("reload") },
            stillOpen = { true },
        )
        assertEquals(1, reads)
    }

    @Test
    fun blankStateDoesNotReadAndCancellationIsRethrown() {
        val push = JmapListPush()
        var reads = 0
        val apply = { _: String ->
            reads += 1
            null
        }
        val missing = pushPage(null, "e1")
        val spaced = pushPage(" ", "e1")
        assertSame(
            missing,
            jmapReadMessageListPush(
                missing,
                "mb1",
                push,
                apply,
                reload = { throw AssertionError("reload") },
                stillOpen = { true },
            ),
        )
        assertSame(
            spaced,
            jmapReadMessageListPush(
                spaced,
                "mb1",
                push,
                apply,
                reload = { throw AssertionError("reload") },
                stillOpen = { true },
            ),
        )
        assertEquals(0, reads)
        try {
            jmapReadMessageListPush(
                pushPage("s1", "e1"),
                "mb1",
                JmapListPush(),
                apply = { throw CancellationException("stop") },
                reload = { throw AssertionError("reload") },
                stillOpen = { true },
            )
            throw AssertionError("expected CancellationException")
        } catch (error: CancellationException) {
            assertEquals("stop", error.message)
        }
    }

    private class PushListGate(
        private val pushCode: Int,
        private val pushText: String,
    ) {
        val opens = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        fun open(url: String): JmapHttpExchange {
            opens.add(url)
            return exchange(pushCode, pushText)
        }

        fun post(url: String, body: String, authorization: String): JmapHttpExchange {
            bodies.add(body)
            return exchange(200, changesSample)
        }

        private fun exchange(code: Int, text: String): JmapHttpExchange {
            return object : JmapHttpExchange {
                override val status: Int = code

                override fun peerDer(): List<ByteArray> = emptyList()

                override fun header(name: String): String? = null

                override fun body(): String = text

                override fun close() = Unit
            }
        }
    }

    private fun pushListModel(gate: PushListGate): JmapFolderScreenModel {
        return JmapFolderScreenModel(
            pushListSession(),
            "user",
            "secret",
            "pin",
            gate::post,
            { _: String, _: List<ByteArray>, _: String -> "" },
        )
    }

    private fun assertPushListFails(text: String, body: () -> Unit) {
        try {
            body()
            throw AssertionError("expected JmapFailure")
        } catch (failure: JmapFailure) {
            assertEquals(text, failure.text)
        }
    }

    private fun pushPage(state: String?, vararg ids: String): JmapMessagePage {
        return JmapMessagePage(
            ids.size.toLong(),
            ids.map { id ->
                JmapMessage(id, "t", "Hello", "Ada", "2026-10-08T00:00:00Z", "Hi", true, 1L)
            },
            state,
        )
    }

    private companion object {
        const val eventUrl = "https://example.com/event"
        val changesSample =
            """{"methodResponses":[["Email/changes",{"oldState":"s1","newState":"s2","hasMoreChanges":false,"created":["e2"],"updated":["e1"],"destroyed":["e0"]},"0"]]}"""

        fun emailState(state: String): String {
            return """{"changed":{"A1":{"Email":"$state"}}}"""
        }

        fun stateThenPing(state: String): String {
            return "event: state\ndata: ${emailState(state)}\n\nevent: ping\ndata: {}\n"
        }

        fun pushListSession(): JmapSession {
            return JmapSession(
                username = "user",
                apiUrl = "https://example.com/jmap/",
                downloadUrl = "https://example.com/download",
                uploadUrl = "https://example.com/upload",
                eventSourceUrl = eventUrl,
                state = "s",
                capabilityIds = setOf(JMAP_MAIL),
                primaryMailAccountId = "A1",
            )
        }
    }
}
