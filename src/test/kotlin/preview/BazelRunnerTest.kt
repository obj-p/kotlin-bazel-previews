package preview

import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BazelRunnerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun runCommandCapturesStdout() {
        val dir = tmpDir.root
        val output = BazelRunner.runCommand(dir.absolutePath, "echo", "hello world")
        assertEquals("hello world\n", output)
    }

    @Test
    fun runCommandThrowsOnNonZeroExitWithStderr() {
        val dir = tmpDir.root
        val ex = assertFailsWith<RuntimeException> {
            BazelRunner.runCommand(dir.absolutePath, "sh", "-c", "echo 'oops' >&2; exit 1")
        }
        assertTrue(ex.message!!.contains("exit 1"))
        assertTrue(ex.message!!.contains("oops"))
    }

    @Test
    fun findTargetRejectsInvalidFilename() {
        val dir = tmpDir.root
        assertFailsWith<IllegalArgumentException> {
            BazelRunner.findTarget(dir.absolutePath, "path/to/bad file(name).kt")
        }
    }
}
