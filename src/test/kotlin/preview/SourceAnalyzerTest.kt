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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
        assertEquals("myapp", result[0].packageName)
        assertEquals("myapp.GreeterKt", result[0].jvmClassName)
        assertEquals(ContainerKind.TOP_LEVEL, result[0].containerKind)
    }

    @Test
    fun findsFunctionWithInternalModifier() {
        val content = """
            |package myapp
            |
            |@Preview
            |internal fun foo(): String = "foo"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Utils.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Utils.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Secret.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Greeter.kt")
        // Now we only get the top-level preview (greet has a parameter, no @Preview)
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Greeter.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "App.kt")
        assertEquals("com.example.app", result[0].packageName)
        assertEquals("com.example.app.AppKt", result[0].jvmClassName)
    }

    @Test
    fun handlesDefaultPackage() {
        val content = """
            |@Preview
            |fun topLevel() {}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Script.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Foo.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Ext.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Generic.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Multi.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Inline.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Greeter.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Greeter.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Foo.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Preview.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Preview.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Params.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Foo.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "I.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "E.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Greeter.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Greeter.kt")
        assertEquals(1, result.size)
        assertEquals("myapp.Custom", result[0].jvmClassName)
    }

    @Test
    fun handlesEmptyFile() {
        val result = analyzer.findPreviewFunctionsFromContent("", "Empty.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun findPreviewFunctions_readsFromFile() {
        val file = tmpDir.newFile("Sample.kt")
        file.writeText("""
            |package sample
            |
            |@Preview
            |fun greet() = "hi"
        """.trimMargin())

        val result = analyzer.findPreviewFunctions(file.absolutePath)
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Mix.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Preview.kt")
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

        val result = analyzer.findPreviewFunctionsFromContent(content, "Preview.kt")
        assertEquals(1, result.size)
        assertEquals("inlinePreview", result[0].name)
    }

    // --- New tests for nested container support ---

    @Test
    fun findsPreviewFunctionInObject() {
        val content = """
            |package pkg
            |
            |object Previews {
            |    @Preview
            |    fun objectPreview() = "from object"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Previews.kt")
        assertEquals(1, result.size)
        assertEquals("objectPreview", result[0].name)
        assertEquals("pkg", result[0].packageName)
        assertEquals("pkg.Previews", result[0].jvmClassName)
        assertEquals(ContainerKind.OBJECT, result[0].containerKind)
    }

    @Test
    fun findsPreviewFunctionInClass() {
        val content = """
            |package pkg
            |
            |class MyPreviews {
            |    @Preview
            |    fun classPreview() = "from class"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "MyPreviews.kt")
        assertEquals(1, result.size)
        assertEquals("classPreview", result[0].name)
        assertEquals("pkg", result[0].packageName)
        assertEquals("pkg.MyPreviews", result[0].jvmClassName)
        assertEquals(ContainerKind.CLASS, result[0].containerKind)
    }

    @Test
    fun findsPreviewFunctionInCompanionObject() {
        val content = """
            |package pkg
            |
            |class Host {
            |    companion object {
            |        @Preview
            |        fun companionPreview() = "from companion"
            |    }
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Host.kt")
        assertEquals(1, result.size)
        assertEquals("companionPreview", result[0].name)
        assertEquals("pkg.Host\$Companion", result[0].jvmClassName)
        assertEquals(ContainerKind.OBJECT, result[0].containerKind)
    }

    @Test
    fun findsPreviewFunctionInNamedCompanionObject() {
        val content = """
            |package pkg
            |
            |class Host {
            |    companion object Factory {
            |        @Preview
            |        fun factoryPreview() = "from factory"
            |    }
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Host.kt")
        assertEquals(1, result.size)
        assertEquals("factoryPreview", result[0].name)
        assertEquals("pkg.Host\$Factory", result[0].jvmClassName)
        assertEquals(ContainerKind.OBJECT, result[0].containerKind)
    }

    @Test
    fun findsPreviewFunctionInNestedObject() {
        val content = """
            |package pkg
            |
            |class Outer {
            |    object Inner {
            |        @Preview
            |        fun innerPreview() = "from inner"
            |    }
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Outer.kt")
        assertEquals(1, result.size)
        assertEquals("innerPreview", result[0].name)
        assertEquals("pkg.Outer\$Inner", result[0].jvmClassName)
        assertEquals(ContainerKind.OBJECT, result[0].containerKind)
    }

    @Test
    fun findsMixOfTopLevelAndNestedPreviewFunctions() {
        val content = """
            |package pkg
            |
            |@Preview
            |fun topLevel() = "top"
            |
            |object Obj {
            |    @Preview
            |    fun objPreview() = "obj"
            |}
            |
            |class Cls {
            |    @Preview
            |    fun clsPreview() = "cls"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Mix.kt")
        assertEquals(3, result.size)
        assertEquals("topLevel", result[0].name)
        assertEquals(ContainerKind.TOP_LEVEL, result[0].containerKind)
        assertEquals("pkg.MixKt", result[0].jvmClassName)
        assertEquals("objPreview", result[1].name)
        assertEquals(ContainerKind.OBJECT, result[1].containerKind)
        assertEquals("pkg.Obj", result[1].jvmClassName)
        assertEquals("clsPreview", result[2].name)
        assertEquals(ContainerKind.CLASS, result[2].containerKind)
        assertEquals("pkg.Cls", result[2].jvmClassName)
    }

    @Test
    fun ignoresPreviewInInterface() {
        val content = """
            |package pkg
            |
            |interface Iface {
            |    @Preview
            |    fun ifacePreview() {}
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Iface.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ignoresPreviewInEnum() {
        val content = """
            |package pkg
            |
            |enum class MyEnum {
            |    A;
            |    @Preview
            |    fun enumPreview() = "enum"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "MyEnum.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ignoresPrivatePreviewInObject() {
        val content = """
            |package pkg
            |
            |object Previews {
            |    @Preview
            |    private fun secret() = "hidden"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Previews.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun ignoresPreviewInAbstractClass() {
        val content = """
            |package pkg
            |
            |abstract class Base {
            |    @Preview
            |    fun basePreview() = "base"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Base.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun findsPreviewInObjectWithDefaultPackage() {
        val content = """
            |object Previews {
            |    @Preview
            |    fun noPackage() = "default"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Previews.kt")
        assertEquals(1, result.size)
        assertEquals("noPackage", result[0].name)
        assertEquals("", result[0].packageName)
        assertEquals("Previews", result[0].jvmClassName)
        assertEquals(ContainerKind.OBJECT, result[0].containerKind)
    }

    // --- Tests for @PreviewParameter support ---

    @Test
    fun extractsProviderFromSamePackage() {
        val content = """
            |package examples
            |import preview.annotations.PreviewParameter
            |
            |@Preview
            |fun preview(@PreviewParameter(UserProvider::class) user: User) = "Hello"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals(1, result[0].parameters.size)
        assertEquals("user", result[0].parameters[0].name)
        assertEquals("User", result[0].parameters[0].type)
        assertEquals("examples.UserProvider", result[0].parameters[0].providerClass)
    }

    @Test
    fun extractsProviderFromExplicitImport() {
        val content = """
            |package examples
            |import preview.annotations.PreviewParameter
            |import com.other.UserProvider
            |
            |@Preview
            |fun preview(@PreviewParameter(UserProvider::class) user: User) = "Hello"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals(1, result[0].parameters.size)
        assertEquals("com.other.UserProvider", result[0].parameters[0].providerClass)
    }

    @Test
    fun extractsProviderFromImportAlias() {
        val content = """
            |package examples
            |import preview.annotations.PreviewParameter
            |import com.other.UserProvider as UP
            |
            |@Preview
            |fun preview(@PreviewParameter(UP::class) user: User) = "Hello"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals(1, result[0].parameters.size)
        assertEquals("com.other.UserProvider", result[0].parameters[0].providerClass)
    }

    @Test
    fun extractsFullyQualifiedProvider() {
        val content = """
            |package examples
            |import preview.annotations.PreviewParameter
            |
            |@Preview
            |fun preview(@PreviewParameter(com.other.Provider::class) user: User) = "Hello"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals(1, result[0].parameters.size)
        assertEquals("com.other.Provider", result[0].parameters[0].providerClass)
    }

    @Test
    fun supportsZeroParametersBackwardCompatibility() {
        val content = """
            |package examples
            |import preview.annotations.Preview
            |
            |@Preview
            |fun preview() = "Hello"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals(0, result[0].parameters.size)
    }

    @Test
    fun acceptsMultipleParameters() {
        val content = """
            |package examples
            |import preview.annotations.PreviewParameter
            |
            |@Preview
            |fun preview(
            |    @PreviewParameter(UserProvider::class) user: User,
            |    @PreviewParameter(ThemeProvider::class) theme: Theme
            |) = "Hello"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals("preview", result[0].name)
        assertEquals(2, result[0].parameters.size)
        assertEquals("user", result[0].parameters[0].name)
        assertEquals("theme", result[0].parameters[1].name)
    }

    @Test
    fun rejectsParameterWithoutAnnotation() {
        val content = """
            |package examples
            |
            |@Preview
            |fun preview(user: User) = "Hello"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertTrue(result.isEmpty())
    }

    @Test
    fun extractsProviderInDefaultPackage() {
        val content = """
            |import preview.annotations.PreviewParameter
            |
            |@Preview
            |fun preview(@PreviewParameter(UserProvider::class) user: User) = "Hello"
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals(1, result[0].parameters.size)
        assertEquals("UserProvider", result[0].parameters[0].providerClass)
    }

    @Test
    fun extractsProviderInObjectContainer() {
        val content = """
            |package examples
            |import preview.annotations.PreviewParameter
            |
            |object Previews {
            |    @Preview
            |    fun preview(@PreviewParameter(UserProvider::class) user: User) = "Hello"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals(ContainerKind.OBJECT, result[0].containerKind)
        assertEquals(1, result[0].parameters.size)
        assertEquals("examples.UserProvider", result[0].parameters[0].providerClass)
    }

    @Test
    fun extractsProviderInClassContainer() {
        val content = """
            |package examples
            |import preview.annotations.PreviewParameter
            |
            |class Previews {
            |    @Preview
            |    fun preview(@PreviewParameter(UserProvider::class) user: User) = "Hello"
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "Test.kt")
        assertEquals(1, result.size)
        assertEquals(ContainerKind.CLASS, result[0].containerKind)
        assertEquals(1, result[0].parameters.size)
        assertEquals("examples.UserProvider", result[0].parameters[0].providerClass)
    }

    @Test
    fun parsesParameterizedPreviewExample() {
        val content = """
            |package examples
            |
            |import preview.annotations.Preview
            |import preview.annotations.PreviewParameter
            |
            |object ParameterizedPreview {
            |
            |    @Preview
            |    fun userCard(@PreviewParameter(UserPreviewParameterProvider::class) user: User): String {
            |        return "User Card"
            |    }
            |}
        """.trimMargin()

        val result = analyzer.findPreviewFunctionsFromContent(content, "ParameterizedPreview.kt")
        assertEquals(1, result.size)
        assertEquals("userCard", result[0].name)
        assertEquals("examples", result[0].packageName)
        assertEquals("examples.ParameterizedPreview", result[0].jvmClassName)
        assertEquals(ContainerKind.OBJECT, result[0].containerKind)
        assertEquals(1, result[0].parameters.size)
        assertEquals("user", result[0].parameters[0].name)
        assertEquals("User", result[0].parameters[0].type)
        assertEquals("examples.UserPreviewParameterProvider", result[0].parameters[0].providerClass)
    }
}
