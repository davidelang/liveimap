package org.dlang.liveimap.smoke

import android.content.Intent

const val SMOKE_EXTRA = "org.dlang.liveimap.SMOKE"

fun smokeLaunch(intent: Intent?): Boolean =
    intent?.getBooleanExtra(SMOKE_EXTRA, false) == true

fun smokeSteps(): List<String> = listOf(
    "launch",
    "folder-list",
    "open-inbox",
    "open-message",
    "compose-discard",
    "settings-back",
)
