package org.dlang.liveimap.window

import java.io.File

internal const val WindowSlotCount = 8

/** One slot record: the same token, pid, and start time a settings marker uses. */
internal data class SlotRecord(val token: String, val pid: Int, val startTime: Long)

/**
 * [write] is set only for a claim that stays. A look that is not a claim writes nothing.
 * Two live records on one slot each drop their own. A stale record is dropped and does not count.
 */
internal data class SlotDecision(
    val leave: Boolean,
    val drop: List<SlotRecord>,
    val write: SlotRecord?,
)

internal fun lookAtSlot(
    written: List<SlotRecord>,
    self: SlotRecord,
    livingStart: Map<Int, Long>,
    claim: Boolean,
): SlotDecision {
    val stale = ArrayList<SlotRecord>()
    var otherLive = false
    for (record in written) {
        if (record == self) continue
        val start = livingStart[record.pid]
        if (start != null && start == record.startTime) otherLive = true
        else stale.add(record)
    }
    val drop = if (claim && otherLive) stale + self else stale
    val write = if (claim && !otherLive) self else null
    return SlotDecision(leave = otherLive, drop = drop, write = write)
}

/**
 * Claims from [start], then the next slot, and stops at the first slot this record holds.
 * A full board holds nothing. Does not wrap.
 */
internal fun claimWalk(
    slots: List<List<SlotRecord>>,
    self: SlotRecord,
    livingStart: Map<Int, Long>,
    start: Int,
): Int? {
    val board = slots.map { it.toMutableList() }
    var index = start
    while (index < board.size) {
        board[index].add(self)
        val decision = lookAtSlot(board[index], self, livingStart, claim = true)
        board[index].removeAll(decision.drop.toSet())
        if (decision.write != null) return index
        index += 1
    }
    return null
}

/** Field 22 of `/proc/<pid>/stat`, after the comm field that ends at the last `)`. */
internal fun processStartTicks(stat: String): Long? {
    val end = stat.lastIndexOf(')')
    if (end < 0 || end + 1 >= stat.length) return null
    val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
    if (fields.size <= 19) return null
    return fields[19].toLongOrNull()
}

internal fun readProcStart(pid: Int): Long? {
    val file = File("/proc/$pid/stat")
    if (!file.isFile) return null
    return try {
        processStartTicks(file.readText())
    } catch (_: Exception) {
        null
    }
}

private fun writeRecord(file: File, pid: Int, startTime: Long) {
    file.parentFile?.mkdirs()
    file.writeText("$pid $startTime\n")
}

private fun readRecord(file: File, token: String): SlotRecord? {
    if (!file.isFile) return null
    val parts = try {
        file.readText().trim().split(Regex("\\s+"))
    } catch (_: Exception) {
        return null
    }
    if (parts.size < 2) return null
    val pid = parts[0].toIntOrNull() ?: return null
    val start = parts[1].toLongOrNull() ?: return null
    return SlotRecord(token, pid, start)
}

private fun readDir(folder: File): List<SlotRecord> {
    val files = folder.listFiles() ?: return emptyList()
    val out = ArrayList<SlotRecord>()
    for (file in files) {
        if (!file.isFile) continue
        val record = readRecord(file, file.name) ?: continue
        out.add(record)
    }
    return out
}

private fun livingStarts(written: List<SlotRecord>, self: SlotRecord, startOf: (Int) -> Long?): Map<Int, Long> {
    val out = HashMap<Int, Long>()
    for (pid in written.map { it.pid }.toSet()) {
        val start = startOf(pid) ?: continue
        out[pid] = start
    }
    val mine = startOf(self.pid)
    if (mine == null || mine == self.startTime) out[self.pid] = self.startTime
    return out
}

/** Slot files under [root]/slots. A claim writes, reads, then drops what the look names. */
internal class SlotBoard(private val root: File, private val startOf: (Int) -> Long?) {
    private fun dir(slot: Int) = File(root, "slots/$slot")

    fun claim(slot: Int, self: SlotRecord): Boolean {
        val folder = dir(slot)
        folder.mkdirs()
        writeRecord(File(folder, self.token), self.pid, self.startTime)
        val written = readDir(folder)
        val decision = lookAtSlot(written, self, livingStarts(written, self, startOf), claim = true)
        drop(folder, decision.drop)
        return decision.write != null && !decision.leave
    }

    fun firstFree(self: SlotRecord): Int? {
        for (slot in 0 until WindowSlotCount) {
            if (!occupied(slot, self)) return slot
        }
        return null
    }

    fun firstLive(self: SlotRecord): Int? {
        for (slot in 0 until WindowSlotCount) {
            if (occupied(slot, self)) return slot
        }
        return null
    }

    /** The next free slot at or after [start]. Does not wrap, and writes nothing. */
    fun nextFree(start: Int, self: SlotRecord): Int? {
        var slot = start
        while (slot < WindowSlotCount) {
            if (!occupied(slot, self)) return slot
            slot += 1
        }
        return null
    }

    fun release(slot: Int, self: SlotRecord) {
        File(dir(slot), self.token).delete()
    }

    private fun occupied(slot: Int, self: SlotRecord): Boolean {
        val folder = dir(slot)
        val written = readDir(folder)
        val decision = lookAtSlot(written, self, livingStarts(written, self, startOf), claim = false)
        drop(folder, decision.drop)
        val left = readDir(folder)
        val living = livingStarts(left, self, startOf)
        return left.any { record ->
            val start = living[record.pid]
            start != null && start == record.startTime
        }
    }

    private fun drop(folder: File, records: List<SlotRecord>) {
        for (record in records) File(folder, record.token).delete()
    }
}

/** Marker files under [root]/markers. A different directory from the slots. */
internal class MarkerBoard(private val root: File, private val startOf: (Int) -> Long?) {
    private val folder get() = File(root, "markers")

    fun enter(self: SettingsMarker): Boolean {
        folder.mkdirs()
        writeRecord(File(folder, self.token), self.pid, self.startTime)
        val written = readMarkers()
        val decision = lookAtMarkers(written, self, living(written, self), enter = true)
        drop(decision.drop)
        return !decision.leave && decision.write != null
    }

    /** A look that is not an enter. Writes nothing. [held] means this marker was already in the file. */
    fun look(self: SettingsMarker): MarkerLook {
        val written = readMarkers()
        val decision = lookAtMarkers(written, self, living(written, self), enter = false)
        drop(decision.drop)
        return MarkerLook(allow = !decision.leave, held = self in written)
    }

    fun remove(self: SettingsMarker) {
        File(folder, self.token).delete()
    }

    private fun readMarkers(): List<SettingsMarker> {
        return readDir(folder).map { SettingsMarker(it.token, it.pid, it.startTime) }
    }

    private fun living(written: List<SettingsMarker>, self: SettingsMarker): Map<Int, Long> {
        val asSlots = written.map { SlotRecord(it.token, it.pid, it.startTime) }
        return livingStarts(asSlots, SlotRecord(self.token, self.pid, self.startTime), startOf)
    }

    private fun drop(records: List<SettingsMarker>) {
        for (record in records) File(folder, record.token).delete()
    }
}

internal data class MarkerLook(val allow: Boolean, val held: Boolean)

internal class SettingsHeldElsewhere : IllegalStateException("Another window has settings open")
