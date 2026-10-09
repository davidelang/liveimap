package org.dlang.liveimap.smoke

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.dlang.liveimap.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val launchIntent =
        Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        ).apply {
            putExtra(SMOKE_EXTRA, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    @get:Rule
    val rule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(launchIntent),
        activityProvider = { scenarioRule ->
            var activity: MainActivity? = null
            scenarioRule.scenario.onActivity { activity = it }
            checkNotNull(activity)
        },
    )

    @Test
    fun launchShowsFolders() {
        rule.onNodeWithTag("smoke-folders").assertIsDisplayed()
        assertEquals(
            listOf(
                "launch",
                "folder-list",
                "open-inbox",
                "open-message",
                "compose-discard",
                "settings-back",
            ),
            smokeSteps(),
        )
    }
}
