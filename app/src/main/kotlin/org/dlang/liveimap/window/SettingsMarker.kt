package org.dlang.liveimap.window

/** One instance's marker: its token, its process, and that process's start time. Not written here. */
data class SettingsMarker(val token: String, val pid: Int, val startTime: Long)

/**
 * What one look does. [leave] drops this instance's own marker.
 * [write] is set only for an enter that stays. A read that is not an enter writes nothing.
 * [drop] is the stale markers, plus this instance's own when it leaves.
 */
data class MarkerDecision(
    val leave: Boolean,
    val drop: List<SettingsMarker>,
    val write: SettingsMarker?,
)

/**
 * [written] includes this instance's own marker.
 * [livingStart] maps a live process id to that process's start time. A missing id is dead.
 * A live marker whose start time matches counts. A dead process, or a reused id, is dropped and does not count.
 * Each instance that sees another live marker leaves, so two that see each other both leave.
 */
fun lookAtMarkers(
    written: List<SettingsMarker>,
    self: SettingsMarker,
    livingStart: Map<Int, Long>,
    enter: Boolean,
): MarkerDecision {
    val stale = ArrayList<SettingsMarker>()
    var otherLive = false
    for (marker in written) {
        if (marker == self) continue
        val start = livingStart[marker.pid]
        if (start != null && start == marker.startTime) otherLive = true
        else stale.add(marker)
    }
    val drop = if (otherLive) stale + self else stale
    val write = if (enter && !otherLive) self else null
    return MarkerDecision(leave = otherLive, drop = drop, write = write)
}
