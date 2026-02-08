package preview

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun parseArgs_validArgsWithoutWatch() {
        val result = parseArgs(arrayOf("/workspace", "src/Main.kt"))
        assertEquals("/workspace", result.workspaceRoot)
        assertEquals("src/Main.kt", result.filePath)
        assertFalse(result.watch)
    }

    @Test
    fun parseArgs_validArgsWithWatch() {
        val result = parseArgs(arrayOf("--watch", "/workspace", "src/Main.kt"))
        assertEquals("/workspace", result.workspaceRoot)
        assertEquals("src/Main.kt", result.filePath)
        assertTrue(result.watch)
    }

    @Test
    fun parseArgs_watchFlagPositionIndependent() {
        val result = parseArgs(arrayOf("/workspace", "--watch", "src/Main.kt"))
        assertEquals("/workspace", result.workspaceRoot)
        assertEquals("src/Main.kt", result.filePath)
        assertTrue(result.watch)
    }

    @Test
    fun parseArgs_tooFewArgs_throws() {
        assertFailsWith<IllegalArgumentException> {
            parseArgs(arrayOf("/workspace"))
        }
    }

    @Test
    fun parseArgs_tooManyArgs_throws() {
        assertFailsWith<IllegalArgumentException> {
            parseArgs(arrayOf("/workspace", "src/Main.kt", "extra"))
        }
    }

    @Test
    fun parseArgs_noArgs_throws() {
        assertFailsWith<IllegalArgumentException> {
            parseArgs(emptyArray())
        }
    }
}
