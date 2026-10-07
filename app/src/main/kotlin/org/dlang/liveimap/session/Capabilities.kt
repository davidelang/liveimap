package org.dlang.liveimap.session

class Capabilities private constructor(
    val imap4rev1: Boolean,
    val imap4rev2: Boolean,
    val namespace: Boolean,
    val uidPlus: Boolean,
    val idle: Boolean,
    val unselect: Boolean,
    val sort: Boolean,
    val threadReferences: Boolean,
    val threadOrderedSubject: Boolean,
    val move: Boolean,
    val esearch: Boolean,
    val listExtended: Boolean,
    val listStatus: Boolean,
    val esort: Boolean,
    val sortDisplay: Boolean,
    val preview: Boolean,
    val binary: Boolean,
    val qresync: Boolean,
    val condstore: Boolean,
    val compressDeflate: Boolean,
    val names: Set<String>,
) {
    fun moveKind(): String = if (move) "Move" else "CopyThenDelete"

    fun listKind(unreadCounts: Boolean): String = when {
        !listExtended -> "Plain"
        !listStatus -> "Extended"
        unreadCounts -> "ExtendedWithStatus"
        else -> "ExtendedWithMessages"
    }

    fun resyncKind(): String = when {
        qresync -> "Qresync"
        condstore -> "Condstore"
        else -> "FullSelect"
    }

    fun searchKind(): String = if (esearch) "Esearch" else "UidSearch"

    fun sortKind(): String = if (esort) "Esort" else "UidSort"

    fun imapSortKey(token: String): String {
        if (!sortDisplay) return token
        return when (token) {
            "FROM" -> "DISPLAYFROM"
            "TO" -> "DISPLAYTO"
            else -> token
        }
    }

    fun previewKind(): String = if (preview) "Preview" else "BodyPeek"

    fun fetchKind(): String = if (binary) "BinaryPeek" else "BodyPeek"

    companion object {
        fun parse(line: String): Capabilities {
            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val have = tokens.map { it.uppercase() }.toSet()
            fun has(name: String) = name.uppercase() in have
            return Capabilities(
                imap4rev1 = has("IMAP4REV1"),
                imap4rev2 = has("IMAP4REV2"),
                namespace = has("NAMESPACE"),
                uidPlus = has("UIDPLUS"),
                idle = has("IDLE"),
                unselect = has("UNSELECT"),
                sort = has("SORT"),
                threadReferences = has("THREAD=REFERENCES"),
                threadOrderedSubject = has("THREAD=ORDEREDSUBJECT"),
                move = has("MOVE"),
                esearch = has("ESEARCH"),
                listExtended = has("LIST-EXTENDED"),
                listStatus = has("LIST-STATUS"),
                esort = has("ESORT"),
                sortDisplay = has("SORT=DISPLAY"),
                preview = has("PREVIEW"),
                binary = has("BINARY"),
                qresync = has("QRESYNC"),
                condstore = has("CONDSTORE"),
                compressDeflate = has("COMPRESS=DEFLATE"),
                names = tokens.toSet(),
            )
        }
    }
}
