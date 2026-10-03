package com.jonkryl.sumpath.core

/** Values are counted once, including the start and finish. Every marked key is mandatory. */
data class Cell(val value: Int, val key: Boolean = false) {
    init { require(value in 1..99) { "Cell values must be positive and at most 99" } }
}

enum class Difficulty(val size: Int, val keyCount: Int, val minimumLength: Int, val maximumLength: Int) {
    EASY(4, 1, 6, 9),
    MEDIUM(4, 2, 8, 12),
    HARD(5, 3, 11, 16)
}

data class Puzzle(
    val size: Int,
    val seed: Long,
    val difficulty: Difficulty,
    val cells: List<Cell>,
    val start: Int,
    val finish: Int,
    val targetSum: Int
) {
    init {
        require(size == 4 || size == 5) { "Only 4 x 4 and 5 x 5 boards are supported" }
        require(cells.size == size * size)
        require(start in cells.indices && finish in cells.indices && start != finish)
        require(targetSum in 1..cells.sumOf { it.value })
    }

    val keyCount: Int get() = cells.count { it.key }
    val id: String get() = "v1:${difficulty.name}:$seed"

    fun adjacent(first: Int, second: Int): Boolean =
        first in cells.indices && second in cells.indices &&
            kotlin.math.abs(first / size - second / size) +
            kotlin.math.abs(first % size - second % size) == 1

    fun neighbors(index: Int): List<Int> {
        require(index in cells.indices)
        // Capture the board dimension: buildList's receiver also has a `size` property.
        val boardSize = size
        val row = index / boardSize
        val column = index % boardSize
        return buildList(4) {
            if (row > 0) add(index - boardSize)
            if (column > 0) add(index - 1)
            if (column + 1 < boardSize) add(index + 1)
            if (row + 1 < boardSize) add(index + boardSize)
        }
    }
}

enum class PathError {
    EMPTY, WRONG_START, OUT_OF_BOUNDS, REPEATED_CELL, NOT_ORTHOGONAL,
    FINISH_BEFORE_END, NOT_FINISHED, WRONG_SUM, MISSING_KEYS
}

data class PathValidation(val valid: Boolean, val error: PathError? = null)

object PuzzleRules {
    /** Structural validation permits an unfinished path or an incorrect finish for undo/recovery. */
    fun validatePrefix(puzzle: Puzzle, path: List<Int>): PathValidation {
        if (path.isEmpty()) return PathValidation(false, PathError.EMPTY)
        if (path.first() != puzzle.start) return PathValidation(false, PathError.WRONG_START)
        var visited = 0L
        path.forEachIndexed { position, index ->
            if (index !in puzzle.cells.indices) return PathValidation(false, PathError.OUT_OF_BOUNDS)
            val bit = 1L shl index
            if (visited and bit != 0L) return PathValidation(false, PathError.REPEATED_CELL)
            if (position > 0 && !puzzle.adjacent(path[position - 1], index)) {
                return PathValidation(false, PathError.NOT_ORTHOGONAL)
            }
            if (index == puzzle.finish && position != path.lastIndex) {
                return PathValidation(false, PathError.FINISH_BEFORE_END)
            }
            visited = visited or bit
        }
        return PathValidation(true)
    }

    /** All real solutions are accepted; no generated witness is consulted here. */
    fun validatePath(puzzle: Puzzle, path: List<Int>): PathValidation {
        val prefix = validatePrefix(puzzle, path)
        if (!prefix.valid) return prefix
        if (path.last() != puzzle.finish) return PathValidation(false, PathError.NOT_FINISHED)
        if (path.sumOf { puzzle.cells[it].value } != puzzle.targetSum) {
            return PathValidation(false, PathError.WRONG_SUM)
        }
        val visited = path.toSet()
        if (puzzle.cells.indices.any { puzzle.cells[it].key && it !in visited }) {
            return PathValidation(false, PathError.MISSING_KEYS)
        }
        return PathValidation(true)
    }
}

enum class SessionStatus { IN_PROGRESS, WON, INCORRECT_FINISH }

enum class MoveResult {
    ADDED, WON, FINISHED_INCORRECT,
    OUT_OF_BOUNDS, NOT_ADJACENT, ALREADY_VISITED, ALREADY_FINISHED
}

class GameSession(val puzzle: Puzzle, restoredPath: List<Int> = listOf(puzzle.start)) {
    private val steps = restoredPath.toMutableList()

    init { require(PuzzleRules.validatePrefix(puzzle, steps).valid) { "Invalid saved path" } }

    val path: List<Int> get() = steps.toList()
    val currentSum: Int get() = steps.sumOf { puzzle.cells[it].value }
    val collectedKeys: Int get() = steps.count { puzzle.cells[it].key }
    val lastCell: Int get() = steps.last()
    val canUndo: Boolean get() = steps.size > 1
    val validation: PathValidation get() = PuzzleRules.validatePath(puzzle, steps)
    val status: SessionStatus get() = when {
        lastCell != puzzle.finish -> SessionStatus.IN_PROGRESS
        validation.valid -> SessionStatus.WON
        else -> SessionStatus.INCORRECT_FINISH
    }

    fun attemptMove(index: Int): MoveResult {
        if (index !in puzzle.cells.indices) return MoveResult.OUT_OF_BOUNDS
        if (lastCell == puzzle.finish) return MoveResult.ALREADY_FINISHED
        if (index in steps) return MoveResult.ALREADY_VISITED
        if (!puzzle.adjacent(lastCell, index)) return MoveResult.NOT_ADJACENT
        steps.add(index)
        return when (status) {
            SessionStatus.IN_PROGRESS -> MoveResult.ADDED
            SessionStatus.WON -> MoveResult.WON
            SessionStatus.INCORRECT_FINISH -> MoveResult.FINISHED_INCORRECT
        }
    }

    fun undo(): Boolean = if (canUndo) { steps.removeAt(steps.lastIndex); true } else false

    fun reset() { steps.clear(); steps.add(puzzle.start) }

    fun snapshot(): SessionSnapshot = SessionSnapshot(puzzle, path)
}
