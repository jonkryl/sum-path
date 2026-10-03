package com.jonkryl.sumpath

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jonkryl.sumpath.ads.BannerController
import com.jonkryl.sumpath.core.Difficulty
import com.jonkryl.sumpath.core.GameSession
import com.jonkryl.sumpath.core.MoveResult
import com.jonkryl.sumpath.core.PathError
import com.jonkryl.sumpath.core.PuzzleGenerator
import com.jonkryl.sumpath.core.PuzzleSeeds
import com.jonkryl.sumpath.core.SessionSnapshot
import com.jonkryl.sumpath.core.SessionStatus
import com.jonkryl.sumpath.core.Tutorial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private lateinit var store: PuzzleProgressStore
    private lateinit var banner: BannerController
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var board: PuzzleBoard
    private lateinit var sumText: TextView
    private lateinit var remainingText: TextView
    private lateinit var keyText: TextView
    private lateinit var message: TextView
    private lateinit var progressText: TextView
    private lateinit var title: TextView
    private lateinit var undoButton: Button
    private lateinit var restartButton: Button
    private lateinit var nextButton: Button
    private lateinit var difficultyButton: Button
    private val tabs = mutableMapOf<String, Button>()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation: Future<*>? = null
    private var generationToken = 0
    private var session: GameSession? = null
    private var mode = "learn"
    private var levelNumber = 1
    private var dailyDate = today()
    private var loading = false
    private val ink = Color.rgb(24, 45, 70)
    private val cream = Color.rgb(247, 243, 233)
    private val teal = Color.rgb(23, 109, 120)
    private val mint = Color.rgb(213, 234, 224)
    private val amber = Color.rgb(243, 198, 107)
    private val muted = Color.rgb(85, 102, 116)

    /** Read-only puzzle state is useful to accessibility/device tests; contains no secret answer. */
    fun currentSnapshot(): SessionSnapshot? = session?.snapshot()
    fun currentMode(): String = mode
    fun isPreparingPuzzle(): Boolean = loading

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PuzzleProgressStore(this)
        banner = BannerController(this)
        val root = column().apply { setBackgroundColor(cream) }
        configureInsets(root)
        root.addView(header(), matchWrap())
        root.addView(tabBar(), matchWrap().apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), dp(6), dp(16), dp(24))
        }
        content = column()
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val adArea = column().apply {
            setPadding(0, dp(10), 0, 0)
            addView(View(this@MainActivity).apply { setBackgroundColor(Color.rgb(224, 220, 210)) },
                LinearLayout.LayoutParams(-1, dp(1)))
            addView(text(getString(R.string.advertisement), 10f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        val host = FrameLayout(this).apply {
            id = R.id.ad_container
            minimumHeight = dp(64)
            contentDescription = getString(R.string.ad_area)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        adArea.addView(host, matchWrap())
        root.addView(adArea, matchWrap())
        setContentView(root)
        selectMode(store.selectedMode.takeIf { it in listOf("play", "daily", "learn") } ?: "learn")
        banner.attach(host)
    }

    override fun onStart() {
        super.onStart()
        if (::banner.isInitialized) banner.onStart()
    }
    override fun onResume() {
        super.onResume()
        // A fresh calendar day receives a fresh deterministic task, including when the app stayed open.
        if (::store.isInitialized && mode == "daily" && dailyDate != today()) selectMode("daily")
    }
    override fun onStop() {
        persist()
        if (::banner.isInitialized) banner.onStop()
        super.onStop()
    }
    override fun onDestroy() {
        generationToken++
        generation?.cancel(true)
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        if (::banner.isInitialized) banner.destroy()
        super.onDestroy()
    }

    private fun header() = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(12), dp(16), dp(2))
        val label = column().apply {
            addView(text(getString(R.string.app_name), 26f, ink, true))
            addView(text(getString(R.string.tagline), 12f, muted).apply { setPadding(0, dp(3), 0, dp(6)) })
        }
        addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        addView(button("⋯", R.id.more_button, Color.WHITE, ink).apply {
            // This glyph is an icon; its accessible text is the localized description.
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 25f)
            contentDescription = getString(R.string.more)
            setOnClickListener { showMore() }
        }, LinearLayout.LayoutParams(dp(56), dp(52)).apply { marginStart = dp(10) })
    }
    private fun tabBar() = row().apply {
        listOf("play" to R.string.play_tab, "daily" to R.string.daily_tab, "learn" to R.string.learn_tab).forEachIndexed { index, pair ->
            val tab = button(getString(pair.second), intArrayOf(R.id.play_tab, R.id.daily_tab, R.id.learn_tab)[index], Color.WHITE, ink)
            tab.setOnClickListener { if (!loading) selectMode(pair.first) }
            tabs[pair.first] = tab
            addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index < 2) marginEnd = dp(6) })
        }
    }

    private fun selectMode(selected: String, difficulty: Difficulty = store.selectedDifficulty, fresh: Boolean = false) {
        persist()
        dailyDate = today()
        mode = selected
        val chosenDifficulty = when (selected) {
            "learn" -> Difficulty.EASY
            "daily" -> Difficulty.HARD
            else -> difficulty
        }
        levelNumber = store.number(chosenDifficulty)
        if (fresh && mode == "play") levelNumber++
        val restored = if (fresh) null else store.saved(mode, chosenDifficulty, dailyDate)
        if (restored != null) {
            session = restored
            render()
            persist()
            return
        }
        val wasCorrupt = !fresh && store.hasSaved(mode, chosenDifficulty, dailyDate)
        prepare(chosenDifficulty, wasCorrupt)
    }

    private fun prepare(difficulty: Difficulty, wasCorrupt: Boolean) {
        loading = true
        session = null
        render()
        val token = ++generationToken
        generation?.cancel(true)
        val seed = if (mode == "daily") PuzzleSeeds.dailySeed(dailyDate)
            else PuzzleSeeds.levelSeed(difficulty, levelNumber)
        val requestedMode = mode
        generation = worker.submit {
            val result = runCatching {
                if (requestedMode == "learn") GameSession(Tutorial.puzzle)
                else GameSession(PuzzleGenerator.generate(seed, difficulty).puzzle)
            }
            mainHandler.post {
                if (token != generationToken || isDestroyed || isFinishing) return@post
                loading = false
                session = result.getOrNull()
                render()
                if (session != null) {
                    persist()
                    if (wasCorrupt) Toast.makeText(this, R.string.restore_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        tabs.forEach { (key, tab) ->
            tab.background = shape(if (key == mode) ink else Color.WHITE)
            tab.setTextColor(if (key == mode) Color.WHITE else ink)
            tab.isEnabled = !loading
        }
        val current = session
        if (current == null) {
            content.addView(text(getString(if (loading) R.string.loading else R.string.loading_error), 18f, ink).apply {
                setPadding(dp(12), dp(32), dp(12), dp(24))
                gravity = Gravity.CENTER
            })
            if (!loading) content.addView(button(getString(R.string.new_puzzle), R.id.next_button, teal, Color.WHITE).apply {
                setOnClickListener { prepare(when (mode) {
                    "learn" -> Difficulty.EASY
                    "daily" -> Difficulty.HARD
                    else -> store.selectedDifficulty
                }, false) }
            }, matchWrap())
            return
        }
        title = text(levelTitle(current), 17f, ink, true).apply { id = R.id.level_title; setPadding(dp(2), dp(10), dp(2), dp(12)) }
        content.addView(title)
        val status = column().apply {
            background = shape(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        sumText = text("", 23f, ink, true).apply { id = R.id.sum_status }
        remainingText = text("", 13f, muted)
        keyText = text("", 16f, teal, true).apply { id = R.id.keys_status; setPadding(0, dp(6), 0, 0) }
        status.addView(sumText); status.addView(remainingText); status.addView(keyText)
        content.addView(status, matchWrap().apply { bottomMargin = dp(12) })
        message = text("", 15f, ink).apply {
            id = R.id.message
            background = shape(if (mode == "learn") amber else mint)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(message, matchWrap().apply { bottomMargin = dp(10) })
        board = PuzzleBoard(this)
        content.addView(board, matchWrap().apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(text(getString(R.string.legend), 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(8), dp(2), dp(14))
        })
        val actions = row()
        undoButton = button(getString(R.string.undo), R.id.undo_button, Color.WHITE, ink).apply {
            setOnClickListener { current.undo(); persist(); updateRoute() }
        }
        restartButton = button(getString(R.string.restart), R.id.restart_button, Color.WHITE, ink).apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(R.string.restart_title).setMessage(R.string.restart_body)
                    .setPositiveButton(R.string.restart) { _, _ -> current.reset(); persist(); updateRoute() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }
        actions.addView(undoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(restartButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions, matchWrap())
        nextButton = button(getString(R.string.new_puzzle), R.id.next_button, teal, Color.WHITE).apply {
            setOnClickListener {
                when (mode) {
                    "learn", "daily" -> selectMode("play", Difficulty.EASY)
                    else -> {
                        if (current.path.size > 1 && current.status != SessionStatus.WON) {
                            AlertDialog.Builder(this@MainActivity).setTitle(R.string.new_title).setMessage(R.string.new_body)
                                .setPositiveButton(R.string.new_puzzle) { _, _ -> selectMode("play", current.puzzle.difficulty, true) }
                                .setNegativeButton(R.string.cancel, null).show()
                        } else selectMode("play", current.puzzle.difficulty, true)
                    }
                }
            }
        }
        content.addView(nextButton, matchWrap().apply { topMargin = dp(10) })
        difficultyButton = button(getString(R.string.difficulty), R.id.difficulty_button, cream, ink).apply {
            setOnClickListener { showDifficulty() }
        }
        if (mode == "play") content.addView(difficultyButton, matchWrap().apply { topMargin = dp(4) })
        progressText = text(getString(R.string.progress_value, store.solvedCount), 13f, muted).apply {
            id = R.id.progress
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(12), dp(2), dp(4))
        }
        content.addView(progressText)
        content.addView(text(getString(R.string.offline_note), 11f, muted).apply { gravity = Gravity.CENTER })
        updateRoute()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun updateRoute() {
        val current = session ?: return
        sumText.text = getString(R.string.sum_value, current.currentSum, current.puzzle.targetSum)
        val remaining = current.puzzle.targetSum - current.currentSum
        remainingText.text = getString(if (remaining < 0) R.string.over_target else R.string.remaining, kotlin.math.abs(remaining))
        keyText.text = getString(R.string.keys_value, current.collectedKeys, current.puzzle.keyCount)
        undoButton.isEnabled = current.canUndo
        undoButton.alpha = if (current.canUndo) 1f else .45f
        restartButton.isEnabled = current.canUndo
        restartButton.alpha = if (current.canUndo) 1f else .45f
        val won = current.status == SessionStatus.WON
        nextButton.text = getString(if (mode != "play") R.string.play_tab else if (won) R.string.next_puzzle else R.string.new_puzzle)
        progressText.text = getString(R.string.progress_value, store.solvedCount)
        val guided = guidedNext(current)
        message.text = when {
            won && mode == "learn" -> getString(R.string.tutorial_done)
            won && mode == "daily" -> getString(R.string.daily_completed)
            won -> getString(R.string.won) + "\n" + getString(R.string.won_body)
            current.status == SessionStatus.INCORRECT_FINISH -> getString(
                if (current.validation.error == PathError.MISSING_KEYS) R.string.wrong_finish_keys else R.string.wrong_finish_sum,
                if (current.validation.error == PathError.MISSING_KEYS) current.puzzle.keyCount else current.puzzle.targetSum)
            mode == "learn" && guided == null -> getString(R.string.tutorial_free)
            mode == "learn" && current.path.size == 1 -> getString(R.string.tutorial_intro)
            mode == "learn" && current.puzzle.cells[guided!!].key -> getString(R.string.tutorial_key)
            mode == "learn" && guided == current.puzzle.finish -> getString(R.string.tutorial_finish)
            mode == "learn" -> getString(R.string.tutorial_add)
            current.puzzle.neighbors(current.lastCell).all { it in current.path } -> getString(R.string.path_dead_end)
            mode == "daily" -> getString(R.string.daily_description)
            else -> getString(R.string.path_prompt)
        }
        message.background = shape(if (won) mint else if (mode == "learn") amber else Color.WHITE)
        board.display(current.puzzle, current.path, guided) { index ->
            when (current.attemptMove(index)) {
                MoveResult.ADDED, MoveResult.WON, MoveResult.FINISHED_INCORRECT -> {
                    persist()
                    updateRoute()
                    board.announceForAccessibility(sumText.text.toString() + "; " + keyText.text.toString())
                }
                MoveResult.ALREADY_VISITED -> reject(R.string.rejected_repeat)
                MoveResult.ALREADY_FINISHED -> reject(R.string.rejected_finished)
                else -> reject(R.string.rejected_adjacent)
            }
        }
    }
    private fun guidedNext(current: GameSession): Int? {
        if (mode != "learn" || current.status == SessionStatus.WON) return null
        val path = current.path
        return if (path == Tutorial.solution.take(path.size)) Tutorial.solution.getOrNull(path.size) else null
    }
    private fun reject(string: Int) {
        Toast.makeText(this, string, Toast.LENGTH_SHORT).show()
        board.announceForAccessibility(getString(string))
    }
    private fun persist() {
        val current = session ?: return
        if (!store.save(mode, current, levelNumber, dailyDate)) Toast.makeText(this, R.string.save_error, Toast.LENGTH_LONG).show()
    }
    private fun levelTitle(current: GameSession) = when (mode) {
        "learn" -> getString(R.string.tutorial_title)
        "daily" -> getString(R.string.daily_title, dailyDate)
        else -> getString(R.string.classic_title, getString(difficultyName(current.puzzle.difficulty)), levelNumber)
    }
    private fun difficultyName(difficulty: Difficulty) = when (difficulty) {
        Difficulty.EASY -> R.string.easy
        Difficulty.MEDIUM -> R.string.medium
        Difficulty.HARD -> R.string.hard
    }
    private fun showDifficulty() {
        AlertDialog.Builder(this).setTitle(R.string.difficulty).setItems(Difficulty.entries.map { getString(difficultyName(it)) }.toTypedArray()) { _, selected ->
            selectMode("play", Difficulty.entries[selected])
        }.setNeutralButton(R.string.rules) { _, _ ->
            AlertDialog.Builder(this).setTitle(R.string.difficulty).setMessage(R.string.difficulty_body).setPositiveButton(R.string.close, null).show()
        }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun showMore() {
        AlertDialog.Builder(this).setTitle(R.string.more).setItems(arrayOf(getString(R.string.rules), getString(R.string.privacy), getString(R.string.about))) { _, selected ->
            when (selected) {
                0 -> AlertDialog.Builder(this).setTitle(R.string.rules).setMessage(R.string.rules_body).setPositiveButton(R.string.close, null).show()
                1 -> banner.showPrivacyChoice()
                else -> AlertDialog.Builder(this).setTitle(R.string.about).setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME)).setPositiveButton(R.string.close, null).show()
            }
        }.show()
    }
    @Suppress("DEPRECATION")
    private fun configureInsets(root: View) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        root.requestApplyInsets()
    }
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, viewId: Int, color: Int, foreground: Int) = Button(this).apply {
        id = viewId; text = value; textSize = 14f; isAllCaps = false; setTextColor(foreground)
        setTypeface(typeface, Typeface.BOLD); background = shape(color); minHeight = dp(48); minimumHeight = dp(48)
        minWidth = 0; minimumWidth = 0; setPadding(dp(10), dp(8), dp(10), dp(8)); stateListAnimator = null
    }
}
