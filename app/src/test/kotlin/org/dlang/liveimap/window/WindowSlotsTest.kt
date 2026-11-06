package org.dlang.liveimap.window

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSlotsTest {
    private val self = SlotRecord(token = "self", pid = 1, startTime = 100L)
    private val other = SlotRecord(token = "other", pid = 2, startTime = 200L)
    private val living = mapOf(self.pid to self.startTime, other.pid to other.startTime)

    @Test
    fun anotherLiveRecordDropsOwnAndTheNextSlotIsClaimed() {
        val slots = List(WindowSlotCount) { slot -> if (slot == 0) listOf(other) else emptyList() }
        assertEquals(1, claimWalk(slots, self, living, 0))
        val seen = lookAtSlot(listOf(self, other), self, living, claim = true)
        assertTrue(seen.leave)
        assertEquals(listOf(self), seen.drop)
        assertNull(seen.write)
    }

    @Test
    fun twoThatSeeEachOtherBothDropTheSlot() {
        val read = listOf(self, other)
        val seenBySelf = lookAtSlot(read, self, living, claim = true)
        val seenByOther = lookAtSlot(read, other, living, claim = true)
        assertTrue(seenBySelf.leave)
        assertTrue(seenByOther.leave)
        assertEquals(listOf(self), seenBySelf.drop)
        assertEquals(listOf(other), seenByOther.drop)
        assertNull(seenBySelf.write)
        assertNull(seenByOther.write)
    }

    @Test
    fun aStaleRecordIsDroppedAndDoesNotCount() {
        val dead = SlotRecord(token = "dead", pid = 3, startTime = 300L)
        val reused = SlotRecord(token = "reused", pid = 4, startTime = 400L)
        val decision = lookAtSlot(
            written = listOf(self, dead, reused),
            self = self,
            livingStart = mapOf(self.pid to self.startTime, reused.pid to 999L),
            claim = true,
        )
        assertFalse(decision.leave)
        assertEquals(listOf(dead, reused), decision.drop)
        assertEquals(self, decision.write)
        val slots = List(WindowSlotCount) { slot ->
            if (slot == 0) listOf(dead, reused) else emptyList()
        }
        assertEquals(0, claimWalk(slots, self, mapOf(self.pid to self.startTime, reused.pid to 999L), 0))
    }

    @Test
    fun aLookThatIsNotAClaimWritesNothing() {
        val dead = SlotRecord(token = "dead", pid = 3, startTime = 300L)
        val decision = lookAtSlot(
            written = listOf(dead, other),
            self = self,
            livingStart = living,
            claim = false,
        )
        assertNull(decision.write)
        assertEquals(listOf(dead), decision.drop)
        assertFalse(self in decision.drop)
        assertFalse(other in decision.drop)
    }

    @Test
    fun theNinthClaimHoldsNothing() {
        val slots = List(WindowSlotCount) { slot ->
            listOf(SlotRecord(token = "p$slot", pid = slot + 10, startTime = 10L))
        }
        val live = (10 until 18).associateWith { 10L }
        assertNull(claimWalk(slots, self, live + (self.pid to self.startTime), 0))
    }

    @Test
    fun aDeadProcessFreesItsSlotOnDisk() {
        val root = tempRoot()
        val board = SlotBoard(root) { pid -> if (pid == self.pid) self.startTime else null }
        val folder = File(root, "slots/0")
        folder.mkdirs()
        File(folder, "dead").writeText("30 300\n")
        assertTrue(board.claim(0, self))
        assertFalse(File(folder, "dead").exists())
        assertTrue(File(folder, self.token).exists())
        board.release(0, self)
        assertEquals(0, board.firstFree(self))
    }

    @Test
    fun aReusedPidDoesNotKeepTheSlotOnDisk() {
        val root = tempRoot()
        val board = SlotBoard(root) { pid -> if (pid == 4) 999L else if (pid == self.pid) self.startTime else null }
        val folder = File(root, "slots/0")
        folder.mkdirs()
        File(folder, "reused").writeText("4 400\n")
        assertTrue(board.claim(0, self))
        assertFalse(File(folder, "reused").exists())
    }

    @Test
    fun eightLiveSlotsLeaveNoFreeSlot() {
        val root = tempRoot()
        val board = SlotBoard(root) { pid -> 10L }
        for (slot in 0 until WindowSlotCount) {
            val folder = File(root, "slots/$slot")
            folder.mkdirs()
            File(folder, "p$slot").writeText("${slot + 10} 10\n")
        }
        assertNull(board.firstFree(self))
        assertFalse(board.claim(0, self))
    }

    @Test
    fun processStartTicksReadsTheFieldAfterComm() {
        val stat = "12 (a) b) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 999 0 0"
        assertEquals(999L, processStartTicks(stat))
        assertNull(processStartTicks("nope"))
    }

    @Test
    fun aLiveMarkerBlocksEnterAndAStaleMarkerIsDeleted() {
        val root = tempRoot()
        val board = MarkerBoard(root) { pid ->
            when (pid) {
                other.pid -> other.startTime
                self.pid -> self.startTime
                else -> null
            }
        }
        val folder = File(root, "markers")
        folder.mkdirs()
        File(folder, "dead").writeText("30 300\n")
        val otherMarker = SettingsMarker(other.token, other.pid, other.startTime)
        assertTrue(board.enter(otherMarker))
        assertFalse(File(folder, "dead").exists())
        val selfMarker = SettingsMarker(self.token, self.pid, self.startTime)
        assertFalse(board.enter(selfMarker))
        assertFalse(File(folder, self.token).exists())
        assertTrue(File(folder, other.token).exists())
        val look = board.look(otherMarker)
        assertTrue(look.allow)
        assertTrue(look.held)
        board.remove(otherMarker)
        assertTrue(board.enter(selfMarker))
    }

    private fun tempRoot(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-slots-" + System.nanoTime())
        root.mkdirs()
        return root
    }
}
