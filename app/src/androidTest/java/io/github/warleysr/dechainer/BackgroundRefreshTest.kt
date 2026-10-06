package io.github.warleysr.dechainer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.warleysr.dechainer.clock.TrustedClock
import io.github.warleysr.dechainer.day.DayWindow
import io.github.warleysr.dechainer.day.GoalState
import io.github.warleysr.dechainer.day.NewGoal
import io.github.warleysr.dechainer.day.ResolvedBy
import io.github.warleysr.dechainer.screens.ReportsScreen
import io.github.warleysr.dechainer.screens.TodayScreen
import io.github.warleysr.dechainer.store.Store
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class BackgroundRefreshTest {
    @get:Rule val compose = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun background(write: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try { executor.submit(write).get() } finally { executor.shutdown() }
    }

    @Test fun savedReportAppearsWhileReportsIsOpen() {
        val zone = TrustedClock.zone()
        val date = DayWindow.dateOf(TrustedClock.now(ctx), zone).minusDays(14)
        val start = DayWindow.startOf(date, zone)
        val end = DayWindow.startOf(date.plusDays(7), zone)
        val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val title = ctx.getString(R.string.reports_week, date.format(fmt), date.plusDays(6).format(fmt))
        compose.setContent { MaterialTheme { ReportsScreen(null, null, {}) } }
        compose.waitForIdle()
        background { Store.reports(ctx).insert(9999, start, end, TrustedClock.now(ctx), "Background report saved", "{}") }
        compose.waitUntil(15_000) {
            runCatching {
                compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(title))
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText("Background report saved").assertIsDisplayed()
    }

    @Test fun backgroundGoalResolutionRefreshesOpenChecklist() {
        val zone = TrustedClock.zone()
        val now = TrustedClock.now(ctx)
        val date = DayWindow.dateOf(now, zone)
        background { Store.days(ctx).savePlan(date, listOf(NewGoal("Refresh target"), NewGoal("Read"), NewGoal("Walk")), now) }
        val goal = Store.days(ctx).goals(date).first()
        val action = ctx.getString(R.string.today_mark_done, goal.text)
        compose.setContent { MaterialTheme { TodayScreen() } }
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(action).fetchSemanticsNodes().isNotEmpty() }
        background { Store.days(ctx).setGoal(goal.id, GoalState.DONE, ResolvedBy.AUTO) }
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(action).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(action).assertDoesNotExist()
        compose.onNodeWithText("Refresh target").assertIsDisplayed()
    }
}
