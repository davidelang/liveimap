package org.dlang.liveimap.accounts

import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.TlsMode
import org.dlang.liveimap.settings.newAccountSettings

internal class BackupRecord(
    val settings: Settings,
    val imapPassword: String?,
    val smtpPassword: String?,
    val sievePassword: String?,
    val oauthClientSecret: String? = null,
    val oauthToken: String? = null,
)

internal fun encodeBackupRecord(
    settings: Settings,
    imapPassword: String,
    smtpPassword: String,
    sievePassword: String,
    oauthClientSecret: String = "",
    oauthToken: String = "",
): ByteArray {
    fun esc(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")
    val text = listOf(
        "v=2",
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
        "oauthClientSecret=${esc(oauthClientSecret)}",
        "oauthToken=${esc(oauthToken)}",
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
    val version = values["v"]
    if (version != "1" && version != "2") return null
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
    val oauthSecret = if (version == "2") present("oauthClientSecret") else null
    val oauthToken = if (version == "2") present("oauthToken") else null
    return BackupRecord(settings, present("imapPassword"), present("smtpPassword"), present("sievePassword"), oauthSecret, oauthToken)
}
