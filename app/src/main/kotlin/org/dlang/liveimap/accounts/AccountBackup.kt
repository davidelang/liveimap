package org.dlang.liveimap.accounts

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.TlsMode
import org.dlang.liveimap.settings.newAccountSettings

internal class BackupRecord(
    val settings: AccountSettings,
    val imapPassword: String?,
    val smtpPassword: String?,
    val sievePassword: String?,
)

internal fun encodeBackupRecord(
    settings: AccountSettings,
    imapPassword: String,
    smtpPassword: String,
    sievePassword: String,
): ByteArray {
    fun esc(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
    val text = listOf(
        "v=1",
        "email=${esc(settings.email)}",
        "username=${esc(settings.username)}",
        "inbound=${if (settings.jmapFolders) "jmap" else "imap"}",
        "host=${esc(settings.imapHost)}",
        "port=${settings.imapPort}",
        "tls=${settings.tlsMode.name}",
        "smtpHost=${esc(settings.smtpHost)}",
        "smtpPort=${settings.smtpPort}",
        "imapPassword=${esc(imapPassword)}",
        "smtpPassword=${esc(smtpPassword)}",
        "sievePassword=${esc(sievePassword)}",
    ).joinToString("\n")
    return text.toByteArray(Charsets.UTF_8)
}

internal fun decodeBackupRecord(bytes: ByteArray): BackupRecord? {
    val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
    val values = linkedMapOf<String, String>()
    for (line in text.lineSequence()) {
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        values[line.substring(0, eq)] = line.substring(eq + 1).replace("\\n", "\n").replace("\\\\", "\\")
    }
    if (values["v"] != "1") return null
    val tls = runCatching { TlsMode.valueOf(values["tls"].orEmpty()) }.getOrDefault(TlsMode.None)
    val settings = newAccountSettings().copy(
        email = values["email"].orEmpty(),
        username = values["username"].orEmpty(),
        jmapFolders = values["inbound"] == "jmap",
        imapHost = values["host"].orEmpty(),
        imapPort = values["port"]?.toIntOrNull() ?: 993,
        tlsMode = tls,
        smtpHost = values["smtpHost"].orEmpty(),
        smtpPort = values["smtpPort"]?.toIntOrNull() ?: 587,
    )
    fun present(key: String): String? = if (key in values) values.getValue(key) else null
    return BackupRecord(settings, present("imapPassword"), present("smtpPassword"), present("sievePassword"))
}
