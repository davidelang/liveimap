package org.dlang.liveimap.engine

/**
 * F52: registrable domain (eTLD+1) per the Public Suffix List, bundled as a Java resource so app and JVM tests
 * share it. Standard algorithm: the longest matching rule wins, "!" exceptions beat wildcards, the default rule
 * is "*". Does not open a socket.
 */
object PublicSuffix {
    private val rules: Set<String> by lazy {
        val stream = PublicSuffix::class.java.getResourceAsStream("public_suffix_list.dat")
            ?: return@lazy emptySet()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }.map { it.lowercase() }.toHashSet()
        }
    }

    /** Null for an IP literal, a bare suffix or an empty name. */
    fun registrableDomain(host: String): String? {
        val name = java.net.IDN.toUnicode(host.trim().trimEnd('.').lowercase(), java.net.IDN.ALLOW_UNASSIGNED)
        if (name.isEmpty() || name.all { it.isDigit() || it == '.' } || ':' in name) return null
        val labels = name.split('.')
        if (labels.any { it.isEmpty() }) return null
        var suffixLen = 1  // default rule "*"
        for (i in labels.indices) {  // longest candidate first: the first hit is the longest match
            val n = labels.size - i
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if ("!$candidate" in rules) {
                suffixLen = n - 1
                break
            }
            val wild = if (n > 1) "*." + labels.subList(i + 1, labels.size).joinToString(".") else null
            if (candidate in rules || (wild != null && wild in rules)) {
                suffixLen = n
                break
            }
        }
        if (labels.size <= suffixLen) return null
        return labels.subList(labels.size - suffixLen - 1, labels.size).joinToString(".")
    }
}

/**
 * F52: the IMAP login may be offered to the SMTP server only when both hosts are the same, or share one
 * registrable domain (imap.example.co.uk / smtp.example.co.uk, never example.co.uk / evil.co.uk).
 */
fun smtpMayReuseImapLogin(imapHost: String, smtpHost: String): Boolean {
    val a = imapHost.trim().trimEnd('.')
    val b = smtpHost.trim().trimEnd('.')
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.equals(b, ignoreCase = true)) return true
    val ra = PublicSuffix.registrableDomain(a) ?: return false
    return ra == PublicSuffix.registrableDomain(b)
}
