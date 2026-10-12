package org.dlang.liveimap.ui.debug

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** F10: the share and debug-report paths must warn that the log holds full message bodies. */
class TrafficShareWarningTest {
    private fun src(path: String): String {
        val f = listOf(File(path), File("app/$path")).first { it.isFile }
        return f.readText()
    }

    @Test
    fun warningTextMentionsBodies() {
        val strings = src("src/main/res/values/strings.xml")
        val line = strings.lines().first { "name=\"traffic_share_warning\"" in it }
        assertTrue(line.contains("full text") && line.contains("bodies"))
    }

    @Test
    fun shareLogGoesThroughConfirmationAndReportShowsWarning() {
        val settings = src("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        val share = settings.indexOf("log.shareFile(")
        assertTrue(share > 0 && settings.lastIndexOf("R.string.traffic_share_warning", share) > 0)
        assertTrue(settings.lines().none { "onClick" in it && "shareFile" in it })
        val review = src("src/main/kotlin/org/dlang/liveimap/ui/debug/DebugReportReview.kt")
        assertTrue(review.contains("R.string.traffic_share_warning"))
    }
}
