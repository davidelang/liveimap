package org.dlang.liveimap.session

/** Native watch start error when the server closed the connection on IDLE instead of answering (liveimap_jni.cpp). */
const val IdleDroppedText = "IDLE dropped by server"

/** True when IDLE failed because the server dropped the connection: the app then polls on a timer instead. */
fun idleDroppedByServer(failureText: String?): Boolean = failureText?.startsWith(IdleDroppedText) == true

/** Poll interval for the IDLE fallback: the account's poll setting, or 60 s when polling is not set up. */
fun idleFallbackSeconds(pollForNewMail: Boolean, pollSeconds: Int): Int = if (pollForNewMail) pollSeconds else 60
