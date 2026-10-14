package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class KeepCopyUntilSubmittedTest {
    private val copy = DeviceCopy("id1", false, "Sent", listOf("a@b"), "m".toByteArray())

    @Test
    fun successDeletesTheCopyAfterTheUncertainWrite() {
        val writes = mutableListOf<Boolean>(); val deletes = mutableListOf<String>()
        val r = keepCopyUntilSubmitted(copy, { writes += it.uncertain; true }, { deletes += it }) { "S1" }
        assertEquals("S1", r)
        assertEquals(listOf(true), writes) // written "may be sent" before the send, so interruption keeps it
        assertEquals(listOf("id1"), deletes)
    }

    @Test
    fun failureKeepsAPlainUnsentCopyAndRethrows() {
        val writes = mutableListOf<Boolean>(); val deletes = mutableListOf<String>()
        try {
            keepCopyUntilSubmitted(copy, { writes += it.uncertain; true }, { deletes += it }) { throw IllegalStateException("refused") }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("refused", e.message)
        }
        assertEquals(listOf(true, false), writes)
        assertEquals(emptyList<String>(), deletes)
    }

    @Test
    fun cancellationLeavesTheMaybeSentCopy() {
        val writes = mutableListOf<Boolean>()
        try {
            keepCopyUntilSubmitted(copy, { writes += it.uncertain; true }, { fail("deleted") }) { throw kotlinx.coroutines.CancellationException("x") }
            fail()
        } catch (_: kotlinx.coroutines.CancellationException) {}
        assertEquals(listOf(true), writes) // interrupted mid-send: kept, marked "may be sent"
    }
}
