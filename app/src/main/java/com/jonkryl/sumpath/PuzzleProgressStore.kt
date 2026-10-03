package com.jonkryl.sumpath

import android.content.Context
import com.jonkryl.sumpath.core.Difficulty
import com.jonkryl.sumpath.core.GameSession
import com.jonkryl.sumpath.core.SessionStatus
import com.jonkryl.sumpath.core.SnapshotCodec

/** Small local records. One commit persists route, selection and a win together. */
class PuzzleProgressStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val selectedMode: String get() = preferences.getString("mode", "learn") ?: "learn"
    val selectedDifficulty: Difficulty get() = runCatching {
        Difficulty.valueOf(preferences.getString("difficulty", Difficulty.EASY.name)!!)
    }.getOrDefault(Difficulty.EASY)
    val solvedCount: Int get() = preferences.getStringSet("solved", emptySet())!!.size
    val tutorialComplete: Boolean get() = preferences.getBoolean("tutorial_complete", false)
    fun number(difficulty: Difficulty): Int = preferences.getInt("number_${difficulty.name}", 1).coerceAtLeast(1)
    fun saved(mode: String, difficulty: Difficulty, date: String): GameSession? {
        if (mode == "daily" && preferences.getString("daily_date", null) != date) return null
        val raw = preferences.getString(snapshotKey(mode, difficulty), null) ?: return null
        return SnapshotCodec.decode(raw)?.restore()
    }
    fun hasSaved(mode: String, difficulty: Difficulty, date: String): Boolean {
        if (mode == "daily" && preferences.getString("daily_date", null) != date) return false
        return preferences.contains(snapshotKey(mode, difficulty))
    }
    fun save(mode: String, session: GameSession, number: Int, date: String): Boolean {
        val editor = preferences.edit()
            .putString("mode", mode)
            .putString(snapshotKey(mode, session.puzzle.difficulty), SnapshotCodec.encode(session.snapshot()))
        if (mode == "daily") editor.putString("daily_date", date)
        if (mode == "play") {
            editor.putInt("number_${session.puzzle.difficulty.name}", number)
            editor.putString("difficulty", session.puzzle.difficulty.name)
        }
        if (session.status == SessionStatus.WON) {
            if (mode == "learn") editor.putBoolean("tutorial_complete", true)
            else {
                val solved = preferences.getStringSet("solved", emptySet())!!.toMutableSet()
                solved.add(session.puzzle.id)
                editor.putStringSet("solved", solved)
            }
        }
        return editor.commit()
    }
    private fun snapshotKey(mode: String, difficulty: Difficulty) =
        if (mode == "play") "snapshot_play_${difficulty.name}" else "snapshot_$mode"
    companion object { const val PREFERENCES = "sum-path-progress" }
}
