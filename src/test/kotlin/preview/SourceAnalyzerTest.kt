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
    fun findsFunctionWithModifiers() {
        val content = """
            |package myapp
            |
            |internal fun foo(): String = "foo"
            |
            |suspend fun bar() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Utils.kt")
        assertEquals(2, result.size)
        assertEquals("foo", result[0].name)
        assertEquals("bar", result[1].name)
        assertEquals("myapp.UtilsKt", result[0].jvmClassName)
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
    fun handlesPrivateModifier() {
        val content = """
            |package myapp
            |
            |private fun secret() {}
        """.trimMargin()

        val result = SourceAnalyzer.findTopLevelFunctionsFromContent(content, "Secret.kt")
        assertEquals(1, result.size)
        assertEquals("secret", result[0].name)
    }
}
