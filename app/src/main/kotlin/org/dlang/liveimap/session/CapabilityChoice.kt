package org.dlang.liveimap.session

data class CapabilityChoice(
    val move: String,
    val listUnread: String,
    val listRead: String,
    val resync: String,
    val search: String,
    val sort: String,
    val fromKey: String,
    val toKey: String,
    val subjectKey: String,
    val preview: String,
    val fetch: String,
    val serverSort: Boolean,
    val namespace: Boolean,
    val uidPlus: Boolean,
    val idle: Boolean,
    val threadReferences: Boolean,
    val threadOrderedSubject: Boolean,
)

fun capabilityChoice(line: String): CapabilityChoice {
    val caps = Capabilities.parse(line)
    return CapabilityChoice(
        move = caps.moveKind(),
        listUnread = caps.listKind(true),
        listRead = caps.listKind(false),
        resync = caps.resyncKind(),
        search = caps.searchKind(),
        sort = caps.sortKind(),
        fromKey = caps.imapSortKey("FROM"),
        toKey = caps.imapSortKey("TO"),
        subjectKey = caps.imapSortKey("SUBJECT"),
        preview = caps.previewKind(),
        fetch = caps.fetchKind(),
        serverSort = caps.sort,
        namespace = caps.namespace,
        uidPlus = caps.uidPlus,
        idle = caps.idle,
        threadReferences = caps.threadReferences,
        threadOrderedSubject = caps.threadOrderedSubject,
    )
}
