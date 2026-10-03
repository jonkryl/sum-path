package com.jonkryl.sumpath

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.jonkryl.sumpath.core.Puzzle
import kotlin.math.min

/** Equal square touch targets; text fits inside each cell even on API 24 at 200% font size. */
class PuzzleBoard(context: Context) : ViewGroup(context) {
    private var puzzle: Puzzle? = null
    private var path: List<Int> = emptyList()
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(23, 109, 120)
        strokeWidth = dp(5).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    init { setWillNotDraw(false); id = R.id.board }

    fun display(board: Puzzle, route: List<Int>, guidedNext: Int?, onTap: (Int) -> Unit) {
        if (puzzle != board) {
            removeAllViews()
            board.cells.indices.forEach { index ->
                addView(CellView(context).apply {
                    id = cellIds[index]
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onTap(index) }
                })
            }
        }
        puzzle = board
        path = route.toList()
        contentDescription = context.getString(R.string.board_description, board.size)
        val end = route.last()
        board.cells.forEachIndexed { index, cell ->
            val visited = route.indexOf(index)
            val adjacent = kotlin.math.abs(index / board.size - end / board.size) +
                kotlin.math.abs(index % board.size - end % board.size) == 1
            val next = visited == -1 && adjacent && end != board.finish
            val marker = buildList {
                if (index == board.start) add(context.getString(R.string.start_marker))
                if (index == board.finish) add(context.getString(R.string.finish_marker))
                if (cell.key) add(context.getString(R.string.key_marker))
            }.joinToString(" ")
            val state = buildString {
                if (index == board.start) append(context.getString(R.string.cell_start))
                if (index == board.finish) append(context.getString(R.string.cell_finish))
                if (cell.key) append(context.getString(R.string.cell_key))
                if (visited >= 0) append(context.getString(R.string.cell_visited, visited + 1))
                if (index == end) append(context.getString(R.string.cell_current))
                else if (next) append(context.getString(R.string.cell_next))
            }
            (getChildAt(index) as CellView).show(cell.value, marker, visited + 1,
                index == end, visited >= 0, next, index == guidedNext)
            getChildAt(index).contentDescription = context.getString(R.string.cell_description,
                index / board.size + 1, index % board.size + 1, cell.value, state)
            getChildAt(index).isSelected = visited >= 0
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = puzzle?.size ?: 4
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val side = min(available, dp(440))
        setMeasuredDimension(side, side)
        val cell = side / size
        val inner = cell - dp(4)
        for (i in 0 until childCount) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val size = puzzle?.size ?: 4
        val gap = dp(2)
        for (i in 0 until childCount) {
            val left = i % size * width / size + gap
            val top = i / size * height / size + gap
            getChildAt(i).layout(left, top, (i % size + 1) * width / size - gap,
                (i / size + 1) * height / size - gap)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        path.zipWithNext().forEach { (from, to) ->
            val a = getChildAt(from) ?: return@forEach
            val b = getChildAt(to) ?: return@forEach
            canvas.drawLine((a.left + a.right) / 2f, (a.top + a.bottom) / 2f,
                (b.left + b.right) / 2f, (b.top + b.bottom) / 2f, trail)
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private class CellView(context: Context) : ViewGroup(context) {
        private val number = label(24f, true)
        private val marker = label(11f, true)
        private val step = label(9f, false)
        init {
            addView(number); addView(marker); addView(step)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            minimumWidth = dp(48); minimumHeight = dp(48)
        }
        fun show(value: Int, badge: String, order: Int, current: Boolean, visited: Boolean, next: Boolean, guided: Boolean) {
            number.text = value.toString()
            marker.text = badge
            step.text = if (order > 0) order.toString() else ""
            val ink = Color.rgb(24, 45, 70)
            val teal = Color.rgb(23, 109, 120)
            val fill = when { current -> teal; visited -> Color.rgb(213, 234, 224); else -> Color.WHITE }
            val border = when { guided -> Color.rgb(227, 162, 49); current -> ink; next -> teal; else -> Color.rgb(218, 223, 224) }
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(if (guided || current) 3 else 1), border)
            }
            val textColor = if (current) Color.WHITE else ink
            number.setTextColor(textColor); marker.setTextColor(textColor); step.setTextColor(textColor)
        }
        private fun label(size: Float, bold: Boolean) = TextView(context).apply {
            textSize = size
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val textWidth = w - dp(8)
            number.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * .51f).toInt(), MeasureSpec.EXACTLY))
            marker.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * .24f).toInt(), MeasureSpec.EXACTLY))
            step.measure(MeasureSpec.makeMeasureSpec((w * .30f).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * .21f).toInt(), MeasureSpec.EXACTLY))
            fit(number, 24f); fit(marker, 11f); fit(step, 9f)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val x = dp(4)
            val top = (height * .21f).toInt()
            number.layout(x, top, width - x, top + number.measuredHeight)
            marker.layout(x, height - marker.measuredHeight - dp(3), width - x, height - dp(3))
            step.layout(width - step.measuredWidth - dp(3), dp(2), width - dp(3), dp(2) + step.measuredHeight)
        }
        private fun fit(view: TextView, max: Float) {
            val paint = Paint(view.paint)
            var size = max
            val scale = resources.displayMetrics.scaledDensity
            while (size > 6f) {
                paint.textSize = size * scale
                if (paint.measureText(view.text.toString()) <= view.measuredWidth - dp(2) &&
                    paint.fontMetrics.descent - paint.fontMetrics.ascent <= view.measuredHeight) break
                size -= .5f
            }
            view.textSize = size
        }
        private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    }

    companion object {
        val cellIds = intArrayOf(R.id.cell_0, R.id.cell_1, R.id.cell_2, R.id.cell_3, R.id.cell_4,
            R.id.cell_5, R.id.cell_6, R.id.cell_7, R.id.cell_8, R.id.cell_9, R.id.cell_10,
            R.id.cell_11, R.id.cell_12, R.id.cell_13, R.id.cell_14, R.id.cell_15, R.id.cell_16,
            R.id.cell_17, R.id.cell_18, R.id.cell_19, R.id.cell_20, R.id.cell_21, R.id.cell_22,
            R.id.cell_23, R.id.cell_24)
    }
}
