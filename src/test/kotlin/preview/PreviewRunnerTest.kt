package preview

import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun throwsClassNotFoundForMissingClass() {
        val fn = FunctionInfo(name = "foo", packageName = "", jvmClassName = "NoSuchClass")
        assertFailsWith<ClassNotFoundException> {
            PreviewRunner.invoke(emptyList(), fn)
        }
    }

    @Test
    fun throwsNoSuchMethodForMissingMethod() {
        val source = """
            public class HasFoo {
                public static String foo() { return "foo"; }
            }
        """.trimIndent()

        val srcDir = tmpDir.newFolder("src-missing")
        val outDir = tmpDir.newFolder("classes-missing")
        val srcFile = java.io.File(srcDir, "HasFoo.java")
        srcFile.writeText(source)

        val javac = ProcessBuilder("javac", "-d", outDir.absolutePath, srcFile.absolutePath)
            .redirectErrorStream(true).start()
        javac.inputStream.bufferedReader().readText()
        javac.waitFor()

        val fn = FunctionInfo(name = "bar", packageName = "", jvmClassName = "HasFoo")
        assertFailsWith<NoSuchMethodException> {
            PreviewRunner.invoke(listOf(outDir.absolutePath), fn)
        }
    }

    @Test
    fun throwsForNonStaticMethod() {
        val source = """
            public class InstanceOnly {
                public String greet() { return "hi"; }
            }
        """.trimIndent()

        val srcDir = tmpDir.newFolder("src-instance")
        val outDir = tmpDir.newFolder("classes-instance")
        val srcFile = java.io.File(srcDir, "InstanceOnly.java")
        srcFile.writeText(source)

        val javac = ProcessBuilder("javac", "-d", outDir.absolutePath, srcFile.absolutePath)
            .redirectErrorStream(true).start()
        javac.inputStream.bufferedReader().readText()
        javac.waitFor()

        val fn = FunctionInfo(name = "greet", packageName = "", jvmClassName = "InstanceOnly")
        // Invoking a non-static method with null receiver should throw
        val ex = assertFailsWith<Exception> {
            PreviewRunner.invoke(listOf(outDir.absolutePath), fn)
        }
        assertTrue(ex is NullPointerException || ex is IllegalArgumentException,
            "Expected NPE or IAE, got: ${ex::class.simpleName}: ${ex.message}")
    }

    @Test
    fun handlesVoidReturnType() {
        val source = """
            public class VoidMethod {
                public static void doNothing() { }
            }
        """.trimIndent()

        val result = compileAndInvoke(source, "VoidMethod", "doNothing")
        assertNull(result)
    }
}
