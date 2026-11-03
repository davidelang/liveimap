package org.dlang.liveimap.session

/** Native watch start error when the server closed the connection on IDLE instead of answering (liveimap_jni.cpp). */
const val IdleDroppedText = "IDLE dropped by server"

/** True when IDLE failed because the server dropped the connection: the app then polls on a timer instead. */
fun idleDroppedByServer(failureText: String?): Boolean = failureText?.startsWith(IdleDroppedText) == true

/** Poll interval for the IDLE fallback: [pollSeconds] when polling is on, otherwise the off-path wait. */
fun idleFallbackSeconds(pollForNewMail: Boolean, pollSeconds: Int, idleFallbackSeconds: Long): Int =
    if (pollForNewMail) pollSeconds else idleFallbackSeconds.toInt()
