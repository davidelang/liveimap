package org.dlang.liveimap.settings

/** Enter in a single-line settings field: commit on key-down, swallow key-up, so it never reaches navigation. */
enum class LineKey { Commit, Swallow, Pass }

fun lineFieldKey(isEnter: Boolean, keyDown: Boolean, singleLine: Boolean): LineKey = when {
    !isEnter || !singleLine -> LineKey.Pass
    keyDown -> LineKey.Commit
    else -> LineKey.Swallow
}
