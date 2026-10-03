package com.jonkryl.sumpath.core

import java.util.concurrent.CancellationException

data class SearchBudget(val maxNodes: Int = 100_000, val maxElapsedMillis: Long = 0) {
    init { require(maxNodes >= 0); require(maxElapsedMillis >= 0) }
}

enum class SearchStatus { SOLVED, UNSOLVABLE, LIMIT_REACHED, CANCELLED }

data class SearchResult(
    val status: SearchStatus,
    val solution: List<Int>?,
    val nodesVisited: Int
)

/** A genuine simple-path DFS. Its node bound is deterministic; the optional deadline is a backstop. */
object PuzzleSolver {
    fun solve(
        puzzle: Puzzle,
        budget: SearchBudget = SearchBudget(),
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): SearchResult {
        val count = puzzle.cells.size
        val neighbors = Array(count) { puzzle.neighbors(it).toIntArray() }
        val values = IntArray(count) { puzzle.cells[it].value }
        val minimumValue = values.minOrNull() ?: 1
        val keyMask = puzzle.cells.indices.fold(0L) { mask, index ->
            if (puzzle.cells[index].key) mask or (1L shl index) else mask
        }
        val route = IntArray(count)
        val queue = IntArray(count)
        var nodes = 0
        var stopped: SearchStatus? = null
        var solution: List<Int>? = null
        val startedAt = System.nanoTime()

        fun distance(first: Int, second: Int): Int =
            kotlin.math.abs(first / puzzle.size - second / puzzle.size) +
                kotlin.math.abs(first % puzzle.size - second % puzzle.size)

        // Reachability is computed without passing through the finish: touching it ends a path.
        fun canStillFinish(current: Int, visited: Long, sum: Int, keys: Long): Boolean {
            var seen = 1L shl current
            var head = 0
            var tail = 1
            queue[0] = current
            var availableSum = 0
            while (head < tail) {
                val cell = queue[head++]
                if (cell != current) availableSum += values[cell]
                if (cell == puzzle.finish) continue
                for (next in neighbors[cell]) {
                    val bit = 1L shl next
                    if (seen and bit == 0L && visited and bit == 0L) {
                        seen = seen or bit
                        queue[tail++] = next
                    }
                }
            }
            if (seen and (1L shl puzzle.finish) == 0L) return false
            if (keyMask and keys.inv() and seen.inv() != 0L) return false
            if (sum + availableSum < puzzle.targetSum) return false
            var minimumSteps = distance(current, puzzle.finish)
            for (index in 0 until count) {
                val bit = 1L shl index
                if (keyMask and bit != 0L && keys and bit == 0L) {
                    minimumSteps = maxOf(minimumSteps, distance(current, index) + distance(index, puzzle.finish))
                }
            }
            return sum.toLong() + minimumSteps.toLong() * minimumValue <= puzzle.targetSum
        }

        fun score(index: Int, collected: Long): Int {
            val bit = 1L shl index
            if (keyMask and bit != 0L && collected and bit == 0L) return -100
            var nearestKey = count * 2
            for (key in 0 until count) {
                val keyBit = 1L shl key
                if (keyMask and keyBit != 0L && collected and keyBit == 0L) {
                    nearestKey = minOf(nearestKey, distance(index, key))
                }
            }
            return if (nearestKey != count * 2) nearestKey * 4 + values[index]
            else distance(index, puzzle.finish) * 4 + values[index]
        }

        fun search(current: Int, visited: Long, sum: Int, keys: Long, length: Int): Boolean {
            if (stopped != null) return false
            if (cancelled()) { stopped = SearchStatus.CANCELLED; return false }
            if (nodes >= budget.maxNodes) { stopped = SearchStatus.LIMIT_REACHED; return false }
            if (budget.maxElapsedMillis > 0 && nodes and 255 == 0 &&
                (System.nanoTime() - startedAt) / 1_000_000 >= budget.maxElapsedMillis
            ) { stopped = SearchStatus.LIMIT_REACHED; return false }
            nodes++
            route[length - 1] = current
            if (sum > puzzle.targetSum) return false
            if (current == puzzle.finish) {
                if (sum == puzzle.targetSum && keys and keyMask == keyMask) {
                    solution = route.take(length)
                    return true
                }
                return false
            }
            if (sum == puzzle.targetSum || !canStillFinish(current, visited, sum, keys)) return false
            // Stable ordering improves time to the first solution without changing the legal search space.
            val candidates = neighbors[current].filter { visited and (1L shl it) == 0L }
                .sortedWith(compareBy<Int> { score(it, keys) }.thenBy { it })
            for (next in candidates) {
                val bit = 1L shl next
                if (search(next, visited or bit, sum + values[next], keys or (keyMask and bit), length + 1)) {
                    return true
                }
                if (stopped != null) return false
            }
            return false
        }

        val startBit = 1L shl puzzle.start
        search(puzzle.start, startBit, values[puzzle.start], keyMask and startBit, 1)
        return SearchResult(
            if (solution != null) SearchStatus.SOLVED else stopped ?: SearchStatus.UNSOLVABLE,
            solution,
            nodes
        )
    }

    internal fun throwIfCancelled(status: SearchStatus) {
        if (status == SearchStatus.CANCELLED) throw CancellationException("Puzzle generation cancelled")
    }
}
