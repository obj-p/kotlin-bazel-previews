package preview

import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceAnalyzerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    companion object {
        private lateinit var analyzer: SourceAnalyzer

        @BeforeClass @JvmStatic
        fun setUp() { analyzer = SourceAnalyzer() }

        @AfterClass @JvmStatic
        fun tearDown() { analyzer.close() }
    }

    @Test
    fun findsSimpleTopLevelFunction() {
        val content = """
            |package myapp
            |
            |@Preview
            |fun preview() {
            |    println("hello")
            |}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
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
            |@Preview
            |internal fun foo(): String = "foo"
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Utils.kt")
        assertEquals(1, result.size)
        assertEquals("foo", result[0].name)
        assertEquals("myapp.UtilsKt", result[0].jvmClassName)
    }

    @Test
    fun filtersSuspendFunctions() {
        val content = """
            |package myapp
            |
            |@Preview
            |suspend fun bar() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Utils.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun filtersPrivateFunctions() {
        val content = """
            |package myapp
            |
            |@Preview
            |private fun secret() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Secret.kt")
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
            |@Preview
            |fun preview() = "ok"
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun ignoresFunctionsWithParameters() {
        val content = """
            |package myapp
            |
            |fun greet(name: String): String = "Hello"
            |@Preview
            |fun preview() = "ok"
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun extractsPackageName() {
        val content = """
            |package com.example.app
            |
            |@Preview
            |fun doSomething() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "App.kt")
        assertEquals("com.example.app", result[0].packageName)
        assertEquals("com.example.app.AppKt", result[0].jvmClassName)
    }

    @Test
    fun handlesDefaultPackage() {
        val content = """
            |@Preview
            |fun topLevel() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Script.kt")
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

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Foo.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ignoresExtensionFunctions() {
        val content = """
            |package myapp
            |
            |fun String.exclaim() = this + "!"
            |@Preview
            |fun preview() = "ok"
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Ext.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun ignoresGenericFunctions() {
        val content = """
            |package myapp
            |
            |fun <T> identity(x: T): T = x
            |@Preview
            |fun preview() = "ok"
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Generic.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun findsMultipleTopLevelFunctions() {
        val content = """
            |package myapp
            |
            |@Preview
            |fun first() = 1
            |@Preview
            |fun second() = 2
            |@Preview
            |fun third() = 3
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Multi.kt")
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
            |@Preview
            |inline fun fast() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Inline.kt")
        assertEquals(1, result.size)
        assertEquals("fast", result[0].name)
    }

    @Test
    fun handlesFileJvmNameAnnotation() {
        val content = """
            |@file:JvmName("Custom")
            |package myapp
            |
            |@Preview
            |fun preview() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
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
            |@Preview
            |fun preview() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
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

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Foo.kt")
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

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Preview.kt")
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

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Preview.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun ignoresFunctionsWithDefaultParams() {
        val content = """
            |package myapp
            |
            |fun foo(x: Int = 0) {}
            |@Preview
            |fun bar() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Params.kt")
        assertEquals(1, result.size)
        assertEquals("bar", result[0].name)
    }

    @Test
    fun ignoresCompanionObjectMembers() {
        val content = """
            |package myapp
            |
            |class Foo {
            |    companion object {
            |        fun bar() {}
            |    }
            |}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Foo.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ignoresInterfaceDefaultMethods() {
        val content = """
            |package myapp
            |
            |interface I {
            |    fun bar() {}
            |}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "I.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ignoresEnumClassMembers() {
        val content = """
            |package myapp
            |
            |enum class E {
            |    A;
            |    fun bar() {}
            |}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "E.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun handlesFileJvmNameWithNamedArg() {
        val content = """
            |@file:JvmName(name = "Custom")
            |package myapp
            |
            |@Preview
            |fun preview() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("myapp.Custom", result[0].jvmClassName)
    }

    @Test
    fun handlesMultipleFileAnnotations() {
        val content = """
            |@file:Suppress("UNUSED")
            |@file:JvmName("Custom")
            |package myapp
            |
            |@Preview
            |fun preview() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("myapp.Custom", result[0].jvmClassName)
    }

    @Test
    fun handlesEmptyFile() {
        val result = analyzer.findTopLevelFunctionsFromContent("", "Empty.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun findTopLevelFunctions_readsFromFile() {
        val file = tmpDir.newFile("Sample.kt")
        file.writeText("""
            |package sample
            |
            |@Preview
            |fun greet() = "hi"
        """.trimMargin())

        val result = analyzer.findTopLevelFunctions(file.absolutePath)
        assertEquals(1, result.size)
        assertEquals("greet", result[0].name)
        assertEquals("sample", result[0].packageName)
        assertEquals("sample.SampleKt", result[0].jvmClassName)
    }

    @Test
    fun filtersUnannotatedFunctions() {
        val content = """
            |package myapp
            |
            |fun plain() {}
            |
            |@Preview
            |fun preview() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Mix.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
    }

    @Test
    fun findsPreviewWithArguments() {
        val content = """
            |package myapp
            |
            |@Preview(name = "dark", showBackground = true)
            |fun darkPreview() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Preview.kt")
        assertEquals(1, result.size)
        assertEquals("darkPreview", result[0].name)
    }

    @Test
    fun findsPreviewOnSameLine() {
        val content = """
            |package myapp
            |
            |@Preview fun inlinePreview() {}
        """.trimMargin()

        val result = analyzer.findTopLevelFunctionsFromContent(content, "Preview.kt")
        assertEquals(1, result.size)
        assertEquals("inlinePreview", result[0].name)
    }
}
