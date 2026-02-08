package preview

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatchingClassLoaderTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun compileJava(source: String, className: String, outDir: File) {
        val srcDir = tmpDir.newFolder()
        val srcFile = File(srcDir, "$className.java")
        srcFile.writeText(source)
        val javac = ProcessBuilder("javac", "-d", outDir.absolutePath, srcFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = javac.inputStream.bufferedReader().readText()
        val exitCode = javac.waitFor()
        if (exitCode != 0) throw RuntimeException("javac failed: $output")
    }

    @Test
    fun patchDirTakesPrecedenceOverClasspath() {
        val baseDir = tmpDir.newFolder("base")
        val patchDir = tmpDir.newFolder("patch")

        // Compile version 1 to base
        compileJava("""
            public class Target {
                public static String value() { return "base"; }
            }
        """.trimIndent(), "Target", baseDir)

        // Compile version 2 to patch
        compileJava("""
            public class Target {
                public static String value() { return "patched"; }
            }
        """.trimIndent(), "Target", patchDir)

        val urls = listOf(baseDir.toURI().toURL())
        val loader = PatchingClassLoader(patchDir, urls)
        loader.use {
            val clazz = it.loadClass("Target")
            val result = clazz.getMethod("value").invoke(null)
            assertEquals("patched", result)
        }
    }

    @Test
    fun fallsThroughToClasspathWhenNoPatch() {
        val baseDir = tmpDir.newFolder("base")
        val patchDir = tmpDir.newFolder("patch") // empty

        compileJava("""
            public class Fallback {
                public static String value() { return "base"; }
            }
        """.trimIndent(), "Fallback", baseDir)

        val urls = listOf(baseDir.toURI().toURL())
        val loader = PatchingClassLoader(patchDir, urls)
        loader.use {
            val clazz = it.loadClass("Fallback")
            val result = clazz.getMethod("value").invoke(null)
            assertEquals("base", result)
        }
    }

    @Test
    fun throwsClassNotFoundWhenMissing() {
        val patchDir = tmpDir.newFolder("patch")
        val loader = PatchingClassLoader(patchDir, emptyList())
        loader.use {
            assertFailsWith<ClassNotFoundException> {
                it.loadClass("com.nonexistent.Missing")
            }
        }
    }

    @Test
    fun findResourceReturnsPatchedResourceFirst() {
        val baseDir = tmpDir.newFolder("base")
        val patchDir = tmpDir.newFolder("patch")

        File(baseDir, "data.txt").writeText("base")
        File(patchDir, "data.txt").writeText("patched")

        val urls = listOf(baseDir.toURI().toURL())
        val loader = PatchingClassLoader(patchDir, urls)
        loader.use {
            val url = it.getResource("data.txt")
            assertNotNull(url)
            assertEquals("patched", url.readText())
        }
    }

    @Test
    fun findResourceFallsThroughToClasspath() {
        val baseDir = tmpDir.newFolder("base")
        val patchDir = tmpDir.newFolder("patch") // empty

        File(baseDir, "data.txt").writeText("base")

        val urls = listOf(baseDir.toURI().toURL())
        val loader = PatchingClassLoader(patchDir, urls)
        loader.use {
            val url = it.getResource("data.txt")
            assertNotNull(url)
            assertEquals("base", url.readText())
        }
    }

    @Test
    fun findResourceReturnsNullWhenMissing() {
        val patchDir = tmpDir.newFolder("patch")
        val loader = PatchingClassLoader(patchDir, emptyList())
        loader.use {
            assertNull(it.getResource("nonexistent.txt"))
        }
    }

    @Test
    fun findResourcesReturnsPatchedFirst() {
        val baseDir = tmpDir.newFolder("base")
        val patchDir = tmpDir.newFolder("patch")

        File(baseDir, "data.txt").writeText("base")
        File(patchDir, "data.txt").writeText("patched")

        val urls = listOf(baseDir.toURI().toURL())
        val loader = PatchingClassLoader(patchDir, urls)
        loader.use {
            val resources = it.getResources("data.txt").toList()
            assertEquals(2, resources.size, "Should find both patched and base resources")
            assertEquals("patched", resources[0].readText())
            assertEquals("base", resources[1].readText())
        }
    }

    @Test
    fun findResourcesReturnsOnlyBaseWhenNoPatch() {
        val baseDir = tmpDir.newFolder("base")
        val patchDir = tmpDir.newFolder("patch") // empty

        File(baseDir, "data.txt").writeText("base")

        val urls = listOf(baseDir.toURI().toURL())
        val loader = PatchingClassLoader(patchDir, urls)
        loader.use {
            val resources = it.getResources("data.txt").toList()
            assertEquals(1, resources.size)
            assertEquals("base", resources[0].readText())
        }
    }

    @Test
    fun findResourcesReturnsEmptyWhenMissing() {
        val patchDir = tmpDir.newFolder("patch")
        val loader = PatchingClassLoader(patchDir, emptyList())
        loader.use {
            val resources = it.getResources("nonexistent.txt").toList()
            assertTrue(resources.isEmpty())
        }
    }
}
