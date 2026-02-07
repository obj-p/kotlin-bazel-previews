package preview

import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PreviewRunnerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun compileAndInvoke(javaSource: String, className: String, methodName: String): Any? {
        val srcDir = tmpDir.newFolder("src")
        val outDir = tmpDir.newFolder("classes")

        val srcFile = java.io.File(srcDir, "$className.java")
        srcFile.writeText(javaSource)

        // Compile with javac
        val javac = ProcessBuilder("javac", "-d", outDir.absolutePath, srcFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val javacOutput = javac.inputStream.bufferedReader().readText()
        val exitCode = javac.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("javac failed: $javacOutput")
        }

        val fn = FunctionInfo(
            name = methodName,
            packageName = "",
            jvmClassName = className,
        )
        return PreviewRunner.invoke(listOf(outDir.absolutePath), fn)
    }

    @Test
    fun invokesStaticMethodAndReturnsStringResult() {
        val source = """
            public class TestTarget {
                public static String hello() {
                    return "Hello, World!";
                }
            }
        """.trimIndent()

        val result = compileAndInvoke(source, "TestTarget", "hello")
        assertEquals("Hello, World!", result)
    }

    @Test
    fun handlesNullReturnValue() {
        val source = """
            public class VoidTarget {
                public static Object nothing() {
                    return null;
                }
            }
        """.trimIndent()

        val result = compileAndInvoke(source, "VoidTarget", "nothing")
        assertEquals(null, result)
    }

    @Test
    fun unwrapsInvocationTargetException() {
        val source = """
            public class ThrowTarget {
                public static String boom() {
                    throw new RuntimeException("kaboom");
                }
            }
        """.trimIndent()

        val ex = assertFailsWith<RuntimeException> {
            compileAndInvoke(source, "ThrowTarget", "boom")
        }
        assertEquals("kaboom", ex.message)
    }

    @Test
    fun convertsResultToStringBeforeClosingLoader() {
        // Returns a custom object — toString() must happen before classloader closes.
        val source = """
            public class CustomReturn {
                public static Object get() {
                    return new Object() {
                        @Override public String toString() { return "custom-value"; }
                    };
                }
            }
        """.trimIndent()

        val result = compileAndInvoke(source, "CustomReturn", "get")
        assertEquals("custom-value", result)
    }
}
