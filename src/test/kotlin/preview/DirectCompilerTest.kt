package preview

import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun findKotlinStdlib(): String? {
    val cp = System.getProperty("java.class.path") ?: return null
    return cp.split(File.pathSeparator).firstOrNull { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
}

class DirectCompilerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun compilesValidKotlinFile() {
        val srcFile = tmpDir.newFile("Hello.kt")
        srcFile.writeText("""
            fun hello(): String = "Hello"
        """.trimIndent())

        val outDir = File(tmpDir.root, "out")
        val result = DirectCompiler.compile(listOf(srcFile), emptyList(), outDir)

        assertTrue(result.success, "Compilation should succeed: ${result.diagnostics}")
        assertTrue(File(outDir, "HelloKt.class").exists(), ".class file should be created")
    }

    @Test
    fun reportsErrorsForInvalidCode() {
        val srcFile = tmpDir.newFile("Bad.kt")
        srcFile.writeText("""
            fun bad(): String = 42
        """.trimIndent())

        val outDir = File(tmpDir.root, "out")
        val result = DirectCompiler.compile(listOf(srcFile), emptyList(), outDir)

        assertFalse(result.success, "Compilation should fail")
        assertTrue(result.diagnostics.any { it.severity == "ERROR" }, "Should have error diagnostics")
    }

    @Test
    fun reportsErrorWhenStdlibTypesUsedWithoutStdlib() {
        val srcFile = tmpDir.newFile("NeedsStdlib.kt")
        srcFile.writeText("""
            fun greet(): String = "hello"
            fun items(): List<String> = listOf("a")
        """.trimIndent())

        val outDir = File(tmpDir.root, "out")
        // noStdlib is true, so referencing List/listOf should fail
        val result = DirectCompiler.compile(listOf(srcFile), emptyList(), outDir)

        assertFalse(result.success, "Compilation should fail without stdlib")
        assertTrue(
            result.diagnostics.any { it.severity == "ERROR" },
            "Should have error diagnostics: ${result.diagnostics}",
        )
    }

    @Test
    fun clearsOutputDirBetweenCompilations() {
        val outDir = File(tmpDir.root, "out")
        outDir.mkdirs()
        File(outDir, "stale.class").writeText("stale")

        val srcFile = tmpDir.newFile("Fresh.kt")
        srcFile.writeText("fun fresh() {}")

        DirectCompiler.compile(listOf(srcFile), emptyList(), outDir)

        assertFalse(File(outDir, "stale.class").exists(), "Stale files should be cleaned")
        assertTrue(File(outDir, "FreshKt.class").exists(), "New output should exist")
    }

    @Test
    fun compilesWithClasspath() {
        val stdlib = findKotlinStdlib()
        assumeNotNull(stdlib)

        // Compile a dependency first
        val depFile = tmpDir.newFile("Dep.kt")
        depFile.writeText("""
            object Dep {
                fun value(): String = "from-dep"
            }
        """.trimIndent())
        val depOut = File(tmpDir.root, "dep-out")
        val depResult = DirectCompiler.compile(listOf(depFile), listOf(stdlib!!), depOut)
        assertTrue(depResult.success, "Dep compilation should succeed: ${depResult.diagnostics}")

        // Compile something that depends on it
        val mainFile = tmpDir.newFile("Main.kt")
        mainFile.writeText("""
            fun callDep(): String = Dep.value()
        """.trimIndent())
        val mainOut = File(tmpDir.root, "main-out")
        val mainResult = DirectCompiler.compile(
            listOf(mainFile),
            listOf(stdlib, depOut.absolutePath),
            mainOut,
        )

        assertTrue(mainResult.success, "Main compilation should succeed with dep on classpath: ${mainResult.diagnostics}")
    }

    @Test
    fun warmupDoesNotThrow() {
        val outDir = File(tmpDir.root, "warmup-out")
        // Should not throw
        DirectCompiler.warmup(emptyList(), outDir)
    }

    @Test
    fun rejectsOutputDirOutsideTmpdir() {
        val srcFile = tmpDir.newFile("Hello.kt")
        srcFile.writeText("fun hello() {}")

        val badDir = File("/tmp/../usr/local/dangerous")
        val ex = kotlin.test.assertFailsWith<IllegalArgumentException> {
            DirectCompiler.compile(listOf(srcFile), emptyList(), badDir)
        }
        assertTrue(ex.message!!.contains("java.io.tmpdir") || ex.message!!.contains("subdirectory"),
            "Error should mention tmpdir constraint: ${ex.message}")
    }

    @Test
    fun rejectsTmpdirItselfAsOutputDir() {
        val srcFile = tmpDir.newFile("Hello.kt")
        srcFile.writeText("fun hello() {}")

        val tmpdirItself = File(System.getProperty("java.io.tmpdir"))
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            DirectCompiler.compile(listOf(srcFile), emptyList(), tmpdirItself)
        }
    }
}
