package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust

data class JmapFolderRow(
    val id: String,
    val name: String,
    val role: String?,
    val total: Long,
    val unread: Long,
    val hasChildren: Boolean,
    val parentId: String? = null,
)

fun jmapFolderRows(
    mailboxes: List<JmapMailbox>,
    parentIdsWithChildren: Set<String>,
): List<JmapFolderRow> {
    return mailboxes.map { mailbox ->
        JmapFolderRow(
            id = mailbox.id,
            name = mailbox.name,
            role = mailbox.role,
            total = mailbox.totalEmails,
            unread = mailbox.unreadEmails,
            hasChildren = mailbox.id in parentIdsWithChildren,
            parentId = mailbox.parentId,
        )
    }
}

fun jmapFolderLevel(
    session: JmapSession,
    parentId: String?,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): List<JmapFolderRow> {
    val mailboxes = jmapMailboxLevel(session, parentId, username, password, pin, post, trust)
    if (mailboxes.isEmpty()) return emptyList()
    val withChildren = jmapMailboxChildren(
        session,
        mailboxes.map { it.id },
        username,
        password,
        pin,
        post,
        trust,
    )
    return jmapFolderRows(mailboxes, withChildren)
}
