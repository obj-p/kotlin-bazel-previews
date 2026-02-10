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

    private fun compileAndInvoke(
        javaSource: String,
        className: String,
        methodName: String,
        containerKind: ContainerKind = ContainerKind.TOP_LEVEL,
    ): Any? {
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
            containerKind = containerKind,
        )

        // Use old direct invocation path for backward compatibility with old tests
        val urls = listOf(outDir.absolutePath).map { java.io.File(it).toURI().toURL() }.toTypedArray()
        val loader = java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader())

        return try {
            loader.use {
                val clazz = it.loadClass(fn.jvmClassName)
                val method = clazz.getMethod(fn.name)
                val receiver = when (fn.containerKind) {
                    ContainerKind.TOP_LEVEL -> null
                    ContainerKind.OBJECT -> clazz.getDeclaredField("INSTANCE").get(null)
                    ContainerKind.CLASS -> clazz.getDeclaredConstructor().newInstance()
                }
                val result = method.invoke(receiver)
                result?.toString()
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun compileAndInvokeList(
        javaSource: String,
        className: String,
        methodName: String,
        containerKind: ContainerKind = ContainerKind.TOP_LEVEL,
        parameters: List<ParameterInfo> = emptyList()
    ): List<PreviewResult> {
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
            containerKind = containerKind,
            parameters = parameters
        )
        return PreviewRunner.invoke(listOf(outDir.absolutePath), fn)
    }

    private fun compileKotlinAndInvokeList(
        sourceFiles: Map<String, String>,  // filename -> source content
        className: String,
        methodName: String,
        containerKind: ContainerKind = ContainerKind.TOP_LEVEL,
        parameters: List<ParameterInfo> = emptyList()
    ): List<PreviewResult> {
        val srcDir = tmpDir.newFolder("kt-src")
        val outDir = tmpDir.newFolder("kt-classes")

        // Write all source files
        for ((filename, content) in sourceFiles) {
            java.io.File(srcDir, filename).writeText(content)
        }

        // Get Kotlin stdlib from classpath
        val kotlinStdlib = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .find { it.contains("kotlin-stdlib") }
            ?: throw RuntimeException("kotlin-stdlib not found on classpath")

        // Get kotlinc from the system
        val result = DirectCompiler.compile(
            sourceFiles = sourceFiles.keys.map { java.io.File(srcDir, it) },
            classpath = listOf(getAnnotationsClasspath(), kotlinStdlib),
            outputDir = outDir
        )

        if (!result.success) {
            val errors = result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" }
            throw RuntimeException("kotlinc failed:\n$errors")
        }

        val fn = FunctionInfo(
            name = methodName,
            packageName = "",
            jvmClassName = className,
            containerKind = containerKind,
            parameters = parameters
        )
        return PreviewRunner.invoke(listOf(outDir.absolutePath, getAnnotationsClasspath(), kotlinStdlib), fn)
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
        val results = PreviewRunner.invoke(emptyList(), fn)
        assertEquals(1, results.size)
        assertTrue(results[0].error?.contains("NoSuchClass") == true)
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
        val results = PreviewRunner.invoke(listOf(outDir.absolutePath), fn)
        assertEquals(1, results.size)
        assertTrue(results[0].error?.contains("bar") == true || results[0].error?.contains("NoSuchMethod") == true)
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
        // Invoking a non-static method with null receiver should return error
        val results = PreviewRunner.invoke(listOf(outDir.absolutePath), fn)
        assertEquals(1, results.size)
        assertTrue(results[0].error != null, "Expected error but got result: ${results[0].result}")
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

    // --- New tests for object/class invocation ---

    @Test
    fun invokesObjectInstanceMethod() {
        // Mimics Kotlin object: private ctor, public static final INSTANCE field
        val source = """
            public class ObjTarget {
                public static final ObjTarget INSTANCE = new ObjTarget();
                private ObjTarget() {}
                public String hello() {
                    return "from-object";
                }
            }
        """.trimIndent()

        val result = compileAndInvoke(source, "ObjTarget", "hello", ContainerKind.OBJECT)
        assertEquals("from-object", result)
    }

    @Test
    fun invokesClassInstanceMethod() {
        // Regular class with public no-arg constructor
        val source = """
            public class ClsTarget {
                public ClsTarget() {}
                public String hello() {
                    return "from-class";
                }
            }
        """.trimIndent()

        val result = compileAndInvoke(source, "ClsTarget", "hello", ContainerKind.CLASS)
        assertEquals("from-class", result)
    }

    @Test
    fun throwsForClassWithNoNoArgConstructor() {
        val source = """
            public class NoDefaultCtor {
                public NoDefaultCtor(int x) {}
                public String hello() { return "hi"; }
            }
        """.trimIndent()

        assertFailsWith<NoSuchMethodException> {
            compileAndInvoke(source, "NoDefaultCtor", "hello", ContainerKind.CLASS)
        }
    }

    @Test
    fun throwsForObjectWithNoInstanceField() {
        val source = """
            public class NoInstance {
                public String hello() { return "hi"; }
            }
        """.trimIndent()

        assertFailsWith<NoSuchFieldException> {
            compileAndInvoke(source, "NoInstance", "hello", ContainerKind.OBJECT)
        }
    }

    // --- New tests for parameterized previews ---

    @Test
    fun zeroParameterBackwardCompatibility() {
        // Zero-parameter functions should return a list with single result, no displayName
        val source = """
            public class SimplePreview {
                public static String preview() {
                    return "simple";
                }
            }
        """.trimIndent()

        val results = compileAndInvokeList(source, "SimplePreview", "preview")
        assertEquals(1, results.size)
        assertEquals("preview", results[0].functionName)
        assertEquals(null, results[0].displayName)
        assertEquals("simple", results[0].result)
        assertEquals(null, results[0].error)
        assertEquals("preview", results[0].fullDisplayName)
    }

    @Test
    fun multipleInvocationsWithSameClassloader() {
        // Create a provider that returns 3 values
        val results = compileKotlinAndInvokeList(
            mapOf(
                "TestProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class TestProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("value1", "value2", "value3")
                    }
                """.trimIndent(),
                "ParameterizedPreview.kt" to """
                    fun preview(arg: String): String {
                        return "got:${'$'}arg"
                    }
                """.trimIndent()
            ),
            className = "ParameterizedPreviewKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "TestProvider"))
        )

        // Verify all toString() calls succeeded
        assertEquals(3, results.size)
        assertEquals("got:value1", results[0].result)
        assertEquals("got:value2", results[1].result)
        assertEquals("got:value3", results[2].result)
        assertEquals("preview[0]", results[0].fullDisplayName)
        assertEquals("preview[1]", results[1].fullDisplayName)
        assertEquals("preview[2]", results[2].fullDisplayName)
    }

    @Test
    fun providerInstantiationObject() {
        // Test INSTANCE field resolution for Kotlin object
        val results = compileKotlinAndInvokeList(
            mapOf(
                "ObjectProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    object ObjectProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("from-object")
                    }
                """.trimIndent(),
                "PreviewWithObject.kt" to """
                    fun preview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "PreviewWithObjectKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "ObjectProvider"))
        )

        assertEquals(1, results.size)
        assertEquals("from-object", results[0].result)
    }

    @Test
    fun providerInstantiationClass() {
        // Test no-arg constructor for regular class
        val results = compileKotlinAndInvokeList(
            mapOf(
                "ClassProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class ClassProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("from-class")
                    }
                """.trimIndent(),
                "PreviewWithClass.kt" to """
                    fun preview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "PreviewWithClassKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "ClassProvider"))
        )

        assertEquals(1, results.size)
        assertEquals("from-class", results[0].result)
    }

    @Test
    fun individualInvocationFailureDoesntStopOthers() {
        // One invocation fails, others succeed
        val results = compileKotlinAndInvokeList(
            mapOf(
                "MixedProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class MixedProvider : PreviewParameterProvider<Int> {
                        override val values = sequenceOf(1, 0, 2)
                    }
                """.trimIndent(),
                "DivisionPreview.kt" to """
                    fun preview(divisor: Int): String {
                        require(divisor != 0) { "Cannot divide by zero" }
                        return (10 / divisor).toString()
                    }
                """.trimIndent()
            ),
            className = "DivisionPreviewKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("divisor", "Int", "MixedProvider"))
        )

        assertEquals(3, results.size)
        assertEquals("10", results[0].result)
        assertEquals(null, results[0].error)
        assertTrue(results[1].error?.contains("divide by zero") == true)
        assertEquals("5", results[2].result)
        assertEquals(null, results[2].error)
    }

    @Test
    fun providerInstantiationFailure() {
        // Returns single error result
        val results = compileKotlinAndInvokeList(
            mapOf(
                "PreviewWithBadProvider.kt" to """
                    fun preview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "PreviewWithBadProviderKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "NonExistentProvider"))
        )

        assertEquals(1, results.size)
        assertTrue(results[0].error?.contains("Failed to instantiate provider") == true)
    }

    @Test
    fun emptyProviderSequence() {
        // Returns single error result
        val results = compileKotlinAndInvokeList(
            mapOf(
                "EmptyProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class EmptyProvider : PreviewParameterProvider<String> {
                        override val values = emptySequence<String>()
                    }
                """.trimIndent(),
                "PreviewWithEmpty.kt" to """
                    fun preview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "PreviewWithEmptyKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "EmptyProvider"))
        )

        assertEquals(1, results.size)
        assertTrue(results[0].error?.contains("returned no values") == true)
    }

    @Test
    fun hundredResultHardLimit() {
        // Provider with 200 values returns exactly 100 results
        val results = compileKotlinAndInvokeList(
            mapOf(
                "LargeProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class LargeProvider : PreviewParameterProvider<Int> {
                        override val values = sequence {
                            for (i in 0 until 200) {
                                yield(i)
                            }
                        }
                    }
                """.trimIndent(),
                "PreviewWithLarge.kt" to """
                    fun preview(arg: Int): String = "value:${'$'}arg"
                """.trimIndent()
            ),
            className = "PreviewWithLargeKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "Int", "LargeProvider"))
        )

        assertEquals(100, results.size)
        assertEquals("value:0", results[0].result)
        assertEquals("value:99", results[99].result)
    }

    @Test
    fun warningForMoreThanTwentyResults() {
        // Verify stderr warning for >20 results
        // Capture stderr
        val originalErr = System.err
        val errCapture = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(errCapture))

        try {
            val results = compileKotlinAndInvokeList(
                mapOf(
                    "MediumProvider.kt" to """
                        import preview.annotations.PreviewParameterProvider

                        class MediumProvider : PreviewParameterProvider<Int> {
                            override val values = (0 until 25).asSequence()
                        }
                    """.trimIndent(),
                    "PreviewWithMedium.kt" to """
                        fun preview(arg: Int): String = "value:${'$'}arg"
                    """.trimIndent()
                ),
                className = "PreviewWithMediumKt",
                methodName = "preview",
                parameters = listOf(ParameterInfo("arg", "Int", "MediumProvider"))
            )

            assertEquals(25, results.size)

            val errOutput = errCapture.toString()
            assertTrue(errOutput.contains("Warning"))
            assertTrue(errOutput.contains("25 previews"))
            assertTrue(errOutput.contains("limit: 100"))
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun displayNameFormatting() {
        // Verify fullDisplayName is "functionName[0]", "functionName[1]", etc.
        val results = compileKotlinAndInvokeList(
            mapOf(
                "SimpleProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class SimpleProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("a", "b")
                    }
                """.trimIndent(),
                "NamedPreview.kt" to """
                    fun myPreview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "NamedPreviewKt",
            methodName = "myPreview",
            parameters = listOf(ParameterInfo("arg", "String", "SimpleProvider"))
        )

        assertEquals(2, results.size)
        assertEquals("myPreview", results[0].functionName)
        assertEquals("[0]", results[0].displayName)
        assertEquals("myPreview[0]", results[0].fullDisplayName)
        assertEquals("myPreview", results[1].functionName)
        assertEquals("[1]", results[1].displayName)
        assertEquals("myPreview[1]", results[1].fullDisplayName)
    }

    private fun getAnnotationsClasspath(): String {
        // Find the preview-annotations jar on the classpath
        val cp = System.getProperty("java.class.path")
        val entries = cp.split(java.io.File.pathSeparator)
        val annotationsJar = entries.find { it.contains("preview-annotations") }
        return annotationsJar ?: throw RuntimeException("preview-annotations jar not found on classpath")
    }

    private fun compileKotlinSources(
        sources: Map<String, String>,  // filename -> source
        outputDir: java.io.File
    ) {
        val srcDir = tmpDir.newFolder("src-${System.currentTimeMillis()}")

        // Write sources
        sources.forEach { (filename, content) ->
            java.io.File(srcDir, filename).writeText(content)
        }

        // Compile
        val kotlinStdlib = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .find { it.contains("kotlin-stdlib") }
            ?: throw RuntimeException("kotlin-stdlib not found")

        val result = DirectCompiler.compile(
            sourceFiles = sources.keys.map { java.io.File(srcDir, it) },
            classpath = listOf(getAnnotationsClasspath(), kotlinStdlib),
            outputDir = outputDir
        )

        if (!result.success) {
            throw RuntimeException("Compilation failed: ${result.diagnostics}")
        }
    }

    // --- Integration tests for PatchingClassLoader ---

    @Test
    fun patchingClassLoaderLoadsProviderFromPatch() {
        // Setup: Compile provider and preview to baseDir
        val baseDir = tmpDir.newFolder("base")
        val patchDir = tmpDir.newFolder("patch")

        val providerSource = """
            import preview.annotations.PreviewParameterProvider

            class TestProvider : PreviewParameterProvider<String> {
                override val values = sequenceOf("base-value")
            }
        """.trimIndent()

        val previewSource = """
            fun testPreview(arg: String): String = "Result: ${'$'}arg"
        """.trimIndent()

        // Compile both to baseDir
        compileKotlinSources(
            mapOf(
                "TestProvider.kt" to providerSource,
                "TestPreview.kt" to previewSource
            ),
            baseDir
        )

        // Compile modified provider to patchDir
        val patchedProviderSource = """
            import preview.annotations.PreviewParameterProvider

            class TestProvider : PreviewParameterProvider<String> {
                override val values = sequenceOf("patched-value")  // Changed
            }
        """.trimIndent()

        compileKotlinSources(
            mapOf("TestProvider.kt" to patchedProviderSource),
            patchDir
        )

        // Create PatchingClassLoader with annotations and stdlib on classpath
        val kotlinStdlib = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .find { it.contains("kotlin-stdlib") }
            ?: throw RuntimeException("kotlin-stdlib not found")
        val baseUrls = listOf(baseDir.absolutePath, getAnnotationsClasspath(), kotlinStdlib)
        val loader = PatchingClassLoader(
            patchDir,
            baseUrls.map { java.io.File(it).toURI().toURL() }
        )

        // Invoke with PatchingClassLoader
        val fn = FunctionInfo(
            name = "testPreview",
            packageName = "",
            jvmClassName = "TestPreviewKt",
            parameters = listOf(ParameterInfo("arg", "String", "TestProvider"))
        )

        val results = PreviewRunner.invoke(loader, fn)

        // Verify patched provider was used
        assertEquals(1, results.size)
        assertEquals("Result: patched-value", results[0].result)
        assertNull(results[0].error)
    }

    @Test
    fun patchingClassLoaderLoadsPreviewFromPatch() {
        // Setup: Compile both to baseDir
        val baseDir = tmpDir.newFolder("base2")
        val patchDir = tmpDir.newFolder("patch2")

        val providerSource = """
            import preview.annotations.PreviewParameterProvider

            class TestProvider2 : PreviewParameterProvider<String> {
                override val values = sequenceOf("value1", "value2")
            }
        """.trimIndent()

        val previewSource = """
            fun testPreview(arg: String): String = "Original: ${'$'}arg"
        """.trimIndent()

        compileKotlinSources(
            mapOf(
                "TestProvider2.kt" to providerSource,
                "TestPreview2.kt" to previewSource
            ),
            baseDir
        )

        // Compile modified preview to patchDir
        val patchedPreviewSource = """
            fun testPreview(arg: String): String = "Patched: ${'$'}arg"  // Changed
        """.trimIndent()

        compileKotlinSources(
            mapOf("TestPreview2.kt" to patchedPreviewSource),
            patchDir
        )

        // Create PatchingClassLoader with annotations and stdlib on classpath
        val kotlinStdlib = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .find { it.contains("kotlin-stdlib") }
            ?: throw RuntimeException("kotlin-stdlib not found")
        val baseUrls = listOf(baseDir.absolutePath, getAnnotationsClasspath(), kotlinStdlib)
        val loader = PatchingClassLoader(
            patchDir,
            baseUrls.map { java.io.File(it).toURI().toURL() }
        )

        // Invoke
        val fn = FunctionInfo(
            name = "testPreview",
            packageName = "",
            jvmClassName = "TestPreview2Kt",
            parameters = listOf(ParameterInfo("arg", "String", "TestProvider2"))
        )

        val results = PreviewRunner.invoke(loader, fn)

        // Verify patched preview was used with base provider
        assertEquals(2, results.size)
        assertEquals("Patched: value1", results[0].result)
        assertEquals("Patched: value2", results[1].result)
    }

    @Test
    fun patchingClassLoaderLoadsBothFromPatch() {
        // Simulate same-file scenario: both compiled together
        val baseDir = tmpDir.newFolder("base3")
        val patchDir = tmpDir.newFolder("patch3")

        // Base version
        val baseSources = """
            import preview.annotations.PreviewParameterProvider

            class TestProvider3 : PreviewParameterProvider<String> {
                override val values = sequenceOf("base")
            }

            fun testPreview(arg: String): String = "Base: ${'$'}arg"
        """.trimIndent()

        compileKotlinSources(
            mapOf("Combined.kt" to baseSources),
            baseDir
        )

        // Patched version (both modified)
        val patchedSources = """
            import preview.annotations.PreviewParameterProvider

            class TestProvider3 : PreviewParameterProvider<String> {
                override val values = sequenceOf("patched1", "patched2")  // Changed
            }

            fun testPreview(arg: String): String = "Patched: ${'$'}arg"  // Changed
        """.trimIndent()

        compileKotlinSources(
            mapOf("Combined.kt" to patchedSources),
            patchDir
        )

        // Create PatchingClassLoader with annotations and stdlib on classpath
        val kotlinStdlib = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .find { it.contains("kotlin-stdlib") }
            ?: throw RuntimeException("kotlin-stdlib not found")
        val baseUrls = listOf(baseDir.absolutePath, getAnnotationsClasspath(), kotlinStdlib)
        val loader = PatchingClassLoader(
            patchDir,
            baseUrls.map { java.io.File(it).toURI().toURL() }
        )

        // Invoke
        val fn = FunctionInfo(
            name = "testPreview",
            packageName = "",
            jvmClassName = "CombinedKt",
            parameters = listOf(ParameterInfo("arg", "String", "TestProvider3"))
        )

        val results = PreviewRunner.invoke(loader, fn)

        // Verify both patched versions were used
        assertEquals(2, results.size)
        assertEquals("Patched: patched1", results[0].result)
        assertEquals("Patched: patched2", results[1].result)
    }

    // --- Phase 2: Custom Display Names Tests ---

    @Test
    fun customDisplayNamesFromProvider() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "CustomNameProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class CustomNameProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("a", "b", "c")

                        override fun getDisplayName(index: Int): String? {
                            return listOf("First", "Second", "Third").getOrNull(index)
                        }
                    }
                """.trimIndent(),
                "CustomPreview.kt" to """
                    fun preview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "CustomPreviewKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "CustomNameProvider"))
        )

        assertEquals(3, results.size)
        assertEquals("preview[First]", results[0].fullDisplayName)
        assertEquals("preview[Second]", results[1].fullDisplayName)
        assertEquals("preview[Third]", results[2].fullDisplayName)
    }

    @Test
    fun customDisplayNameReturnsNull() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "PartialNameProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class PartialNameProvider : PreviewParameterProvider<Int> {
                        override val values = sequenceOf(1, 2, 3)

                        override fun getDisplayName(index: Int): String? {
                            return if (index == 1) "Middle" else null
                        }
                    }
                """.trimIndent(),
                "PartialPreview.kt" to """
                    fun preview(arg: Int): String = "value:${'$'}arg"
                """.trimIndent()
            ),
            className = "PartialPreviewKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "Int", "PartialNameProvider"))
        )

        assertEquals(3, results.size)
        assertEquals("preview[0]", results[0].fullDisplayName)
        assertEquals("preview[Middle]", results[1].fullDisplayName)
        assertEquals("preview[2]", results[2].fullDisplayName)
    }

    @Test
    fun missingGetDisplayNameMethod() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "NoDisplayNameProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class NoDisplayNameProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("x", "y")
                    }
                """.trimIndent(),
                "SimplePreview.kt" to """
                    fun preview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "SimplePreviewKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "NoDisplayNameProvider"))
        )

        assertEquals(2, results.size)
        assertEquals("preview[0]", results[0].fullDisplayName)
        assertEquals("preview[1]", results[1].fullDisplayName)
    }

    @Test
    fun getDisplayNameThrowsException() {
        val originalErr = System.err
        val errCapture = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(errCapture))

        try {
            val results = compileKotlinAndInvokeList(
                mapOf(
                    "ThrowingProvider.kt" to """
                        import preview.annotations.PreviewParameterProvider

                        class ThrowingProvider : PreviewParameterProvider<String> {
                            override val values = sequenceOf("a", "b")

                            override fun getDisplayName(index: Int): String? {
                                throw RuntimeException("Display name error")
                            }
                        }
                    """.trimIndent(),
                    "ThrowPreview.kt" to """
                        fun preview(arg: String): String = arg
                    """.trimIndent()
                ),
                className = "ThrowPreviewKt",
                methodName = "preview",
                parameters = listOf(ParameterInfo("arg", "String", "ThrowingProvider"))
            )

            assertEquals(2, results.size)
            assertEquals("preview[0]", results[0].fullDisplayName)
            assertEquals("preview[1]", results[1].fullDisplayName)

            val errOutput = errCapture.toString()
            assertTrue(errOutput.contains("getDisplayName"))
            assertTrue(errOutput.contains("exception"))
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun blankDisplayNamesFallBackToIndex() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "BlankNameProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class BlankNameProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("a", "b", "c")

                        override fun getDisplayName(index: Int): String? {
                            return when (index) {
                                0 -> ""
                                1 -> "   "
                                2 -> "Valid"
                                else -> null
                            }
                        }
                    }
                """.trimIndent(),
                "BlankPreview.kt" to """
                    fun preview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "BlankPreviewKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "BlankNameProvider"))
        )

        assertEquals(3, results.size)
        assertEquals("preview[0]", results[0].fullDisplayName)
        assertEquals("preview[1]", results[1].fullDisplayName)
        assertEquals("preview[Valid]", results[2].fullDisplayName)
    }

    @Test
    fun objectProviderWithCustomNames() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "ObjectProvider.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    object ObjectProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("alpha", "beta")

                        override fun getDisplayName(index: Int): String? {
                            return listOf("Greek-1", "Greek-2").getOrNull(index)
                        }
                    }
                """.trimIndent(),
                "ObjectPreview.kt" to """
                    fun preview(arg: String): String = arg
                """.trimIndent()
            ),
            className = "ObjectPreviewKt",
            methodName = "preview",
            parameters = listOf(ParameterInfo("arg", "String", "ObjectProvider"))
        )

        assertEquals(2, results.size)
        assertEquals("preview[Greek-1]", results[0].fullDisplayName)
        assertEquals("preview[Greek-2]", results[1].fullDisplayName)
    }

    // ============================================================
    // Phase 3: Multi-Parameter Support Tests
    // ============================================================

    @Test
    fun twoParametersBasicCase() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class UserProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("Alice", "Bob")
                    }

                    class ThemeProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("Light", "Dark")
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun textCard(user: String, theme: String): String {
                        return "Card for ${'$'}user in ${'$'}theme theme"
                    }
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "textCard",
            parameters = listOf(
                ParameterInfo("user", "String", "UserProvider"),
                ParameterInfo("theme", "String", "ThemeProvider")
            )
        )

        // Should generate 2 × 2 = 4 combinations
        assertEquals(4, results.size)
        assertEquals("textCard[0, 0]", results[0].fullDisplayName)
        assertEquals("Card for Alice in Light theme", results[0].result)
        assertEquals("textCard[0, 1]", results[1].fullDisplayName)
        assertEquals("Card for Alice in Dark theme", results[1].result)
        assertEquals("textCard[1, 0]", results[2].fullDisplayName)
        assertEquals("Card for Bob in Light theme", results[2].result)
        assertEquals("textCard[1, 1]", results[3].fullDisplayName)
        assertEquals("Card for Bob in Dark theme", results[3].result)
    }

    @Test
    fun threeParameters() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class ColorProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("Red", "Blue")
                    }

                    class SizeProvider : PreviewParameterProvider<Int> {
                        override val values = sequenceOf(10, 20)
                    }

                    class StyleProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("Bold", "Italic")
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun styledText(color: String, size: Int, style: String): String {
                        return "${'$'}color text at ${'$'}{size}pt in ${'$'}style"
                    }
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "styledText",
            parameters = listOf(
                ParameterInfo("color", "String", "ColorProvider"),
                ParameterInfo("size", "Int", "SizeProvider"),
                ParameterInfo("style", "String", "StyleProvider")
            )
        )

        // Should generate 2 × 2 × 2 = 8 combinations
        assertEquals(8, results.size)
        assertEquals("styledText[0, 0, 0]", results[0].fullDisplayName)
        assertEquals("Red text at 10pt in Bold", results[0].result)
        assertEquals("styledText[0, 0, 1]", results[1].fullDisplayName)
        assertEquals("Red text at 10pt in Italic", results[1].result)
        assertEquals("styledText[0, 1, 0]", results[2].fullDisplayName)
        assertEquals("Red text at 20pt in Bold", results[2].result)
        assertEquals("styledText[0, 1, 1]", results[3].fullDisplayName)
        assertEquals("Red text at 20pt in Italic", results[3].result)
        assertEquals("styledText[1, 0, 0]", results[4].fullDisplayName)
        assertEquals("Blue text at 10pt in Bold", results[4].result)
        assertEquals("styledText[1, 0, 1]", results[5].fullDisplayName)
        assertEquals("Blue text at 10pt in Italic", results[5].result)
        assertEquals("styledText[1, 1, 0]", results[6].fullDisplayName)
        assertEquals("Blue text at 20pt in Bold", results[6].result)
        assertEquals("styledText[1, 1, 1]", results[7].fullDisplayName)
        assertEquals("Blue text at 20pt in Italic", results[7].result)
    }

    @Test
    fun exactlyAt100Limit() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class TenProvider : PreviewParameterProvider<Int> {
                        override val values = (0..9).asSequence()
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun combine(a: Int, b: Int): String = "${'$'}a,${'$'}b"
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "combine",
            parameters = listOf(
                ParameterInfo("a", "Int", "TenProvider"),
                ParameterInfo("b", "Int", "TenProvider")
            )
        )

        // Should generate exactly 10 × 10 = 100 combinations
        assertEquals(100, results.size)
        assertEquals("combine[0, 0]", results[0].fullDisplayName)
        assertEquals("0,0", results[0].result)
        assertEquals("combine[9, 9]", results[99].fullDisplayName)
        assertEquals("9,9", results[99].result)
    }

    @Test
    fun exceeds100Limit() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class TenProvider : PreviewParameterProvider<Int> {
                        override val values = (0..9).asSequence()
                    }

                    class ElevenProvider : PreviewParameterProvider<Int> {
                        override val values = (0..10).asSequence()
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun combine(a: Int, b: Int): String = "${'$'}a,${'$'}b"
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "combine",
            parameters = listOf(
                ParameterInfo("a", "Int", "TenProvider"),
                ParameterInfo("b", "Int", "ElevenProvider")
            )
        )

        // Should return error: a (10) × b (11) = 110 > 100
        assertEquals(1, results.size)
        assertNull(results[0].result)
        assertTrue(results[0].error!!.contains("Too many parameter combinations"))
        assertTrue(results[0].error!!.contains("a (10) × b (11) = 110"))
        assertTrue(results[0].error!!.contains("limit: 100"))
    }

    @Test
    fun softWarningAt20() {
        val originalErr = System.err
        val errCapture = java.io.ByteArrayOutputStream()
        try {
            System.setErr(java.io.PrintStream(errCapture))

            val results = compileKotlinAndInvokeList(
                mapOf(
                    "Providers.kt" to """
                        import preview.annotations.PreviewParameterProvider

                        class FiveProvider : PreviewParameterProvider<Int> {
                            override val values = (0..4).asSequence()
                        }
                    """.trimIndent(),
                    "Preview.kt" to """
                        fun combine(a: Int, b: Int): String = "${'$'}a,${'$'}b"
                    """.trimIndent()
                ),
                className = "PreviewKt",
                methodName = "combine",
                parameters = listOf(
                    ParameterInfo("a", "Int", "FiveProvider"),
                    ParameterInfo("b", "Int", "FiveProvider")
                )
            )

            // Should generate 5 × 5 = 25 combinations with warning
            assertEquals(25, results.size)

            val errOutput = errCapture.toString()
            assertTrue(errOutput.contains("Warning"))
            assertTrue(errOutput.contains("5 × 5"))
            assertTrue(errOutput.contains("25 previews"))
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun allCustomDisplayNames() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class UserProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("Alice", "Bob")

                        override fun getDisplayName(index: Int): String? {
                            return listOf("User1", "User2").getOrNull(index)
                        }
                    }

                    class ThemeProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("Light", "Dark")

                        override fun getDisplayName(index: Int): String? {
                            return listOf("Day", "Night").getOrNull(index)
                        }
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun textCard(user: String, theme: String): String {
                        return "${'$'}user in ${'$'}theme"
                    }
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "textCard",
            parameters = listOf(
                ParameterInfo("user", "String", "UserProvider"),
                ParameterInfo("theme", "String", "ThemeProvider")
            )
        )

        assertEquals(4, results.size)
        assertEquals("textCard[User1, Day]", results[0].fullDisplayName)
        assertEquals("textCard[User1, Night]", results[1].fullDisplayName)
        assertEquals("textCard[User2, Day]", results[2].fullDisplayName)
        assertEquals("textCard[User2, Night]", results[3].fullDisplayName)
    }

    @Test
    fun mixedCustomAndIndexNames() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class NamedProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("A", "B")

                        override fun getDisplayName(index: Int): String? {
                            return listOf("First", "Second").getOrNull(index)
                        }
                    }

                    class IndexedProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("X", "Y")
                        // No getDisplayName - uses indices
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun combine(a: String, b: String): String = "${'$'}a${'$'}b"
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "combine",
            parameters = listOf(
                ParameterInfo("a", "String", "NamedProvider"),
                ParameterInfo("b", "String", "IndexedProvider")
            )
        )

        assertEquals(4, results.size)
        assertEquals("combine[First, 0]", results[0].fullDisplayName)
        assertEquals("combine[First, 1]", results[1].fullDisplayName)
        assertEquals("combine[Second, 0]", results[2].fullDisplayName)
        assertEquals("combine[Second, 1]", results[3].fullDisplayName)
    }

    @Test
    fun firstProviderFailsToInstantiate() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class BadProvider : PreviewParameterProvider<String> {
                        init {
                            throw RuntimeException("Provider initialization failed")
                        }

                        override val values = sequenceOf("value")
                    }

                    class GoodProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("value")
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun combine(a: String, b: String): String = "${'$'}a${'$'}b"
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "combine",
            parameters = listOf(
                ParameterInfo("a", "String", "BadProvider"),
                ParameterInfo("b", "String", "GoodProvider")
            )
        )

        assertEquals(1, results.size)
        assertNull(results[0].result)
        assertTrue(results[0].error!!.contains("Failed to instantiate provider"))
        assertTrue(results[0].error!!.contains("BadProvider"))
    }

    @Test
    fun secondProviderReturnsEmpty() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class GoodProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("value")
                    }

                    class EmptyProvider : PreviewParameterProvider<String> {
                        override val values = emptySequence<String>()
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun combine(a: String, b: String): String = "${'$'}a${'$'}b"
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "combine",
            parameters = listOf(
                ParameterInfo("a", "String", "GoodProvider"),
                ParameterInfo("b", "String", "EmptyProvider")
            )
        )

        assertEquals(1, results.size)
        assertNull(results[0].result)
        assertTrue(results[0].error!!.contains("returned no values"))
        assertTrue(results[0].error!!.contains("EmptyProvider"))
    }

    @Test
    fun individualInvocationFailure() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class NumberProvider : PreviewParameterProvider<Int> {
                        override val values = sequenceOf(0, 1, 2)
                    }

                    class BoolProvider : PreviewParameterProvider<Boolean> {
                        override val values = sequenceOf(true, false)
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun divide(a: Int, b: Boolean): String {
                        if (a == 1 && b) {
                            throw RuntimeException("Intentional error")
                        }
                        return "${'$'}a/${'$'}b"
                    }
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "divide",
            parameters = listOf(
                ParameterInfo("a", "Int", "NumberProvider"),
                ParameterInfo("b", "Boolean", "BoolProvider")
            )
        )

        // Should generate 3 × 2 = 6 combinations
        assertEquals(6, results.size)

        // Combination [1, true] should fail
        assertEquals("divide[0, 0]", results[0].fullDisplayName)
        assertEquals("0/true", results[0].result)
        assertNull(results[0].error)

        assertEquals("divide[0, 1]", results[1].fullDisplayName)
        assertEquals("0/false", results[1].result)
        assertNull(results[1].error)

        assertEquals("divide[1, 0]", results[2].fullDisplayName)
        assertNull(results[2].result)
        assertTrue(results[2].error!!.contains("Intentional error"))

        assertEquals("divide[1, 1]", results[3].fullDisplayName)
        assertEquals("1/false", results[3].result)
        assertNull(results[3].error)

        assertEquals("divide[2, 0]", results[4].fullDisplayName)
        assertEquals("2/true", results[4].result)
        assertNull(results[4].error)

        assertEquals("divide[2, 1]", results[5].fullDisplayName)
        assertEquals("2/false", results[5].result)
        assertNull(results[5].error)
    }

    @Test
    fun backwardCompatibilitySingleParameter() {
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class SingleProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf("Alpha", "Beta")

                        override fun getDisplayName(index: Int): String? {
                            return listOf("First", "Second").getOrNull(index)
                        }
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun singleParam(value: String): String = "Result: ${'$'}value"
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "singleParam",
            parameters = listOf(
                ParameterInfo("value", "String", "SingleProvider")
            )
        )

        // Should work exactly as in Phase 2: single bracket format
        assertEquals(2, results.size)
        assertEquals("singleParam[First]", results[0].fullDisplayName)
        assertEquals("Result: Alpha", results[0].result)
        assertEquals("singleParam[Second]", results[1].fullDisplayName)
        assertEquals("Result: Beta", results[1].result)
    }

    @Test
    fun manyParametersRecursionDepth() {
        // Test with 6 parameters to verify recursion depth handling
        // 2^6 = 64 combinations (under 100 limit)
        val results = compileKotlinAndInvokeList(
            mapOf(
                "Providers.kt" to """
                    import preview.annotations.PreviewParameterProvider

                    class BinaryProvider : PreviewParameterProvider<Int> {
                        override val values = sequenceOf(0, 1)
                    }
                """.trimIndent(),
                "Preview.kt" to """
                    fun combine(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int): String {
                        return "${'$'}a${'$'}b${'$'}c${'$'}d${'$'}e${'$'}f"
                    }
                """.trimIndent()
            ),
            className = "PreviewKt",
            methodName = "combine",
            parameters = listOf(
                ParameterInfo("a", "Int", "BinaryProvider"),
                ParameterInfo("b", "Int", "BinaryProvider"),
                ParameterInfo("c", "Int", "BinaryProvider"),
                ParameterInfo("d", "Int", "BinaryProvider"),
                ParameterInfo("e", "Int", "BinaryProvider"),
                ParameterInfo("f", "Int", "BinaryProvider")
            )
        )

        // Should generate 2^6 = 64 combinations
        assertEquals(64, results.size)

        // Verify first and last combinations
        assertEquals("combine[0, 0, 0, 0, 0, 0]", results[0].fullDisplayName)
        assertEquals("000000", results[0].result)
        assertEquals("combine[1, 1, 1, 1, 1, 1]", results[63].fullDisplayName)
        assertEquals("111111", results[63].result)

        // Verify all results are successful (no stack overflow)
        assertTrue(results.all { it.error == null })
    }
}
