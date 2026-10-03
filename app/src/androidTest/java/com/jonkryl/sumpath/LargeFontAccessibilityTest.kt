package com.jonkryl.sumpath

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** CI changes the real system font scale before a fresh process on both API24 and36. */
@RunWith(AndroidJUnit4::class)
class LargeFontAccessibilityTest {
    @Test fun fiveByFiveDigitsAndActionsFitAtTwoHundredPercent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(2.0f, it.resources.configuration.fontScale, .05f) }
            val state = awaitPuzzle(scenario)
            assertEquals(5, state.puzzle.size)
            onView(withId(PuzzleBoard.cellIds[12])).perform(scrollTo())
            onView(withId(R.id.board)).check { view, error ->
                if (error != null) throw error
                val board = view as ViewGroup
                val density = board.resources.displayMetrics.density
                assertEquals(25, board.childCount)
                val ad = (board.context as MainActivity).findViewById<View>(R.id.ad_container)
                val adRect = Rect(); ad.getGlobalVisibleRect(adRect)
                for (index in 0 until board.childCount) {
                    val cell = board.getChildAt(index) as ViewGroup
                    assertTrue("Touch target width >=48dp", cell.width >= density * 48f)
                    assertTrue("Touch target height >=48dp", cell.height >= density * 48f)
                    assertTrue(cell.contentDescription.toString().isNotEmpty())
                    for (child in 0 until cell.childCount) {
                        val label = cell.getChildAt(child) as TextView
                        if (label.text.isEmpty()) continue
                        val layout = requireNotNull(label.layout)
                        assertEquals(1, layout.lineCount)
                        assertTrue("Cell label fits horizontally", layout.getLineWidth(0) <= label.width + 1f)
                        assertTrue("Cell label fits vertically", layout.getLineBottom(0) <= label.height + 1f)
                    }
                    val cellRect = Rect()
                    if (cell.getGlobalVisibleRect(cellRect)) assertFalse("Banner cannot overlap cells", Rect.intersects(adRect, cellRect))
                }
            }
            saveScreenshot("06-large-font-board-api-${Build.VERSION.SDK_INT}.png")
            onView(withId(R.id.undo_button)).perform(scrollTo()).check { view, error ->
                if (error != null) throw error
                assertTrue(view!!.height >= view.resources.displayMetrics.density * 48f)
            }.perform(click())
            assertEquals(listOf(state.puzzle.start), awaitPuzzle(scenario).path)
            saveScreenshot("07-large-font-actions-api-${Build.VERSION.SDK_INT}.png")
        }
    }
}
