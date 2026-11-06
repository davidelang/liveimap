package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMarkerTest {
    private val self = SettingsMarker(token = "self", pid = 10, startTime = 100L)
    private val other = SettingsMarker(token = "other", pid = 20, startTime = 200L)

    @Test
    fun anotherLiveMarkerDropsOwnAndLeaves() {
        val decision = lookAtMarkers(
            written = listOf(self, other),
            self = self,
            livingStart = mapOf(self.pid to self.startTime, other.pid to other.startTime),
            enter = true,
        )
        assertTrue(decision.leave)
        assertEquals(listOf(self), decision.drop)
        assertNull(decision.write)
    }

    @Test
    fun twoInstancesThatSeeEachOtherBothLeave() {
        val written = listOf(self, other)
        val living = mapOf(self.pid to self.startTime, other.pid to other.startTime)
        val seenBySelf = lookAtMarkers(written, self, living, enter = true)
        val seenByOther = lookAtMarkers(written, other, living, enter = true)
        assertTrue(seenBySelf.leave)
        assertTrue(seenByOther.leave)
        assertEquals(listOf(self), seenBySelf.drop)
        assertEquals(listOf(other), seenByOther.drop)
        assertFalse(other in seenBySelf.drop)
        assertFalse(self in seenByOther.drop)
    }

    @Test
    fun deadOrMismatchedMarkerIsDroppedAndDoesNotCount() {
        val dead = SettingsMarker(token = "dead", pid = 30, startTime = 300L)
        val reused = SettingsMarker(token = "reused", pid = 40, startTime = 400L)
        val decision = lookAtMarkers(
            written = listOf(self, dead, reused),
            self = self,
            livingStart = mapOf(self.pid to self.startTime, reused.pid to 999L),
            enter = true,
        )
        assertFalse(decision.leave)
        assertEquals(listOf(dead, reused), decision.drop)
        assertEquals(self, decision.write)
    }

    @Test
    fun aReadThatIsNotAnEnterWritesNothing() {
        val dead = SettingsMarker(token = "dead", pid = 30, startTime = 300L)
        val decision = lookAtMarkers(
            written = listOf(self, dead, other),
            self = self,
            livingStart = mapOf(self.pid to self.startTime, other.pid to other.startTime),
            enter = false,
        )
        assertTrue(decision.leave)
        assertEquals(listOf(dead, self), decision.drop)
        assertNull(decision.write)
    }
}
