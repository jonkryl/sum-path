package com.jonkryl.sumpath.core

import org.junit.Assert.*
import org.junit.Test

class PuzzleCoreTest {
    private fun twoRoutePuzzle(keyAtOne: Boolean = false): Puzzle = Puzzle(
        4, 42L, Difficulty.EASY,
        List(16) { index ->
            Cell(when (index) { 0 -> 2; 1, 4 -> 3; 5 -> 4; else -> 9 },
                key = if (keyAtOne) index == 1 else index == 5)
        },
        start = 0, finish = 5, targetSum = 9
    )

    /** Deliberately independent of production rules, adjacency helpers, solver and generator internals. */
    private fun assertIndependentSolution(puzzle: Puzzle, path: List<Int>) {
        assertTrue("Empty solution", path.isNotEmpty())
        assertEquals(puzzle.start, path.first())
        assertEquals(puzzle.finish, path.last())
        val seen = mutableSetOf<Int>()
        var total = 0
        path.forEachIndexed { position, index ->
            assertTrue("Out of bounds: $index", index >= 0 && index < puzzle.size * puzzle.size)
            assertTrue("Repeated: $index", seen.add(index))
            total += puzzle.cells[index].value
            if (position > 0) {
                val previous = path[position - 1]
                val rowChange = kotlin.math.abs(previous / puzzle.size - index / puzzle.size)
                val columnChange = kotlin.math.abs(previous % puzzle.size - index % puzzle.size)
                assertEquals("Nonorthogonal step $previous -> $index", 1, rowChange + columnChange)
            }
            if (position < path.lastIndex) assertNotEquals("Finish used too soon", puzzle.finish, index)
        }
        assertEquals("Incorrect exact sum", puzzle.targetSum, total)
        puzzle.cells.forEachIndexed { index, cell ->
            if (cell.key) assertTrue("Missing mandatory key $index", index in seen)
        }
    }

    @Test fun neighborListsMatchIndependentSquareGeometryForBothSizes() {
        for (size in listOf(4, 5)) {
            val puzzle = Puzzle(size, 0L, if (size == 4) Difficulty.EASY else Difficulty.HARD,
                List(size * size) { Cell(1) }, 0, size * size - 1, size * 2 - 1)
            for (index in 0 until size * size) {
                val expected = (0 until size * size).filter { other ->
                    kotlin.math.abs(index / size - other / size) +
                        kotlin.math.abs(index % size - other % size) == 1
                }.toSet()
                assertEquals("Wrong neighbors on ${size}x$size at $index", expected, puzzle.neighbors(index).toSet())
                assertEquals(expected.size, puzzle.neighbors(index).size)
            }
        }
    }

    @Test fun acceptsBothIndependentSolutionsOfHandwrittenBoard() {
        val puzzle = twoRoutePuzzle()
        val first = listOf(0, 1, 5)
        val second = listOf(0, 4, 5)
        assertIndependentSolution(puzzle, first)
        assertIndependentSolution(puzzle, second)
        assertTrue(PuzzleRules.validatePath(puzzle, first).valid)
        assertTrue(PuzzleRules.validatePath(puzzle, second).valid)
        val firstSession = GameSession(puzzle)
        first.drop(1).forEach(firstSession::attemptMove)
        val secondSession = GameSession(puzzle)
        second.drop(1).forEach(secondSession::attemptMove)
        assertEquals(SessionStatus.WON, firstSession.status)
        assertEquals(SessionStatus.WON, secondSession.status)
    }

    @Test fun rejectsWrongSumMissingKeyBadTransitionRepeatAndPrematureFinish() {
        val puzzle = twoRoutePuzzle()
        assertEquals(PathError.WRONG_SUM, PuzzleRules.validatePath(puzzle, listOf(0, 1, 2, 6, 5)).error)
        assertEquals(PathError.MISSING_KEYS, PuzzleRules.validatePath(twoRoutePuzzle(true), listOf(0, 4, 5)).error)
        assertEquals(PathError.NOT_ORTHOGONAL, PuzzleRules.validatePath(puzzle, listOf(0, 5)).error)
        assertEquals(PathError.REPEATED_CELL, PuzzleRules.validatePath(puzzle, listOf(0, 1, 0, 4, 5)).error)
        assertEquals(PathError.FINISH_BEFORE_END, PuzzleRules.validatePath(puzzle, listOf(0, 1, 5, 6)).error)
        assertEquals(PathError.OUT_OF_BOUNDS, PuzzleRules.validatePath(puzzle, listOf(0, 16)).error)
        assertEquals(PathError.WRONG_START, PuzzleRules.validatePath(puzzle, listOf(4, 5)).error)
        assertEquals(PathError.EMPTY, PuzzleRules.validatePath(puzzle, emptyList()).error)
        assertEquals(PathError.NOT_FINISHED, PuzzleRules.validatePath(puzzle, listOf(0, 1)).error)
    }

    @Test fun realSolverFindsValidRouteAndProvesAnImpossibleBoard() {
        val result = PuzzleSolver.solve(twoRoutePuzzle())
        assertEquals(SearchStatus.SOLVED, result.status)
        assertIndependentSolution(twoRoutePuzzle(), requireNotNull(result.solution))
        // Same-color start/finish need an odd number of cells; a sum of four ones cannot work.
        val impossible = Puzzle(4, 0L, Difficulty.EASY, List(16) { Cell(1) }, 0, 5, 4)
        val absent = PuzzleSolver.solve(impossible)
        assertEquals(SearchStatus.UNSOLVABLE, absent.status)
        assertNull(absent.solution)
    }

    @Test fun solverLimitAndCancellationNeverReportAnInvalidSuccess() {
        val puzzle = twoRoutePuzzle()
        for (limit in 0..2) {
            val result = PuzzleSolver.solve(puzzle, SearchBudget(limit))
            assertEquals(SearchStatus.LIMIT_REACHED, result.status)
            assertNull(result.solution)
            assertTrue(result.nodesVisited <= limit)
        }
        val cancelled = PuzzleSolver.solve(puzzle, cancelled = { true })
        assertEquals(SearchStatus.CANCELLED, cancelled.status)
        assertEquals(0, cancelled.nodesVisited)
        assertNull(cancelled.solution)
    }

    @Test fun solverMatchesIndependentExhaustiveOracleForSmallTargets() {
        val random = java.util.Random(20261003L)
        repeat(120) { case ->
            val start = random.nextInt(16)
            var finish = random.nextInt(16)
            while (finish == start) finish = random.nextInt(16)
            val key = random.nextInt(16)
            val cells = List(16) { Cell(1 + random.nextInt(3), it == key) }
            val puzzle = Puzzle(4, case.toLong(), Difficulty.EASY, cells, start, finish, 3 + random.nextInt(10))
            val route = mutableListOf(start)
            val visited = BooleanArray(16).also { it[start] = true }
            fun exhaustive(current: Int, sum: Int): Boolean {
                if (sum > puzzle.targetSum) return false
                if (current == finish) return sum == puzzle.targetSum && visited[key]
                for (next in 0 until 16) {
                    val rowDifference = kotlin.math.abs(current / 4 - next / 4)
                    val columnDifference = kotlin.math.abs(current % 4 - next % 4)
                    if (!visited[next] && rowDifference + columnDifference == 1) {
                        visited[next] = true
                        route.add(next)
                        if (exhaustive(next, sum + cells[next].value)) return true
                        route.removeAt(route.lastIndex)
                        visited[next] = false
                    }
                }
                return false
            }
            val possible = exhaustive(start, cells[start].value)
            val actual = PuzzleSolver.solve(puzzle, SearchBudget(100_000))
            assertEquals("Oracle mismatch in case $case", possible, actual.status == SearchStatus.SOLVED)
            assertNotEquals("Small board should not exhaust budget", SearchStatus.LIMIT_REACHED, actual.status)
            if (possible) assertIndependentSolution(puzzle, requireNotNull(actual.solution))
            else assertNull(actual.solution)
        }
    }

    @Test fun independentlyValidatesHundredsOfSeedsAtBothBoardSizes() {
        for (difficulty in Difficulty.entries) {
            val signatures = mutableSetOf<String>()
            var directlyGenerated = 0
            for (seed in 0L until 250L) {
                val generated = PuzzleGenerator.generate(seed, difficulty)
                val puzzle = generated.puzzle
                assertEquals(difficulty.size, puzzle.size)
                assertEquals(difficulty.keyCount, puzzle.keyCount)
                assertEquals(seed, puzzle.seed)
                assertIndependentSolution(puzzle, generated.solution)
                assertTrue(generated.searchNodes in 1..110_000)
                if (!generated.usedFallback) directlyGenerated++
                signatures.add("${puzzle.cells}|${puzzle.start}|${puzzle.finish}|${puzzle.targetSum}")
            }
            assertTrue("Too many fallback fields for $difficulty: $directlyGenerated", directlyGenerated >= 230)
            assertTrue("Seeds must create real new boards for $difficulty", signatures.size >= 240)
        }
    }

    @Test fun stableSeedsReproduceBoardAndSolverResultIncludingExtremeLongs() {
        val seeds = listOf(0L, 1L, -1L, 20261003L, Long.MIN_VALUE, Long.MAX_VALUE)
        for (difficulty in Difficulty.entries) for (seed in seeds) {
            val first = PuzzleGenerator.generate(seed, difficulty)
            val second = PuzzleGenerator.generate(seed, difficulty)
            assertEquals(first, second)
            assertIndependentSolution(first.puzzle, first.solution)
        }
    }

    @Test fun exhaustedSolverFallsBackToGenuinelyVerifiedPlayableFields() {
        for (difficulty in Difficulty.entries) for (seed in 0L..7L) {
            val generated = PuzzleGenerator.generate(seed, difficulty,
                GenerationOptions(searchBudget = SearchBudget(maxNodes = 0)))
            assertTrue(generated.usedFallback)
            assertEquals(FallbackReason.SOLVER_LIMIT, generated.fallbackReason)
            assertIndependentSolution(generated.puzzle, generated.solution)
            val proof = PuzzleSolver.solve(generated.puzzle, SearchBudget(10_000))
            assertEquals(SearchStatus.SOLVED, proof.status)
            assertIndependentSolution(generated.puzzle, requireNotNull(proof.solution))
        }
    }

    @Test fun exhaustedPathGenerationFallsBackInsteadOfReturningEmptyBoard() {
        for (difficulty in Difficulty.entries) {
            val result = PuzzleGenerator.generate(1234, difficulty,
                GenerationOptions(maxPathNodes = 0, maxPathAttempts = 0))
            assertTrue(result.usedFallback)
            assertEquals(FallbackReason.PATH_LIMIT, result.fallbackReason)
            assertIndependentSolution(result.puzzle, result.solution)
        }
    }

    @Test fun undoResetAndRejectedMovesPreserveState() {
        val game = GameSession(twoRoutePuzzle())
        assertEquals(2, game.currentSum)
        assertFalse(game.canUndo)
        assertFalse(game.undo())
        assertEquals(MoveResult.NOT_ADJACENT, game.attemptMove(15))
        assertEquals(MoveResult.OUT_OF_BOUNDS, game.attemptMove(-1))
        assertEquals(listOf(0), game.path)
        assertEquals(MoveResult.ADDED, game.attemptMove(1))
        assertEquals(MoveResult.ALREADY_VISITED, game.attemptMove(0))
        assertEquals(5, game.currentSum)
        assertEquals(MoveResult.WON, game.attemptMove(5))
        assertEquals(1, game.collectedKeys)
        assertEquals(MoveResult.ALREADY_FINISHED, game.attemptMove(6))
        assertTrue(game.undo())
        assertEquals(listOf(0, 1), game.path)
        assertEquals(SessionStatus.IN_PROGRESS, game.status)
        game.reset()
        assertEquals(listOf(0), game.path)
        assertEquals(2, game.currentSum)
        assertEquals(0, game.collectedKeys)
    }

    @Test fun wrongFinishCanBeUndoneAndRecoveredWithoutReset() {
        val game = GameSession(twoRoutePuzzle(true))
        assertEquals(MoveResult.ADDED, game.attemptMove(4))
        assertEquals(MoveResult.FINISHED_INCORRECT, game.attemptMove(5))
        assertEquals(SessionStatus.INCORRECT_FINISH, game.status)
        assertEquals(PathError.MISSING_KEYS, game.validation.error)
        assertTrue(game.undo())
        assertTrue(game.undo())
        assertEquals(MoveResult.ADDED, game.attemptMove(1))
        assertEquals(MoveResult.WON, game.attemptMove(5))
        assertEquals(SessionStatus.WON, game.status)
    }

    @Test fun snapshotRoundTripsFullBoardPartialPathWinAndWrongFinish() {
        for (difficulty in Difficulty.entries) {
            val generated = PuzzleGenerator.generate(Long.MIN_VALUE + difficulty.ordinal, difficulty)
            val partial = generated.solution.take(3)
            val saved = GameSession(generated.puzzle, partial)
            val decoded = requireNotNull(SnapshotCodec.decode(SnapshotCodec.encode(saved.snapshot())))
            assertEquals(saved.snapshot(), decoded)
            assertEquals(saved.currentSum, decoded.restore().currentSum)
            val completed = GameSession(generated.puzzle, generated.solution)
            val restored = requireNotNull(SnapshotCodec.decode(SnapshotCodec.encode(completed.snapshot()))).restore()
            assertEquals(SessionStatus.WON, restored.status)
            assertIndependentSolution(restored.puzzle, restored.path)
        }
        val failed = GameSession(twoRoutePuzzle(true), listOf(0, 4, 5))
        val restoredFailure = requireNotNull(SnapshotCodec.decode(SnapshotCodec.encode(failed.snapshot()))).restore()
        assertEquals(SessionStatus.INCORRECT_FINISH, restoredFailure.status)
        assertEquals(PathError.MISSING_KEYS, restoredFailure.validation.error)
        assertTrue(restoredFailure.undo())
    }

    @Test fun persistenceRejectsCorruptionVersionMismatchAndStructurallyInvalidPaths() {
        val original = SnapshotCodec.encode(GameSession(twoRoutePuzzle()).snapshot())
        assertNull(SnapshotCodec.decode(""))
        assertNull(SnapshotCodec.decode("{}"))
        assertNull(SnapshotCodec.decode(original.dropLast(1)))
        assertNull(SnapshotCodec.decode(original + " trailing"))
        assertNull(SnapshotCodec.decode(original.replace("\"version\":1", "\"version\":99")))
        assertNull(SnapshotCodec.decode(original.replace("\"path\":[0]", "\"path\":[0,5]")))
        assertNull(SnapshotCodec.decode(original.replace("\"path\":[0]", "\"path\":[0,1,0]")))
        assertNull(SnapshotCodec.decode(original.replace("\"path\":[0]", "\"path\":[16]")))
        assertNull(SnapshotCodec.decode(original.replace("\"seed\":42", "\"seed\":999999999999999999999999")))
        assertNull(SnapshotCodec.decode(original.replace("\"version\":1", "\"version\":1,\"version\":1")))
        assertNull(SnapshotCodec.decode("[".repeat(9_000)))
    }

    @Test fun dailyAndLevelSeedsAreStableDistinctAndCalendarValidated() {
        // Golden values keep daily identity compatible across releases and JVM/Android runtimes.
        assertEquals(1224014689945866521L, PuzzleSeeds.dailySeed("2026-10-03"))
        assertEquals(4764146303636781574L, PuzzleSeeds.dailySeed("2026-10-04"))
        assertEquals(-691549385573253524L, PuzzleSeeds.levelSeed(Difficulty.EASY, 1))
        assertEquals(-1523520455596747895L, PuzzleSeeds.levelSeed(Difficulty.HARD, 1))
        assertEquals(PuzzleSeeds.dailySeed("2026-10-03"), PuzzleSeeds.dailySeed("2026-10-03"))
        assertNotEquals(PuzzleSeeds.dailySeed("2026-10-03"), PuzzleSeeds.dailySeed("2026-10-04"))
        assertEquals(PuzzleSeeds.dailyDifficulty("2026-10-03"), PuzzleSeeds.dailyDifficulty("2026-10-03"))
        assertEquals(PuzzleSeeds.levelSeed(Difficulty.EASY, 1), PuzzleSeeds.levelSeed(Difficulty.EASY, 1))
        assertNotEquals(PuzzleSeeds.levelSeed(Difficulty.EASY, 1), PuzzleSeeds.levelSeed(Difficulty.EASY, 2))
        assertNotEquals(PuzzleSeeds.levelSeed(Difficulty.EASY, 1), PuzzleSeeds.levelSeed(Difficulty.HARD, 1))
        assertNotEquals(PuzzleSeeds.dailySeed("2026-10-03"), PuzzleSeeds.levelSeed(Difficulty.EASY, 1))
        PuzzleSeeds.dailySeed("2024-02-29")
        for (date in listOf("2026-02-29", "2026-13-01", "2026-04-31", "2026-00-01", "2026-10-00", "26-10-03")) {
            try { PuzzleSeeds.dailySeed(date); fail("Invalid date accepted: $date") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
        val seed = PuzzleSeeds.dailySeed("2026-10-03")
        val difficulty = PuzzleSeeds.dailyDifficulty("2026-10-03")
        assertEquals(PuzzleGenerator.generate(seed, difficulty), PuzzleGenerator.generate(seed, difficulty))
    }

    @Test fun tutorialIsOriginalAndActuallySolvableWithExplicitKeyAndExactSum() {
        assertEquals(4, Tutorial.puzzle.size)
        assertEquals(14, Tutorial.puzzle.targetSum)
        assertEquals(1, Tutorial.puzzle.keyCount)
        assertIndependentSolution(Tutorial.puzzle, Tutorial.solution)
        val proof = PuzzleSolver.solve(Tutorial.puzzle)
        assertEquals(SearchStatus.SOLVED, proof.status)
        assertIndependentSolution(Tutorial.puzzle, requireNotNull(proof.solution))
    }
}
