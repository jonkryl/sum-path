package com.jonkryl.sumpath

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jonkryl.sumpath.core.Difficulty
import com.jonkryl.sumpath.core.PuzzleRules
import com.jonkryl.sumpath.core.PuzzleSolver
import com.jonkryl.sumpath.core.SearchBudget
import com.jonkryl.sumpath.core.SearchStatus
import com.jonkryl.sumpath.core.SessionSnapshot
import com.jonkryl.sumpath.core.Tutorial
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real taps solve the tutorial and generated puzzles; no winning path is injected into production state. */
@RunWith(AndroidJUnit4::class)
class SumPathJourneyTest {
    @Test fun learnSolveNewLevelUndoRestartDailyAndSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(PuzzleProgressStore.PREFERENCES, 0).edit().clear().commit()
        context.getSharedPreferences("ad_privacy", 0).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val first = awaitPuzzle(scenario)
            assertEquals(Tutorial.puzzle, first.puzzle)
            assertEquals(listOf(Tutorial.puzzle.start), first.path)
            assertFalse(context.getSharedPreferences("ad_privacy", 0).getBoolean("personalized", false))
            onView(withId(R.id.more_button)).perform(click())
            onView(withText(context.getString(R.string.privacy))).perform(click())
            onView(withId(android.R.id.button2)).perform(click())
            assertTrue(context.getSharedPreferences("ad_privacy", 0).contains("personalized"))
            assertFalse(context.getSharedPreferences("ad_privacy", 0).getBoolean("personalized", true))
            assertEquals(first, awaitPuzzle(scenario))
            onView(withId(R.id.message)).check { view, error ->
                if (error != null) throw error
                assertEquals(context.getString(R.string.tutorial_intro), (view as TextView).text.toString())
            }
            saveScreenshot("01-tutorial-api-${Build.VERSION.SDK_INT}.png")
            Tutorial.solution.drop(1).forEach { tapCell(it) }
            assertWon(scenario)
            assertTrue(PuzzleProgressStore(context).tutorialComplete)
            onView(withId(R.id.play_tab)).perform(click())
            val easy = awaitPuzzle(scenario)
            assertEquals(Difficulty.EASY, easy.puzzle.difficulty)
            val easySolution = solve(easy)
            easySolution.drop(1).forEach { tapCell(it) }
            assertWon(scenario)
            assertEquals(1, PuzzleProgressStore(context).solvedCount)
            saveScreenshot("02-real-solved-api-${Build.VERSION.SDK_INT}.png")
            onView(withId(R.id.next_button)).perform(scrollTo(), click())
            val next = awaitPuzzle(scenario)
            assertNotEquals(easy.puzzle.seed, next.puzzle.seed)
            assertNotEquals(easy.puzzle, next.puzzle)
            val route = solve(next)
            tapCell(route[1]); tapCell(route[2])
            onView(withId(R.id.undo_button)).perform(scrollTo(), click())
            scenario.onActivity { assertEquals(route.take(2), it.currentSnapshot()!!.path) }
            tapCell(route[2])
            onView(withId(R.id.restart_button)).perform(scrollTo(), click())
            onView(withId(android.R.id.button1)).perform(click())
            scenario.onActivity { assertEquals(listOf(next.puzzle.start), it.currentSnapshot()!!.path) }
            onView(withId(R.id.difficulty_button)).perform(scrollTo(), click())
            onView(withText(context.getString(R.string.hard))).perform(click())
            val hard = awaitPuzzle(scenario)
            assertEquals(5, hard.puzzle.size)
            assertEquals(3, hard.puzzle.keyCount)
            val hardSolution = solve(hard)
            hardSolution.drop(1).forEach { tapCell(it) }
            assertWon(scenario)
            assertEquals(2, PuzzleProgressStore(context).solvedCount)
            saveScreenshot("03-hard-route-api-${Build.VERSION.SDK_INT}.png")
            onView(withId(R.id.daily_tab)).perform(click())
            val daily = awaitPuzzle(scenario)
            assertEquals(5, daily.puzzle.size)
            val dailySolution = solve(daily)
            dailySolution.drop(1).forEach { tapCell(it) }
            assertWon(scenario)
            assertEquals(3, PuzzleProgressStore(context).solvedCount)
            scenario.recreate()
            val dailyRestored = awaitPuzzle(scenario)
            assertEquals(daily.copy(path = dailySolution), dailyRestored)
            assertTrue(PuzzleRules.validatePath(dailyRestored.puzzle, dailyRestored.path).valid)
            assertEquals(3, PuzzleProgressStore(context).solvedCount)
            // Persist a partly built play route, then a separate test invocation force-stops/restarts the app.
            onView(withId(R.id.play_tab)).perform(click())
            awaitPuzzle(scenario)
            onView(withId(R.id.next_button)).perform(scrollTo(), click())
            val fresh = awaitPuzzle(scenario)
            val freshRoute = solve(fresh)
            tapCell(freshRoute[1]); tapCell(freshRoute[2])
            scenario.onActivity {
                val snapshot = it.currentSnapshot()!!
                assertEquals(freshRoute.take(3), snapshot.path)
                context.getSharedPreferences("journey-proof", 0).edit()
                    .putLong("seed", snapshot.puzzle.seed)
                    .putString("path", snapshot.path.joinToString(","))
                    .putInt("sum", snapshot.path.sumOf { index -> snapshot.puzzle.cells[index].value })
                    .putInt("solved", PuzzleProgressStore(context).solvedCount).commit()
            }
            saveScreenshot("04-saved-route-api-${Build.VERSION.SDK_INT}.png")
        }
    }
}

internal fun tapCell(index: Int) { onView(withId(PuzzleBoard.cellIds[index])).perform(scrollTo(), click()) }
internal fun solve(snapshot: SessionSnapshot): List<Int> {
    val result = PuzzleSolver.solve(snapshot.puzzle, SearchBudget(maxNodes = 1_000_000))
    assertEquals(SearchStatus.SOLVED, result.status)
    val path = requireNotNull(result.solution)
    assertTrue(PuzzleRules.validatePath(snapshot.puzzle, path).valid)
    return path
}
internal fun assertWon(scenario: ActivityScenario<MainActivity>) {
    scenario.onActivity { activity ->
        val state = requireNotNull(activity.currentSnapshot())
        assertTrue(PuzzleRules.validatePath(state.puzzle, state.path).valid)
    }
}
internal fun awaitPuzzle(scenario: ActivityScenario<MainActivity>): SessionSnapshot {
    val deadline = System.nanoTime() + 20_000_000_000L
    while (System.nanoTime() < deadline) {
        var snapshot: SessionSnapshot? = null
        scenario.onActivity { if (!it.isPreparingPuzzle()) snapshot = it.currentSnapshot() }
        if (snapshot != null) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            return snapshot!!
        }
        Thread.sleep(50)
    }
    error("The bounded generator failed to produce a playable puzzle within20s")
}
internal fun saveScreenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
    check(directory.mkdirs() || directory.isDirectory)
    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(directory, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    screenshot.recycle()
}
