package com.jonkryl.sumpath.core

data class SessionSnapshot(val puzzle: Puzzle, val path: List<Int>) {
    fun restore(): GameSession = GameSession(puzzle, path)
}

/** Versioned JSON, independent of Android, locale, Gson and platform serialization defaults. */
object SnapshotCodec {
    fun encode(snapshot: SessionSnapshot): String {
        require(PuzzleRules.validatePrefix(snapshot.puzzle, snapshot.path).valid)
        val puzzle = snapshot.puzzle
        return buildString {
            append("{\"version\":1,\"size\":").append(puzzle.size)
            append(",\"seed\":").append(puzzle.seed)
            append(",\"difficulty\":\"").append(puzzle.difficulty.name).append('"')
            append(",\"start\":").append(puzzle.start)
            append(",\"finish\":").append(puzzle.finish)
            append(",\"target\":").append(puzzle.targetSum)
            append(",\"cells\":[")
            puzzle.cells.forEachIndexed { index, cell ->
                if (index > 0) append(',')
                append('[').append(cell.value).append(',').append(cell.key).append(']')
            }
            append("],\"path\":[").append(snapshot.path.joinToString(",")).append("]}")
        }
    }

    /** Corrupt or incompatible data is rejected; the caller can start a fresh, verified board. */
    fun decode(json: String): SessionSnapshot? = runCatching {
        require(json.length <= 8_192)
        val root = JsonReader(json).read() as? Map<*, *> ?: error("Object expected")
        fun number(key: String): Long = root[key] as? Long ?: error("Missing number: $key")
        fun int(key: String): Int {
            val value = number(key)
            require(value in Int.MIN_VALUE..Int.MAX_VALUE)
            return value.toInt()
        }
        require(number("version") == 1L)
        val difficulty = Difficulty.valueOf(root["difficulty"] as? String ?: error("Missing difficulty"))
        val size = int("size")
        require(size == difficulty.size)
        val cellData = root["cells"] as? List<*> ?: error("Missing cells")
        require(cellData.size == size * size)
        val cells = cellData.map { raw ->
            val pair = raw as? List<*> ?: error("Bad cell")
            require(pair.size == 2)
            val value = pair[0] as? Long ?: error("Bad value")
            require(value in 1..99)
            Cell(value.toInt(), pair[1] as? Boolean ?: error("Bad key"))
        }
        val puzzle = Puzzle(size, number("seed"), difficulty, cells, int("start"), int("finish"), int("target"))
        val pathData = root["path"] as? List<*> ?: error("Missing path")
        require(pathData.size in 1..cells.size)
        val path = pathData.map {
            val index = it as? Long ?: error("Bad path index")
            require(index in cells.indices.first.toLong()..cells.indices.last.toLong())
            index.toInt()
        }
        require(PuzzleRules.validatePrefix(puzzle, path).valid)
        SessionSnapshot(puzzle, path)
    }.getOrNull()

    private class JsonReader(private val text: String) {
        private var position = 0
        private var elements = 0

        fun read(): Any {
            val value = value(0)
            whitespace()
            require(position == text.length)
            return value
        }

        private fun whitespace() { while (position < text.length && text[position] in " \n\r\t") position++ }

        private fun take(character: Char): Boolean {
            whitespace()
            if (position < text.length && text[position] == character) { position++; return true }
            return false
        }

        private fun value(depth: Int): Any {
            require(depth <= 8 && ++elements <= 512)
            whitespace()
            require(position < text.length)
            return when (text[position]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                '-', in '0'..'9' -> numberValue()
                else -> error("Unsupported JSON value")
            }
        }

        private fun objectValue(depth: Int): Map<String, Any> {
            require(take('{'))
            val result = linkedMapOf<String, Any>()
            if (take('}')) return result
            do {
                whitespace()
                val key = stringValue()
                require(take(':') && key !in result)
                result[key] = value(depth)
            } while (take(','))
            require(take('}'))
            return result
        }

        private fun arrayValue(depth: Int): List<Any> {
            require(take('['))
            val result = mutableListOf<Any>()
            if (take(']')) return result
            do { result.add(value(depth)) } while (take(','))
            require(take(']'))
            return result
        }

        private fun stringValue(): String {
            require(position < text.length && text[position++] == '"')
            val result = StringBuilder()
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') return result.toString()
                require(character >= ' ')
                if (character != '\\') result.append(character)
                else {
                    require(position < text.length)
                    result.append(when (val escape = text[position++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(position + 4 <= text.length)
                            val code = text.substring(position, position + 4).toInt(16)
                            position += 4
                            code.toChar()
                        }
                        else -> error("Bad escape")
                    })
                }
                require(result.length <= 256)
            }
            error("Unterminated string")
        }

        private fun literal(expected: String) {
            require(text.startsWith(expected, position))
            position += expected.length
        }

        private fun numberValue(): Long {
            val start = position
            if (text[position] == '-') position++
            require(position < text.length && text[position] in '0'..'9')
            if (text[position] == '0') position++
            else while (position < text.length && text[position] in '0'..'9') position++
            require(position - start <= 20)
            return text.substring(start, position).toLong()
        }
    }
}
