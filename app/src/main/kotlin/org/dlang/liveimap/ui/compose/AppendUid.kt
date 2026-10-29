package org.dlang.liveimap.ui.compose

private val appendUidOk = Regex("APPENDUID\\s+(\\d+)\\s+(\\d+)", RegexOption.IGNORE_CASE)

internal fun appendUidFromOk(line: String): Long {
    val match = appendUidOk.find(line) ?: return 0
    return match.groupValues[2].toLongOrNull() ?: 0
}
