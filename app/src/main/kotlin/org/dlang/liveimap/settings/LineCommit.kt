package org.dlang.liveimap.settings

fun commitText(draft: String, stored: String): String? {
    if (draft == stored) return null
    return draft
}

fun commitPort(draft: String, stored: Int): Int? {
    val port = draft.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    if (port == stored) return null
    return port
}
