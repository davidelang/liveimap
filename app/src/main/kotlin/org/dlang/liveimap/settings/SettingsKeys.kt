package org.dlang.liveimap.settings

/**
 * Enter in a single-line settings field: commit on key-down; on key-up consume it and only then clear focus, so
 * the key-up can never reach (and activate) the element that gets focus next.
 */
enum class LineKey { Commit, Swallow, Pass }

fun lineFieldKey(isEnter: Boolean, keyDown: Boolean, singleLine: Boolean): LineKey = when {
    !isEnter || !singleLine -> LineKey.Pass
    keyDown -> LineKey.Commit
    else -> LineKey.Swallow
}
