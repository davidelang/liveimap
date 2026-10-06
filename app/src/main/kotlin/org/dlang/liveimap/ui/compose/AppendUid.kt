package org.dlang.liveimap.ui.compose

internal fun appendUidFromOk(line: String): Long {
    val match = Regex("APPENDUID\\s+(\\d+)\\s+(\\d+)", RegexOption.IGNORE_CASE).find(line) ?: return 0
    return match.groupValues[2].toLongOrNull() ?: 0
}
