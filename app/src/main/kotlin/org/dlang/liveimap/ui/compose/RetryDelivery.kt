package org.dlang.liveimap.ui.compose

internal enum class RetryDelivery {
    Append,
    Submission,
    Smtp,
}

internal fun retryDelivery(emailSubmission: Boolean, appendOnly: Boolean): RetryDelivery {
    if (appendOnly) return RetryDelivery.Append
    if (emailSubmission) return RetryDelivery.Submission
    return RetryDelivery.Smtp
}
