package org.dlang.liveimap.window

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.dlang.liveimap.MainActivity

/** One activity class per process. The slot number is the process number. */
class WindowActivity1 : MainActivity() {
    override val windowSlot = 1
}

class WindowActivity2 : MainActivity() {
    override val windowSlot = 2
}

class WindowActivity3 : MainActivity() {
    override val windowSlot = 3
}

class WindowActivity4 : MainActivity() {
    override val windowSlot = 4
}

class WindowActivity5 : MainActivity() {
    override val windowSlot = 5
}

class WindowActivity6 : MainActivity() {
    override val windowSlot = 6
}

class WindowActivity7 : MainActivity() {
    override val windowSlot = 7
}

/** The launcher icon. A live window is brought forward. An empty board starts slot 0, which claims it. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = launcherSlot(this)
        startActivity(
            Intent(this, windowClass(slot)).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
        )
        finish()
    }
}
