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
    val error: String? = null,
    val errorType: PreviewError? = null,  // Structured error information
    val timingMs: Long? = null  // Execution time in milliseconds (null if profiling disabled)
) {
    /** Full display name: "functionName[index]" for parameterized, "functionName" for simple */
    val fullDisplayName: String
        get() = if (displayName != null) "$functionName$displayName" else functionName
}

/**
 * Timing information for performance profiling.
 */
data class TimingInfo(
    val startNanos: Long,
    val endNanos: Long
) {
    val durationMs: Long get() = (endNanos - startNanos) / 1_000_000
}

object PreviewRunner {
    /**
     * Internal: Materialized provider with values and display name access.
     */
    private data class MaterializedProvider(
        val paramInfo: ParameterInfo,
        val values: List<Any?>,
        val providerInstance: ProviderInstance
    )

    /**
     * Internal: A combination of parameter values with their original indices.
     */
    private data class IndexedCombination(
        val values: List<Any?>,
        val indices: List<Int>
    )

    /**
     * Whether to enable profiling (timing) for preview execution.
     * Set via system property: -Dpreview.profile=true
     */
    private val profileEnabled: Boolean
        get() = System.getProperty("preview.profile")?.toBoolean() == true

    fun invoke(classpathJars: List<String>, fn: FunctionInfo): List<PreviewResult> {
        val urls = classpathJars.map { File(it).toURI().toURL() }.toTypedArray()
        return invoke(URLClassLoader(urls, ClassLoader.getPlatformClassLoader()), fn)
    }

    fun invoke(loader: URLClassLoader, fn: FunctionInfo): List<PreviewResult> {
        return loader.use { invokeWithLoader(it, fn) }
    }

    /**
     * Measure execution time of a block.
     */
    private inline fun <T> measureTime(block: () -> T): Pair<T, TimingInfo> {
        val start = System.nanoTime()
        val result = block()
        val end = System.nanoTime()
        return result to TimingInfo(start, end)
    }

    private fun invokeWithLoader(loader: URLClassLoader, fn: FunctionInfo): List<PreviewResult> {
        // Legacy path: zero-parameter functions
        if (fn.parameters.isEmpty()) {
            return try {
                val (result, timing) = if (profileEnabled) {
                    measureTime { invokeSingle(loader, fn, emptyList()) }
                } else {
                    invokeSingle(loader, fn, emptyList()) to TimingInfo(0, 0)
                }

                // Log slow previews if profiling is enabled
                if (profileEnabled && timing.durationMs > 100) {
                    System.err.println("[profile] Slow preview: ${fn.name} took ${timing.durationMs}ms")
                }

                listOf(PreviewResult(
                    fn.name,
                    null,
                    result = result,
                    timingMs = if (profileEnabled) timing.durationMs else null
                ))
            } catch (e: Exception) {
                val causeMsg = if (e is InvocationTargetException) {
                    (e.cause ?: e).message ?: (e.cause ?: e).javaClass.simpleName
                } else {
                    e.message ?: e.javaClass.simpleName
                }
                val errorType = PreviewError.InvocationFailed(
                    displayName = fn.name,
                    cause = causeMsg
                )
                listOf(PreviewResult(fn.name, null, error = errorType.message, errorType = errorType))
            }
        }

        // Phase 3: Multi-parameter support with cartesian product

        // Step 1: Instantiate all providers
        val materializedProviders = mutableListOf<MaterializedProvider>()
        for (paramInfo in fn.parameters) {
            val provider = try {
                instantiateProvider(loader, paramInfo)
            } catch (e: Exception) {
                val errorType = PreviewError.ProviderInstantiationFailed(
                    providerClass = paramInfo.providerClass,
                    parameterName = paramInfo.name,
                    cause = e.message ?: e.javaClass.simpleName
                )
                return listOf(
                    PreviewResult(
                        fn.name,
                        null,
                        error = errorType.message,
                        errorType = errorType
                    )
                )
            }

            // Materialize values with per-provider limit (or default of 100)
            val effectiveLimit = if (paramInfo.limit > 0) paramInfo.limit else 100
            val values = provider.values.take(effectiveLimit).toList()

            // Empty provider check
            if (values.isEmpty()) {
                val errorType = PreviewError.ProviderEmpty(
                    providerClass = paramInfo.providerClass,
                    parameterName = paramInfo.name
                )
                return listOf(
                    PreviewResult(
                        fn.name,
                        null,
                        error = errorType.message,
                        errorType = errorType
                    )
                )
            }

            materializedProviders.add(MaterializedProvider(paramInfo, values, provider))
        }

        // Step 2: Calculate total combinations
        val sizes = materializedProviders.map { it.values.size }
        val totalCombinations = sizes.fold(1L) { acc, size -> acc * size }

        // Step 3: Check limits
        if (totalCombinations > 100) {
            // Include parameter names and limits in error message for clarity
            val calculation = materializedProviders.zip(sizes)
                .joinToString(" × ") { (provider, size) ->
                    val limitInfo = if (provider.paramInfo.limit > 0) ", limit: ${provider.paramInfo.limit}" else ""
                    "${provider.paramInfo.name} ($size$limitInfo)"
                }
            val errorType = PreviewError.TooManyCombinations(
                totalCombinations = totalCombinations,
                parameterInfo = calculation,
                limit = 100
            )
            return listOf(
                PreviewResult(
                    fn.name,
                    null,
                    error = errorType.message,
                    errorType = errorType
                )
            )
        }

        // Soft warning for >20 combinations
        if (totalCombinations > 20) {
            val calculation = sizes.joinToString(" × ")
            System.err.println(
                "[Preview] Warning: ${fn.name} generates $totalCombinations previews ($calculation, limit: 100). " +
                "Consider reducing provider sizes."
            )
        }

        // Step 4: Generate cartesian product
        val valueSequences = materializedProviders.map { it.values.asSequence() }
        val combinations = cartesianProductWithIndices(valueSequences)

        // Step 5: Invoke once per combination
        // Error isolation strategy: If one combination fails, we catch the exception,
        // record it as an error result, and continue with the remaining combinations.
        // This ensures that a single bad input value doesn't prevent all other previews
        // from being generated.
        val results = mutableListOf<PreviewResult>()
        combinations.forEach { combo ->
            // Build multi-parameter display name
            val displayName = buildMultiParameterDisplayName(materializedProviders, combo.indices)
            val fullName = "${fn.name}$displayName"

            try {
                val (result, timing) = if (profileEnabled) {
                    measureTime { invokeSingle(loader, fn, combo.values) }
                } else {
                    invokeSingle(loader, fn, combo.values) to TimingInfo(0, 0)
                }

                // Log slow previews if profiling is enabled
                if (profileEnabled && timing.durationMs > 100) {
                    System.err.println("[profile] Slow preview: $fullName took ${timing.durationMs}ms")
                }

                results.add(PreviewResult(
                    fn.name,
                    displayName,
                    result = result,
                    timingMs = if (profileEnabled) timing.durationMs else null
                ))
            } catch (e: Exception) {
                val causeMsg = if (e is InvocationTargetException) {
                    (e.cause ?: e).message ?: (e.cause ?: e).javaClass.simpleName
                } else {
                    e.message ?: e.javaClass.simpleName
                }
                val errorType = PreviewError.InvocationFailed(
                    displayName = fullName,
                    cause = causeMsg
                )
                results.add(PreviewResult(fn.name, displayName, error = errorType.message, errorType = errorType))
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
        val receiver = resolveReceiver(clazz, fn.containerKind)

        // For parameterized functions, we need to find the method by trying candidates
        // because parameter types from the provider may not exactly match the method signature
        // (e.g., boxed vs primitive types, different classloaders).
        if (args.isEmpty()) {
            // Zero-parameter case: direct method lookup
            val method = clazz.getMethod(fn.name)
            return try {
                val result = method.invoke(receiver)
                // CRITICAL: Call toString() BEFORE classloader closes
                result?.toString()
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }

        // Parameterized case: find matching method by attempting invocation
        return findAndInvokeMatchingMethod(clazz, fn, receiver, args)
    }

    /**
     * Find and invoke a method that accepts the given arguments.
     *
     * This is necessary because parameter types from providers may not exactly match
     * the method signature (e.g., boxed vs primitive types, different classloaders).
     * We try each candidate method until one successfully accepts our arguments.
     */
    private fun findAndInvokeMatchingMethod(
        clazz: Class<*>,
        fn: FunctionInfo,
        receiver: Any?,
        args: List<Any?>
    ): String? {
        val candidates = clazz.methods.filter { it.name == fn.name && it.parameterCount == args.size }
        if (candidates.isEmpty()) {
            val errorType = PreviewError.MethodNotFound(
                functionName = fn.name,
                expectedParamCount = args.size,
                parameterNames = fn.parameters.map { it.name }
            )
            throw NoSuchMethodException(errorType.message)
        }

        var lastException: Exception? = null
        for (candidate in candidates) {
            try {
                val result = candidate.invoke(receiver, *args.toTypedArray())
                // Success! Return the result immediately
                // CRITICAL: Call toString() BEFORE classloader closes
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

        // All candidates failed - build detailed error with type information
        val errorType = PreviewError.TypeMismatch(
            functionName = fn.name,
            expectedTypes = candidates.first().parameterTypes.map { it.simpleName },
            providedTypes = args.map { it?.javaClass?.simpleName ?: "null" },
            parameterNames = fn.parameters.map { it.name }
        )
        throw IllegalArgumentException(errorType.message)
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

        // Try to get getDisplayName method
        val getDisplayNameMethod = try {
            // Look for method with signature: String? getDisplayName(int)
            // Use javaPrimitiveType to match JVM signature
            providerClass.getMethod("getDisplayName", Int::class.javaPrimitiveType)
        } catch (_: NoSuchMethodException) {
            null  // Method doesn't exist - that's fine, it's optional
        }

        // Convert to Sequence via reflection
        return ProviderInstance(valuesObject, instance, getDisplayNameMethod)
    }

    /**
     * Build display name for a parameterized preview result.
     *
     * Tries provider's getDisplayName() first, falls back to "[index]" format.
     *
     * @param provider The provider instance
     * @param index Zero-based index of the value
     * @return Display name string (never null)
     */
    private fun buildDisplayName(provider: ProviderInstance, index: Int): String {
        // Try custom display name from provider
        val customName = provider.getDisplayName(index)

        // Use custom name if non-null and non-blank, otherwise use index
        return if (customName != null && customName.isNotBlank()) {
            "[$customName]"  // Wrap in brackets for consistency
        } else {
            "[$index]"  // Fall back to index
        }
    }

    /**
     * Generate cartesian product of multiple sequences, tracking original indices.
     *
     * For input sequences [[a, b], [x, y]], generates:
     * - IndexedCombination(values=[a, x], indices=[0, 0])
     * - IndexedCombination(values=[a, y], indices=[0, 1])
     * - IndexedCombination(values=[b, x], indices=[1, 0])
     * - IndexedCombination(values=[b, y], indices=[1, 1])
     *
     * Implementation uses recursion with depth equal to the number of parameters.
     * With the 100-combination limit, maximum practical parameter count is 6-7,
     * making stack overflow impossible in practice.
     *
     * @param sequences List of sequences to combine
     * @return Sequence of all combinations with their indices
     */
    private fun cartesianProductWithIndices(
        sequences: List<Sequence<Any?>>
    ): Sequence<IndexedCombination> {
        if (sequences.isEmpty()) {
            return sequenceOf(IndexedCombination(emptyList(), emptyList()))
        }

        if (sequences.size == 1) {
            return sequences[0].mapIndexed { index, value ->
                IndexedCombination(listOf(value), listOf(index))
            }
        }

        // Recursive case: cartesian product of first sequence with rest
        val first = sequences[0]
        val rest = cartesianProductWithIndices(sequences.drop(1))

        return sequence {
            first.forEachIndexed { index, value ->
                rest.forEach { restCombo ->
                    yield(
                        IndexedCombination(
                            values = listOf(value) + restCombo.values,
                            indices = listOf(index) + restCombo.indices
                        )
                    )
                }
            }
        }
    }

    /**
     * Build multi-parameter display name for a preview result.
     *
     * Format:
     * - Single param: "[name]" or "[index]"
     * - Multiple params: "[name1, name2, ...]" or "[index1, index2, ...]"
     *
     * @param providers List of materialized providers
     * @param indices Index of each value in its provider
     * @return Display name string (never null)
     */
    private fun buildMultiParameterDisplayName(
        providers: List<MaterializedProvider>,
        indices: List<Int>
    ): String {
        require(providers.size == indices.size) {
            "Provider count (${providers.size}) must match index count (${indices.size})"
        }

        // Single parameter: backward compatible format
        if (providers.size == 1) {
            return buildDisplayName(providers[0].providerInstance, indices[0])
        }

        // Multiple parameters: "[name1, name2, ...]"
        val names = providers.zip(indices).map { (provider, index) ->
            val customName = provider.providerInstance.getDisplayName(index)
            if (customName != null && customName.isNotBlank()) {
                customName
            } else {
                index.toString()
            }
        }

        return "[${names.joinToString(", ")}]"
    }

    /**
     * Wrapper for provider instance that accesses values via reflection to avoid classloader issues.
     */
    private class ProviderInstance(
        private val valuesObject: Any,
        private val providerInstance: Any,
        private val getDisplayNameMethod: java.lang.reflect.Method?
    ) {
        val values: Sequence<Any?> get() {
            // The valuesObject is a Kotlin Sequence from a different classloader
            // We need to iterate it using reflection
            val sequenceClass = valuesObject.javaClass
            val iteratorMethod = sequenceClass.getMethod("iterator")
            iteratorMethod.isAccessible = true
            val iterator = iteratorMethod.invoke(valuesObject) as Iterator<*>

            return iterator.asSequence()
        }

        /**
         * Get custom display name for value at given index.
         * Returns null if method not available or returns null.
         */
        fun getDisplayName(index: Int): String? {
            if (getDisplayNameMethod == null) return null

            return try {
                val result = getDisplayNameMethod.invoke(providerInstance, index)
                result as? String
            } catch (e: Exception) {
                // Log warning but don't fail - fall back to index
                System.err.println(
                    "[Preview] Warning: ${providerInstance.javaClass.name}.getDisplayName($index) threw exception: ${e.message}"
                )
                null
            }
        }
    }

    private fun resolveReceiver(clazz: Class<*>, kind: ContainerKind): Any? =
        when (kind) {
            ContainerKind.TOP_LEVEL -> null
            ContainerKind.OBJECT -> clazz.getDeclaredField("INSTANCE").get(null)
            ContainerKind.CLASS -> clazz.getDeclaredConstructor().newInstance()
        }
}
