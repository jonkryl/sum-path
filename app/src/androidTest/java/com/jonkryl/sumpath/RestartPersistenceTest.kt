package com.jonkryl.sumpath

import android.os.Build
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestartPersistenceTest {
    @Test fun freshProcessRestoresExactRouteAndProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val proof = context.getSharedPreferences("journey-proof", 0)
        assertTrue("First journey must leave real saved state", proof.contains("seed"))
        val expected = proof.getString("path", "")!!.split(',').map { it.toInt() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val restored = awaitPuzzle(scenario)
            assertEquals(proof.getLong("seed", 0), restored.puzzle.seed)
            assertEquals(expected, restored.path)
            assertEquals(proof.getInt("solved", -1), PuzzleProgressStore(context).solvedCount)
            onView(withId(R.id.sum_status)).check { view, error ->
                if (error != null) throw error
                assertEquals(context.getString(R.string.sum_value, proof.getInt("sum", -1), restored.puzzle.targetSum),
                    (view as TextView).text.toString())
            }
            onView(withId(R.id.undo_button)).perform(scrollTo(), click())
            scenario.onActivity { assertEquals(expected.dropLast(1), it.currentSnapshot()!!.path) }
            scenario.recreate()
            assertEquals(expected.dropLast(1), awaitPuzzle(scenario).path)
            saveScreenshot("05-process-restored-api-${Build.VERSION.SDK_INT}.png")
        }
    }
}
