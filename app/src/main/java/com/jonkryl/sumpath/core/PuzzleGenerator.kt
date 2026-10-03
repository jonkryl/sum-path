package com.jonkryl.sumpath.core

/** SplitMix64 is specified here instead of relying on platform Random implementations. */
internal class SeededRandom(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -7046029254386353131L
        var value = state
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound).toInt()
    }

    fun <T> shuffle(items: MutableList<T>) {
        for (index in items.lastIndex downTo 1) {
            val other = nextInt(index + 1)
            val old = items[index]
            items[index] = items[other]
            items[other] = old
        }
    }
}

data class GenerationOptions(
    val searchBudget: SearchBudget = SearchBudget(),
    val maxPathNodes: Int = 4_000,
    val maxPathAttempts: Int = 8
) {
    init { require(maxPathNodes >= 0); require(maxPathAttempts in 0..100) }
}

enum class FallbackReason { NONE, PATH_LIMIT, SOLVER_LIMIT, VERIFICATION_FAILED }

data class GeneratedPuzzle(
    val puzzle: Puzzle,
    val solution: List<Int>,
    val usedFallback: Boolean,
    val searchNodes: Int,
    val fallbackReason: FallbackReason = FallbackReason.NONE
)

object PuzzleGenerator {
    /** Call from a worker thread. Candidate construction and actual solver verification are both bounded. */
    fun generate(
        seed: Long,
        difficulty: Difficulty,
        options: GenerationOptions = GenerationOptions(),
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): GeneratedPuzzle {
        val random = SeededRandom(seed xor (difficulty.ordinal.toLong() * -7046029254386353131L))
        val size = difficulty.size
        val length = difficulty.minimumLength +
            random.nextInt(difficulty.maximumLength - difficulty.minimumLength + 1)
        val path = createPath(size, length, random, options, cancelled)
            ?: return fallback(seed, difficulty, FallbackReason.PATH_LIMIT, 0, cancelled)
        val maximumValue = when (difficulty) {
            Difficulty.EASY -> 4
            Difficulty.MEDIUM -> 6
            Difficulty.HARD -> 9
        }
        val values = IntArray(size * size) { 1 + random.nextInt(maximumValue) }
        val candidates = path.drop(1).dropLast(1).toMutableList()
        random.shuffle(candidates)
        val keys = candidates.take(difficulty.keyCount).toSet()
        val puzzle = Puzzle(
            size, seed, difficulty,
            values.mapIndexed { index, value -> Cell(value, index in keys) },
            path.first(), path.last(), path.sumOf { values[it] }
        )
        // This witness check guards construction bugs. A genuine solver still proves the release candidate.
        if (!PuzzleRules.validatePath(puzzle, path).valid) {
            return fallback(seed, difficulty, FallbackReason.VERIFICATION_FAILED, 0, cancelled)
        }
        val proof = PuzzleSolver.solve(puzzle, options.searchBudget, cancelled)
        PuzzleSolver.throwIfCancelled(proof.status)
        if (proof.status == SearchStatus.SOLVED && proof.solution != null &&
            PuzzleRules.validatePath(puzzle, proof.solution).valid
        ) {
            return GeneratedPuzzle(puzzle, proof.solution, false, proof.nodesVisited)
        }
        val reason = if (proof.status == SearchStatus.LIMIT_REACHED) FallbackReason.SOLVER_LIMIT
        else FallbackReason.VERIFICATION_FAILED
        return fallback(seed, difficulty, reason, proof.nodesVisited, cancelled)
    }

    private fun createPath(
        size: Int,
        length: Int,
        random: SeededRandom,
        options: GenerationOptions,
        cancelled: () -> Boolean
    ): List<Int>? {
        var totalNodes = 0
        val route = mutableListOf<Int>()
        fun walk(index: Int, visited: Long): Boolean {
            if (cancelled()) throw java.util.concurrent.CancellationException("Puzzle generation cancelled")
            if (totalNodes >= options.maxPathNodes) return false
            totalNodes++
            route.add(index)
            if (route.size == length) return true
            val row = index / size
            val column = index % size
            val next = buildList {
                if (row > 0) add(index - size)
                if (column > 0) add(index - 1)
                if (column + 1 < size) add(index + 1)
                if (row + 1 < size) add(index + size)
            }.filter { visited and (1L shl it) == 0L }.toMutableList()
            random.shuffle(next)
            for (neighbor in next) {
                if (walk(neighbor, visited or (1L shl neighbor))) return true
                if (totalNodes >= options.maxPathNodes) break
            }
            route.removeAt(route.lastIndex)
            return false
        }
        repeat(options.maxPathAttempts) {
            route.clear()
            val start = random.nextInt(size * size)
            if (walk(start, 1L shl start)) return route.toList()
            if (totalNodes >= options.maxPathNodes) return null
        }
        return null
    }

    /** Small, authored, previously checked fields remain playable even when the candidate budget expires. */
    private fun fallback(
        seed: Long,
        difficulty: Difficulty,
        reason: FallbackReason,
        previousNodes: Int,
        cancelled: () -> Boolean
    ): GeneratedPuzzle {
        val size = difficulty.size
        val template = if (size == 4) listOf(0, 1, 2, 3, 7, 6, 10, 11, 15)
        else listOf(0, 1, 2, 3, 4, 9, 8, 13, 14, 19, 24)
        val symmetry = ((seed xor (seed ushr 32)) and 7L).toInt()
        fun transform(index: Int): Int {
            var row = index / size
            var column = index % size
            if (symmetry and 4 != 0) column = size - 1 - column
            repeat(symmetry and 3) {
                val oldRow = row
                row = column
                column = size - 1 - oldRow
            }
            return row * size + column
        }
        val route = template.map(::transform)
        val routeSet = route.toSet()
        val keyCells = (1..difficulty.keyCount).map { route[it * (route.size - 1) / (difficulty.keyCount + 1)] }.toSet()
        val puzzle = Puzzle(
            size, seed, difficulty,
            List(size * size) { Cell(if (it in routeSet) 1 else 9, it in keyCells) },
            route.first(), route.last(), route.size
        )
        check(PuzzleRules.validatePath(puzzle, route).valid)
        val proof = PuzzleSolver.solve(puzzle, SearchBudget(maxNodes = 10_000), cancelled)
        PuzzleSolver.throwIfCancelled(proof.status)
        check(proof.status == SearchStatus.SOLVED && proof.solution != null) { "Invalid fallback fixture" }
        check(PuzzleRules.validatePath(puzzle, proof.solution).valid)
        return GeneratedPuzzle(puzzle, proof.solution, true, previousNodes + proof.nodesVisited, reason)
    }
}

object Tutorial {
    /** An original square tutorial: first learn adjacency, then a key, then the exact finish sum. */
    val solution: List<Int> = listOf(0, 1, 5, 6, 10, 11, 15)
    val puzzle: Puzzle = Puzzle(
        size = 4,
        seed = Long.MIN_VALUE,
        difficulty = Difficulty.EASY,
        cells = List(16) { index ->
            val values = mapOf(0 to 1, 1 to 2, 5 to 3, 6 to 2, 10 to 1, 11 to 2, 15 to 3)
            Cell(values[index] ?: 7, key = index == 5)
        },
        start = 0,
        finish = 15,
        targetSum = 14
    )
}

object PuzzleSeeds {
    private fun hash(text: String): Long {
        var hash = -3750763034362895579L
        for (character in text) { hash = (hash xor character.code.toLong()) * 1099511628211L }
        return SeededRandom(hash).nextLong()
    }

    fun dailySeed(isoDate: String): Long {
        require(isoDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        val year = isoDate.substring(0, 4).toInt()
        val month = isoDate.substring(5, 7).toInt()
        val day = isoDate.substring(8, 10).toInt()
        require(year in 1..9999 && month in 1..12)
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        require(day in 1..days[month - 1])
        return hash("sum-path-v1:daily:$isoDate")
    }

    fun dailyDifficulty(isoDate: String): Difficulty {
        val seed = dailySeed(isoDate)
        return Difficulty.entries[((seed ushr 1) % Difficulty.entries.size).toInt()]
    }

    fun levelSeed(difficulty: Difficulty, levelNumber: Int): Long {
        require(levelNumber >= 1)
        return hash("sum-path-v1:level:${difficulty.name}:$levelNumber")
    }
}
