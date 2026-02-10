# PreviewProvider Implementation Proposal

## Executive Summary

This document proposes adding `@PreviewParameter` annotation support to enable preview functions to accept parameters provided by `PreviewParameterProvider` implementations, similar to Jetpack Compose previews. This would allow preview functions to depend on complex fixtures and generate multiple previews from a single function.

---

## Background: How Jetpack Compose Does It

### Core API

Compose uses two key components:

**1. `@PreviewParameter` Annotation**
```kotlin
@PreviewParameter(
    provider: KClass<out PreviewParameterProvider<T>>,
    limit: Int = Int.MAX_VALUE
)
```

Applied to function parameters to inject sample data from a provider.

**2. `PreviewParameterProvider<T>` Interface**
```kotlin
interface PreviewParameterProvider<T> {
    val values: Sequence<T>
    fun getDisplayName(index: Int): String? = null
}
```

Implementations provide sequences of test data.

### Usage Example

```kotlin
// Define the provider
class UserPreviewParameterProvider : PreviewParameterProvider<User> {
    override val values = sequenceOf(
        User("Alice", age = 25),
        User("Bob", age = 30),
        User("Charlie", age = 35)
    )

    override fun getDisplayName(index: Int): String? {
        return listOf("Young", "Middle", "Senior").getOrNull(index)
    }
}

// Use in preview
@Preview
@Composable
fun UserProfilePreview(
    @PreviewParameter(UserPreviewParameterProvider::class, limit = 2) user: User
) {
    UserProfile(user)
}
```

This generates **2 previews** (limit = 2), one with Alice and one with Bob, labeled "Young" and "Middle" respectively.

### Key Benefits

1. **Multiple States**: Generate previews for different data states without duplicating functions
2. **Complex Fixtures**: Handle elaborate test data setups in reusable provider classes
3. **Customizable Display**: Provide meaningful names for each preview variant
4. **Performance Control**: Limit preview count to avoid overwhelming the UI

---

## Current System Constraints

### Architecture Overview

```
Source File → SourceAnalyzer → FunctionInfo → PreviewRunner → Result String
              (PSI parsing)    (metadata)     (reflection)
```

### Key Limitations

1. **Zero-Parameter Requirement** (`SourceAnalyzer.kt:119`)
   ```kotlin
   fn.valueParameters.isEmpty()  // Currently enforced
   ```

2. **Simple Invocation** (`PreviewRunner.kt:16`)
   ```kotlin
   val method = clazz.getMethod(fn.name)  // No parameter types
   method.invoke(receiver)                // No arguments
   ```

3. **Single Result Per Function**
   - Current output: `{"functions": [{name, result}]}`
   - Need: Multiple results per function for different parameter values

4. **Metadata Storage**
   - `FunctionInfo` tracks: name, package, JVM class, container kind
   - Need: Parameter info, provider types, limits

---

## Proposed Implementation Approaches

### Approach 1: Full Compose-Style Implementation (Recommended)

Mimics Jetpack Compose's API with maximum flexibility.

#### New Annotations

```kotlin
// User-defined in their codebase
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PreviewParameter(
    val provider: KClass<out PreviewParameterProvider<*>>,
    val limit: Int = Int.MAX_VALUE
)

interface PreviewParameterProvider<T> {
    val values: Sequence<T>
    fun getDisplayName(index: Int): String? = null
}
```

#### Changes Required

**1. Extend `FunctionInfo`**
```kotlin
data class ParameterInfo(
    val name: String,
    val type: String,              // e.g., "examples.User"
    val providerClass: String,     // e.g., "examples.UserProvider"
    val limit: Int
)

data class FunctionInfo(
    val name: String,
    val packageName: String,
    val jvmClassName: String,
    val containerKind: ContainerKind = ContainerKind.TOP_LEVEL,
    val parameters: List<ParameterInfo> = emptyList()  // NEW
)
```

**2. Update `SourceAnalyzer.isPreviewCandidate()`**
```kotlin
private fun isPreviewCandidate(fn: KtNamedFunction): Boolean {
    return fn.annotationEntries.any { it.shortName?.asString() == "Preview" } &&
        !fn.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
        !fn.hasModifier(KtTokens.SUSPEND_KEYWORD) &&
        !fn.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
        // REMOVE: fn.valueParameters.isEmpty() &&
        hasValidPreviewParameters(fn) &&  // NEW
        fn.receiverTypeReference == null &&
        fn.typeParameters.isEmpty()
}

private fun hasValidPreviewParameters(fn: KtNamedFunction): Boolean {
    // Allow either:
    // - No parameters, OR
    // - All parameters have @PreviewParameter annotation
    return fn.valueParameters.isEmpty() ||
           fn.valueParameters.all { hasPreviewParameterAnnotation(it) }
}

private fun extractParameterInfo(param: KtParameter): ParameterInfo? {
    val annotation = param.annotationEntries
        .firstOrNull { it.shortName?.asString() == "PreviewParameter" }
        ?: return null

    // Parse provider::class and limit from annotation arguments
    val providerClass = extractProviderClass(annotation)
    val limit = extractLimit(annotation) ?: Int.MAX_VALUE
    val typeName = param.typeReference?.text ?: return null

    return ParameterInfo(
        name = param.name ?: return null,
        type = typeName,
        providerClass = providerClass,
        limit = limit
    )
}
```

**3. Update `PreviewRunner` for Multi-Value Invocation**
```kotlin
data class PreviewResult(
    val functionName: String,
    val displayName: String?,  // e.g., "UserProfilePreview [Alice]"
    val result: String?
)

object PreviewRunner {
    fun invoke(loader: URLClassLoader, fn: FunctionInfo): List<PreviewResult> {
        if (fn.parameters.isEmpty()) {
            // Legacy path: single invocation
            val result = invokeSingle(loader, fn, emptyList())
            return listOf(PreviewResult(fn.name, null, result))
        }

        // New path: generate cartesian product of all parameter values
        val parameterProviders = fn.parameters.map { param ->
            instantiateProvider(loader, param.providerClass, param.limit)
        }

        return generateCombinations(parameterProviders).mapIndexed { index, args ->
            val displayName = buildDisplayName(fn, parameterProviders, index)
            val result = invokeSingle(loader, fn, args)
            PreviewResult(fn.name, displayName, result)
        }
    }

    private fun invokeSingle(
        loader: URLClassLoader,
        fn: FunctionInfo,
        args: List<Any?>
    ): String? {
        val clazz = loader.loadClass(fn.jvmClassName)
        val paramTypes = fn.parameters.map {
            loader.loadClass(it.type)
        }.toTypedArray()
        val method = clazz.getMethod(fn.name, *paramTypes)
        val receiver = resolveReceiver(clazz, fn.containerKind)
        val result = method.invoke(receiver, *args.toTypedArray())
        return result?.toString()
    }

    private fun instantiateProvider(
        loader: URLClassLoader,
        providerClassName: String,
        limit: Int
    ): ProviderInstance {
        val providerClass = loader.loadClass(providerClassName)
        val provider = providerClass.getDeclaredConstructor().newInstance()

        // Get the 'values' property
        val valuesField = providerClass.getMethod("getValues")
        val sequence = valuesField.invoke(provider) as Sequence<*>
        val values = sequence.take(limit).toList()

        // Get the getDisplayName method if it exists
        val getDisplayName = try {
            providerClass.getMethod("getDisplayName", Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            null
        }

        return ProviderInstance(values, getDisplayName, provider)
    }

    private data class ProviderInstance(
        val values: List<Any?>,
        val getDisplayName: Method?,
        val provider: Any
    )

    private fun buildDisplayName(
        fn: FunctionInfo,
        providers: List<ProviderInstance>,
        index: Int
    ): String {
        // For single parameter: use provider's displayName or index
        if (providers.size == 1) {
            val provider = providers[0]
            val customName = provider.getDisplayName?.invoke(provider.provider, index) as? String
            return if (customName != null) {
                "${fn.name} [$customName]"
            } else {
                "${fn.name} [${index + 1}]"
            }
        }

        // For multiple parameters: show parameter names + values
        val coords = indexToCoordinates(index, providers.map { it.values.size })
        val parts = coords.mapIndexed { paramIdx, valueIdx ->
            val provider = providers[paramIdx]
            provider.getDisplayName?.invoke(provider.provider, valueIdx) as? String
                ?: "${fn.parameters[paramIdx].name}=$valueIdx"
        }
        return "${fn.name} [${parts.joinToString(", ")}]"
    }
}
```

**4. Update JSON Output Format**
```json
{
  "functions": [
    {
      "name": "UserProfilePreview",
      "results": [
        {"displayName": "UserProfilePreview [Young]", "result": "..."},
        {"displayName": "UserProfilePreview [Middle]", "result": "..."}
      ]
    }
  ]
}
```

#### Pros
- Full feature parity with Compose
- Maximum flexibility for complex test data
- Custom display names improve debuggability
- Supports multiple parameters (cartesian product)
- Familiar API for Android developers

#### Cons
- Most complex implementation
- Significant changes to `SourceAnalyzer` PSI parsing for annotation arguments
- Need to handle Kotlin reflection for provider instantiation
- Higher risk of runtime errors (provider instantiation, type mismatches)

---

### Approach 2: Simplified Provider (Pragmatic)

Simplifies the API by removing some Compose features.

#### Simplified Annotations

```kotlin
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PreviewParameter(
    val provider: KClass<out PreviewParameterProvider<*>>
    // No 'limit' parameter - always use all values
)

interface PreviewParameterProvider<T> {
    val values: Sequence<T>
    // No getDisplayName() - use indices
}
```

#### Key Simplifications

1. **No Limit**: Always use all values from provider
2. **No Custom Names**: Display as `FunctionName [1]`, `FunctionName [2]`, etc.
3. **Single Parameter Only**: Reject functions with multiple `@PreviewParameter` parameters

#### Validation in `SourceAnalyzer`
```kotlin
private fun hasValidPreviewParameters(fn: KtNamedFunction): Boolean {
    val previewParams = fn.valueParameters.filter { hasPreviewParameterAnnotation(it) }

    // Either no params or exactly one @PreviewParameter
    return previewParams.size <= 1 &&
           (previewParams.isEmpty() || fn.valueParameters.size == 1)
}
```

#### Pros
- Simpler implementation
- Less PSI parsing complexity
- Easier to debug
- Still covers 80% of use cases

#### Cons
- No limit control (could generate many previews)
- No custom display names (harder to identify previews)
- No multi-parameter support

---

### Approach 3: Factory Function Pattern (Minimal)

Avoid provider interface entirely; use simple factory functions.

#### Annotations

```kotlin
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PreviewParameter(
    val factory: String  // Fully-qualified function name: "examples.userSamples"
)

// User defines factory functions
fun userSamples(): List<User> = listOf(
    User("Alice", 25),
    User("Bob", 30)
)

@Preview
fun userPreview(@PreviewParameter(factory = "examples.userSamples") user: User): String {
    return "User: ${user.name}"
}
```

#### Implementation

- `SourceAnalyzer` extracts factory function name from annotation string
- `PreviewRunner` invokes factory function to get `List<T>`
- Iterate through list, invoking preview function with each value

#### Pros
- No interface to implement
- Minimal API surface
- Very simple PSI parsing (just string extraction)
- Factory functions are reusable across tests

#### Cons
- Less structured than interface approach
- No type safety at compile time
- No display name customization
- String-based factory references are fragile

---

## Recommended Implementation Plan

### Phase 1: Foundation
1. Define `PreviewParameterProvider` interface and `@PreviewParameter` annotation
2. Add examples to `examples/` directory
3. Extend `FunctionInfo` with `ParameterInfo`

### Phase 2: Discovery
1. Update `SourceAnalyzer.isPreviewCandidate()` to allow parameters
2. Implement PSI parsing for `@PreviewParameter` annotation arguments
3. Extract provider class references and limits
4. Add validation: all parameters must have `@PreviewParameter`

### Phase 3: Invocation
1. Update `PreviewRunner.invoke()` to return `List<PreviewResult>`
2. Implement provider instantiation via reflection
3. Handle single-parameter case first
4. Iterate through provider values, invoking preview function

### Phase 4: Display Names
1. Implement `getDisplayName()` method invocation
2. Build meaningful display names for JSON output
3. Update JSON format to support multiple results per function

### Phase 5: Multi-Parameter Support
1. Implement cartesian product generation
2. Handle display names for multi-parameter cases
3. Add validation to prevent combinatorial explosion

### Phase 6: Testing & Refinement
1. Add comprehensive test cases
2. Test with complex provider hierarchies
3. Performance testing with large datasets
4. Documentation and examples

---

## Alternative Design Considerations

### Should Providers Be Singletons or Instantiated Per Preview?

**Option A: Singleton** (Compose approach)
```kotlin
val provider = providerClass.getDeclaredConstructor().newInstance()
// Reuse same instance for getDisplayName() calls
```

**Option B: Fresh Instance**
```kotlin
providers.values.mapIndexed { index, value ->
    val freshProvider = providerClass.getDeclaredConstructor().newInstance()
    // New instance each time
}
```

**Recommendation**: Use singleton (Option A) for consistency with Compose and better performance.

### Should We Support Default Values?

Allow preview parameters with defaults to be optional:
```kotlin
@Preview
fun userPreview(
    @PreviewParameter(UserProvider::class) user: User = User.DEFAULT
): String
```

**Recommendation**: No. Require all parameters to be provided. Simplifies implementation and avoids confusion about when defaults are used.

### How to Handle Provider Instantiation Failures?

**Option A: Fail Fast**
- Throw exception if provider constructor fails
- Clear error message

**Option B: Skip Silently**
- Log warning, exclude that preview
- Continue with other previews

**Recommendation**: Fail fast (Option A) for better developer experience.

---

## Migration Path

### Backward Compatibility

Existing zero-parameter preview functions continue to work unchanged:
```kotlin
@Preview
fun simplePreview(): String = "Hello"
```

### Gradual Adoption

Users can adopt providers incrementally:
1. Start with simple zero-parameter previews
2. Add provider for complex fixtures when needed
3. Refactor multiple preview functions into single parameterized function

---

## Open Questions

1. **Should providers support constructor parameters?**
   - Compose requires no-arg constructors
   - Could support dependency injection for complex providers

2. **Should we support multiple @Preview annotations per function?**
   - Compose allows multiple @Preview annotations with different configs
   - Would multiply preview count (params × annotations)

3. **Performance limits?**
   - Max preview count per function?
   - Max total preview count per file?
   - Timeout for slow providers?

4. **Error handling for provider values?**
   - What if `values` sequence throws during iteration?
   - Should we catch and report, or fail entire preview?

---

## References

- [Jetpack Compose Preview Documentation](https://developer.android.com/develop/ui/compose/tooling/previews)
- [PreviewParameter Playground](https://foso.github.io/Jetpack-Compose-Playground/general/preview/previewparameter/)
- [Cleaner Previews in Compose with PreviewParameter](https://www.droidcon.com/2022/03/11/cleaner-previews-in-compose-with-previewparameter/)
- [The Many Approaches to Providing @Preview data](https://dladukedev.com/articles/036_compose_preview_parameter_data/)
- [Tips for working with Preview in Jetpack Compose](https://nimblehq.co/blog/tips-for-working-with-preview-in-jetpack-compose)
