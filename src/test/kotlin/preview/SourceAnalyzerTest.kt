package preview

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceAnalyzerTest {
    @Test
    fun findsSimpleTopLevelFunction() {
        val content = """
            |package myapp
            |
            |fun preview() {
            |    println("hello")
            |}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
        assertEquals("myapp", result[0].packageName)
        assertEquals("myapp.GreeterKt", result[0].jvmClassName)
    }

    @Test
    fun findsFunctionWithInternalModifier() {
        val content = """
            |package myapp
            |
            |internal fun foo(): String = "foo"
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Utils.kt")
        assertEquals(1, result.size)
        assertEquals("foo", result[0].name)
        assertEquals("myapp.UtilsKt", result[0].jvmClassName)
    }

    @Test
    fun filtersSuspendFunctions() {
        val content = """
            |package myapp
            |
            |suspend fun bar() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Utils.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun filtersPrivateFunctions() {
        val content = """
            |package myapp
            |
            |private fun secret() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Secret.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ignoresIndentedMethods() {
        val content = """
            |package myapp
            |
            |object Greeter {
            |    fun greet(name: String): String {
            |        return "Hello"
            |    }
            |}
            |
            |fun preview() = "ok"
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun ignoresFunctionsWithParameters() {
        val content = """
            |package myapp
            |
            |fun greet(name: String): String = "Hello"
            |fun preview() = "ok"
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun extractsPackageName() {
        val content = """
            |package com.example.app
            |
            |fun doSomething() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "App.kt")
        assertEquals("com.example.app", result[0].packageName)
        assertEquals("com.example.app.AppKt", result[0].jvmClassName)
    }

    @Test
    fun handlesDefaultPackage() {
        val content = """
            |fun topLevel() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Script.kt")
        assertEquals(1, result.size)
        assertEquals("", result[0].packageName)
        assertEquals("ScriptKt", result[0].jvmClassName)
    }

    @Test
    fun returnsEmptyListWhenNoFunctions() {
        val content = """
            |package myapp
            |
            |class Foo {
            |    fun bar() {}
            |}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Foo.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ignoresExtensionFunctions() {
        val content = """
            |package myapp
            |
            |fun String.exclaim() = this + "!"
            |fun preview() = "ok"
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Ext.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun ignoresGenericFunctions() {
        val content = """
            |package myapp
            |
            |fun <T> identity(x: T): T = x
            |fun preview() = "ok"
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Generic.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun findsMultipleTopLevelFunctions() {
        val content = """
            |package myapp
            |
            |fun first() = 1
            |fun second() = 2
            |fun third() = 3
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Multi.kt")
        assertEquals(3, result.size)
        assertEquals("first", result[0].name)
        assertEquals("second", result[1].name)
        assertEquals("third", result[2].name)
    }

    @Test
    fun findsInlineTailrecFunctions() {
        val content = """
            |package myapp
            |
            |inline fun fast() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Inline.kt")
        assertEquals(1, result.size)
        assertEquals("fast", result[0].name)
    }

    @Test
    fun handlesFileJvmNameAnnotation() {
        val content = """
            |@file:JvmName("Custom")
            |package myapp
            |
            |fun preview() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
        assertEquals("myapp", result[0].packageName)
        assertEquals("myapp.Custom", result[0].jvmClassName)
    }

    @Test
    fun handlesFileJvmNameDefaultPackage() {
        val content = """
            |@file:JvmName("Custom")
            |
            |fun preview() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("Custom", result[0].jvmClassName)
    }

    @Test
    fun handlesUnindentedClassMembers() {
        val content = """
            |package myapp
            |
            |class Foo {
            |fun bar() {}
            |}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Foo.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun findsFunctionWithAnnotation() {
        val content = """
            |package myapp
            |
            |@Preview
            |fun preview() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Preview.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun findsFunctionWithMultipleAnnotations() {
        val content = """
            |package myapp
            |
            |@Composable
            |@Preview
            |fun preview() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Preview.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun ignoresFunctionsWithDefaultParams() {
        val content = """
            |package myapp
            |
            |fun foo(x: Int = 0) {}
            |fun bar() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Params.kt")
        assertEquals(1, result.size)
        assertEquals("bar", result[0].name)
    }
}
