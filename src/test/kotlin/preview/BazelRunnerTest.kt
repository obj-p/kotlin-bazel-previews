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

    // --- parseTargets tests ---

    @Test
    fun parseTargets_singleTarget_returnsIt() {
        val output = "//src/main/kotlin:my-lib\n"
        assertEquals("//src/main/kotlin:my-lib", BazelRunner.parseTargets(output, "Foo.kt"))
    }

    @Test
    fun parseTargets_noTargets_throws() {
        val ex = assertFailsWith<RuntimeException> {
            BazelRunner.parseTargets("", "Foo.kt")
        }
        assertTrue(ex.message!!.contains("Expected exactly 1 target"))
    }

    @Test
    fun parseTargets_multipleTargets_throws() {
        val output = "//a:lib\n//b:lib\n"
        val ex = assertFailsWith<RuntimeException> {
            BazelRunner.parseTargets(output, "Foo.kt")
        }
        assertTrue(ex.message!!.contains("found 2"))
    }

    @Test
    fun parseTargets_ignoresNonTargetLines() {
        val output = "Loading: 0 packages loaded\n//src:target\nINFO: done\n"
        assertEquals("//src:target", BazelRunner.parseTargets(output, "Foo.kt"))
    }

    // --- parseClasspath tests ---

    @Test
    fun parseClasspath_extractsJarPaths() {
        val cquery = "bazel-out/k8-fastbuild/bin/lib.jar\n"
        val result = BazelRunner.parseClasspath("/exec/root", cquery)
        assertEquals(listOf("/exec/root/bazel-out/k8-fastbuild/bin/lib.jar"), result)
    }

    @Test
    fun parseClasspath_ignoresNonJarLines() {
        val cquery = "Loading: analyzing target\nbazel-out/bin/lib.jar\nINFO: done\n"
        val result = BazelRunner.parseClasspath("/root", cquery)
        assertEquals(listOf("/root/bazel-out/bin/lib.jar"), result)
    }

    @Test
    fun parseClasspath_prependsExecRoot() {
        val cquery = "a.jar\nb.jar\n"
        val result = BazelRunner.parseClasspath("/ws", cquery)
        assertEquals(listOf("/ws/a.jar", "/ws/b.jar"), result)
    }

    @Test
    fun parseClasspath_handlesEmptyOutput() {
        val result = BazelRunner.parseClasspath("/ws", "")
        assertTrue(result.isEmpty())
    }
}
