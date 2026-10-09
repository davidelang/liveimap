package org.dlang.liveimap.jmap

enum class JmapCapabilityFolders {
    Mail,
    Imap,
}

sealed class JmapCapabilitySend {
    data object Smtp : JmapCapabilitySend()
    data object Submission : JmapCapabilitySend()
    data class Fail(val reason: String) : JmapCapabilitySend()
}

data class JmapCapabilityChoice(
    val mail: Boolean,
    val submission: Boolean,
    val folders: JmapCapabilityFolders,
    val send: JmapCapabilitySend,
)

private const val JMAP_SUBMISSION_NOT_OFFERED = "jmap submission is not offered"

fun jmapCapabilityChoice(
    ids: Set<String>,
    emailSubmission: Boolean,
    username: String,
): JmapCapabilityChoice {
    val mail = JMAP_MAIL in ids
    val submission = JMAP_SUBMISSION in ids
    val folders = if (mail && username.isNotBlank()) {
        JmapCapabilityFolders.Mail
    } else {
        JmapCapabilityFolders.Imap
    }
    val send = when {
        !emailSubmission -> JmapCapabilitySend.Smtp
        mail && submission -> JmapCapabilitySend.Submission
        else -> JmapCapabilitySend.Fail(JMAP_SUBMISSION_NOT_OFFERED)
    }
    return JmapCapabilityChoice(
        mail = mail,
        submission = submission,
        folders = folders,
        send = send,
    )
}
