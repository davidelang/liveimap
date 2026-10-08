package org.dlang.liveimap.ui.compose

internal enum class BounceDelivery {
    Submission,
    Smtp,
}

internal fun bounceDelivery(emailSubmission: Boolean): BounceDelivery {
    if (emailSubmission) return BounceDelivery.Submission
    return BounceDelivery.Smtp
}
