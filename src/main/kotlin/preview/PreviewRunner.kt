package preview

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import preview.annotations.PreviewParameterProvider

/**
 * Result of invoking a preview function.
 *
 * For parameterized previews, one PreviewResult is generated per parameter value.
 * For zero-parameter previews, a single PreviewResult is generated.
 */
data class PreviewResult(
    val functionName: String,
    val displayName: String?,
    val result: String? = null,
    val error: String? = null
) {
    /** Full display name: "functionName[index]" for parameterized, "functionName" for simple */
    val fullDisplayName: String
        get() = if (displayName != null) "$functionName$displayName" else functionName
}

object PreviewRunner {
    fun invoke(classpathJars: List<String>, fn: FunctionInfo): List<PreviewResult> {
        val urls = classpathJars.map { File(it).toURI().toURL() }.toTypedArray()
        return invoke(URLClassLoader(urls, ClassLoader.getPlatformClassLoader()), fn)
    }

    fun invoke(loader: URLClassLoader, fn: FunctionInfo): List<PreviewResult> {
        return loader.use { invokeWithLoader(it, fn) }
    }

    private fun invokeWithLoader(loader: URLClassLoader, fn: FunctionInfo): List<PreviewResult> {
        // Legacy path: zero-parameter functions
        if (fn.parameters.isEmpty()) {
            return try {
                val result = invokeSingle(loader, fn, emptyList())
                listOf(PreviewResult(fn.name, null, result = result))
            } catch (e: Exception) {
                val error = if (e is InvocationTargetException) {
                    (e.cause ?: e).message
                } else {
                    e.message
                }
                listOf(PreviewResult(fn.name, null, error = error))
            }
        }

        // Parameterized path: instantiate provider and iterate values
        val paramInfo = fn.parameters[0]  // Phase 1: single parameter only

        val provider = try {
            instantiateProvider(loader, paramInfo)
        } catch (e: Exception) {
            return listOf(
                PreviewResult(
                    fn.name,
                    null,
                    error = "Failed to instantiate provider ${paramInfo.providerClass}: ${e.message}"
                )
            )
        }

        // Materialize values with hard limit of 100
        val values = provider.values.take(100).toList()

        // Warn if >20 results
        if (values.size > 20) {
            System.err.println(
                "[Preview] Warning: ${fn.name} generates ${values.size} previews (>20). " +
                "Consider reducing provider size."
            )
        }

        // Empty provider check
        if (values.isEmpty()) {
            return listOf(
                PreviewResult(
                    fn.name,
                    null,
                    error = "Provider ${paramInfo.providerClass} returned no values"
                )
            )
        }

        // Invoke once per value
        val results = mutableListOf<PreviewResult>()
        values.forEachIndexed { index, value ->
            try {
                val result = invokeSingle(loader, fn, listOf(value))
                results.add(PreviewResult(fn.name, "[$index]", result = result))
            } catch (e: Exception) {
                val error = if (e is InvocationTargetException) {
                    (e.cause ?: e).message
                } else {
                    e.message
                }
                results.add(PreviewResult(fn.name, "[$index]", error = error))
            }
        }

        return results
    }

    private fun invokeSingle(
        loader: URLClassLoader,
        fn: FunctionInfo,
        args: List<Any?>
    ): String? {
        val clazz = loader.loadClass(fn.jvmClassName)

        // Resolve parameter types for method lookup
        // For parameterized functions, we need to find the method by examining all methods
        // because the parameter types from the provider may not exactly match the method signature
        // (e.g., boxed vs primitive types, different classloaders).
        val method = if (args.isEmpty()) {
            clazz.getMethod(fn.name)
        } else {
            // Find method by name and parameter count, then try to invoke it
            val candidates = clazz.methods.filter { it.name == fn.name && it.parameterCount == args.size }
            if (candidates.isEmpty()) {
                throw NoSuchMethodException("No method ${fn.name} with ${args.size} parameters found in ${fn.jvmClassName}")
            }
            // Try each candidate until one works
            var lastException: Exception? = null
            for (candidate in candidates) {
                try {
                    // Test if this method works with our args
                    val receiver = resolveReceiver(clazz, fn.containerKind)
                    val result = candidate.invoke(receiver, *args.toTypedArray())
                    // Success! Return the result immediately
                    return result?.toString()
                } catch (e: IllegalArgumentException) {
                    // Wrong parameter types, try next candidate
                    lastException = e
                    continue
                } catch (e: InvocationTargetException) {
                    // Method was invoked but threw an exception - this is the right method
                    throw e.cause ?: e
                }
            }
            // None of the candidates worked
            throw lastException ?: NoSuchMethodException("Could not invoke ${fn.name} with provided arguments")
        }

        val receiver = resolveReceiver(clazz, fn.containerKind)

        return try {
            val result = method.invoke(receiver, *args.toTypedArray())
            // CRITICAL: Call toString() BEFORE classloader closes
            result?.toString()
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun instantiateProvider(
        loader: URLClassLoader,
        paramInfo: ParameterInfo
    ): ProviderInstance {
        val providerClass = loader.loadClass(paramInfo.providerClass)

        // Try object singleton first (Kotlin object with INSTANCE field)
        val instance = try {
            val instanceField = providerClass.getDeclaredField("INSTANCE")
            instanceField.get(null)
        } catch (_: NoSuchFieldException) {
            // Not an object, try class with no-arg constructor
            val constructor = providerClass.getDeclaredConstructor()
            constructor.newInstance()
        }

        // Get the 'values' property using reflection (can't cast due to classloader isolation)
        val valuesGetter = try {
            // Try Kotlin property getter first
            providerClass.getMethod("getValues")
        } catch (_: NoSuchMethodException) {
            // Try Java-style getter
            providerClass.getMethod("values")
        }

        val valuesObject = valuesGetter.invoke(instance)

        // Convert to Sequence via reflection
        return ProviderInstance(valuesObject)
    }

    /**
     * Wrapper for provider instance that accesses values via reflection to avoid classloader issues.
     */
    private class ProviderInstance(private val valuesObject: Any) {
        val values: Sequence<Any?> get() {
            // The valuesObject is a Kotlin Sequence from a different classloader
            // We need to iterate it using reflection
            val sequenceClass = valuesObject.javaClass
            val iteratorMethod = sequenceClass.getMethod("iterator")
            iteratorMethod.isAccessible = true
            val iterator = iteratorMethod.invoke(valuesObject) as Iterator<*>

            return iterator.asSequence()
        }
    }

    private fun resolveReceiver(clazz: Class<*>, kind: ContainerKind): Any? =
        when (kind) {
            ContainerKind.TOP_LEVEL -> null
            ContainerKind.OBJECT -> clazz.getDeclaredField("INSTANCE").get(null)
            ContainerKind.CLASS -> clazz.getDeclaredConstructor().newInstance()
        }
}
