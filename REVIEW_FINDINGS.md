# PreviewProvider Proposal Review Findings

This document summarizes the comprehensive review of the PreviewProvider implementation proposals.

## Overall Assessment

The proposals are **well-researched and thoughtfully structured**, demonstrating solid understanding of both Jetpack Compose's approach and the current codebase architecture. However, there are **significant technical gaps** and the implementation complexity is **underestimated by 2-3x**.

---

## Critical Issues Requiring Resolution

### 1. Annotation Location Undefined

**Problem**: The proposal never specifies WHERE the `PreviewParameterProvider` interface and `@PreviewParameter` annotation should live.

**Options**:
- In the tool's own package (`preview.*`)
- User-defined in their projects
- Separate annotations library/dependency

**Impact**: This affects PSI parsing (qualifying annotation names), runtime reflection (class loading), and user experience (dependency management).

**Recommendation**: Create a separate `preview-annotations` module that users add as a compile-only dependency. Tool finds annotations via qualified name: `preview.PreviewParameter`.

### 2. PSI Parsing Complexity Understated

**Problem**: The proposal mentions "Parse provider::class and limit from annotation arguments" but doesn't detail the complexity.

**Missing Implementation**:
- `KClass` references in annotations are `KtClassLiteralExpression`
- Extracting fully-qualified names requires resolving class references
- PSI lacks semantic analysis - must handle imports, aliases, etc.
- No concrete implementation of `extractProviderClass(annotation)`

**Example Challenge**:
```kotlin
import com.example.UserProvider as UP

@Preview
fun test(@PreviewParameter(UP::class) user: User): String
```

The PSI sees `UP`, but you need to resolve it to `com.example.UserProvider`.

**Required**:
```kotlin
private fun extractProviderClass(annotation: KtAnnotationEntry): String {
    val classLiteral = annotation.valueArguments
        .firstOrNull()
        ?.getArgumentExpression() as? KtClassLiteralExpression
        ?: throw IllegalArgumentException("Provider class not found")

    val simpleName = classLiteral.receiverExpression?.text
        ?: throw IllegalArgumentException("Invalid class literal")

    // Resolve against imports
    val imports = (annotation.containingKtFile as KtFile).importDirectives

    val matchingImport = imports.firstOrNull { import ->
        import.importedFqName?.shortName()?.asString() == simpleName ||
        import.importedFqName?.aliasName == simpleName
    }

    return matchingImport?.importedFqName?.asString()
        ?: throw IllegalArgumentException("Cannot resolve: $simpleName")
}
```

(Note: Still incomplete - doesn't handle same-package classes, wildcards, etc.)

### 3. ClassLoader Lifecycle Conflict

**Problem**: Current code closes classloader after each invocation:
```kotlin
} finally {
    loader.close()  // Line 23 in PreviewRunner.kt
}
```

But providers need the classloader to stay alive across multiple invocations of the same function with different parameter values.

**Impact**: Provider instances become invalid after first invocation.

**Recommendation**: Restructure to keep classloader alive for the duration of all previews for a function, close afterward.

### 4. Fast Path (DirectCompiler) Not Addressed

**Problem**: The system has a "fast path" using `DirectCompiler` + `PatchingClassLoader` for instant recompilation without Bazel. This is a core feature for watch mode.

**Challenge**:
- If provider is defined in the same file being previewed, it needs fast compilation
- If provider is in a different file, it won't be recompiled
- Fast path will break for cross-file providers

**Recommendation**:
- Detect if provider is in the same file
- If yes, compile it with the preview file in fast path
- If no, fall back to full Bazel build (document this limitation)

### 5. Type Resolution Gaps

**Problem**: The proposal shows:
```kotlin
val paramTypes = fn.parameters.map {
    loader.loadClass(it.type)  // How does this handle generics?
}.toTypedArray()
```

**Unhandled Cases**:
- Parameterized types: `List<User>`
- Primitive types: Kotlin `Int` vs Java `int`
- Nullable types: `User?`
- Type aliases
- Inner classes: `User.Address`

**Recommendation**: Start with simple non-generic types, document limitations, expand support incrementally.

### 6. No Combinatorial Explosion Protection

**Problem**: Multi-parameter providers create cartesian products. With no hard limit, could generate millions of previews.

**Example**:
```kotlin
@Preview
fun test(
    @PreviewParameter(Provider1::class) p1: T1,  // 100 values
    @PreviewParameter(Provider2::class) p2: T2,  // 100 values
    @PreviewParameter(Provider3::class) p3: T3   // 100 values
): String  // = 100 × 100 × 100 = 1,000,000 previews!
```

**Recommendation**: Hard limit of 100 total results per function. Fail fast if exceeded with clear error message.

---

## Important Design Issues

### 1. JSON Format Breaking Change

**Problem**: Changes from `{"result": "..."}` to `{"results": [...]}`

**Recommendation**: Always return `results` array, but keep `result` field for backward compatibility:
```json
{
  "name": "foo",
  "result": "...",  // Deprecated but kept for compatibility
  "results": [{"displayName": "foo", "result": "..."}]
}
```

### 2. Display Name API is Error-Prone

**Problem**: Index-based `getDisplayName(index: Int)` can desync from values:
```kotlin
override val values = sequenceOf(User("Alice"), User("Bob"), User("Charlie"))
override fun getDisplayName(index: Int) =
    listOf("Young", "Middle", "Senior")[index]  // If someone reorders values...
```

**Better API**:
```kotlin
interface PreviewParameterProvider<T> {
    val values: Sequence<T>
    fun displayName(value: T): String = value.toString()  // Value-based, not index
}
```

### 3. Error Handling Strategy Unspecified

**Questions**:
- What happens with partial failures?
- Provider instantiation errors vs individual value errors?
- Should one failed value fail the entire function?

**Recommendation**:
- Partial failure is OK - each result can have `error` field
- Provider instantiation failure fails the entire function
```json
{
  "name": "foo",
  "results": [
    {"displayName": "foo [1]", "result": "..."},
    {"displayName": "foo [2]", "error": "NullPointerException: ..."}
  ]
}
```

### 4. Provider Instantiation Assumptions

**Problem**: Code assumes no-arg constructors:
```kotlin
val provider = providerClass.getDeclaredConstructor().newInstance()
```

**Unhandled Cases**:
- Object singletons (need `INSTANCE` field)
- Companion objects
- Expensive initialization in constructor
- Infinite sequences

**Recommendation**:
- Handle objects via `INSTANCE` field like existing `resolveReceiver()` pattern
- Document that providers must have cheap instantiation
- Limit sequence evaluation (e.g., `sequence.take(limit).toList()`)

---

## Revised Implementation Plan

### Phase 0: Foundation & Design Decisions

**BEFORE writing code, resolve these:**

1. **Annotation location**: Separate `preview-annotations` module
2. **JSON format versioning**: Keep backward compatibility with `result` + `results`
3. **Error handling strategy**: Partial failures OK, provider failures fail function
4. **Combinatorial limits**: Hard limit of 100 results per function
5. **Provider location constraints**: Must be on classpath, document fast-path limitations

### Phase 1: Minimal Single Parameter

**Implement the absolute minimum:**

```kotlin
// Minimal API (user-defined or in preview-annotations module)
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PreviewParameter(
    val provider: KClass<out PreviewParameterProvider<*>>
    // NO limit parameter
    // NO display names
)

interface PreviewParameterProvider<T> {
    val values: Sequence<T>
    // NO getDisplayName
}
```

**Changes:**
1. Update `SourceAnalyzer`:
   - Allow exactly one parameter with `@PreviewParameter`
   - Extract provider class name (handle basic imports only)
   - Add `ParameterInfo` to `FunctionInfo`

2. Update `PreviewRunner`:
   - Instantiate provider (handle both class and object)
   - Iterate through values (limit to 10 for safety)
   - Invoke preview function once per value
   - Return `List<PreviewResult>`

3. Update JSON output:
   - Always return `results` array
   - Keep `result` field for backward compatibility
   - Display names: auto-generated `functionName [1]`, `functionName [2]`, etc.

4. **Write extensive tests**:
   - Provider in same file
   - Provider in different file
   - Provider as object vs class
   - Empty provider
   - Provider with errors
   - Type mismatches

**Deliverable**: Working single-parameter providers with auto-generated names

### Phase 2: Display Names & Limits

**Add customization:**

```kotlin
annotation class PreviewParameter(
    val provider: KClass<out PreviewParameterProvider<*>>,
    val limit: Int = Int.MAX_VALUE  // NEW
)

interface PreviewParameterProvider<T> {
    val values: Sequence<T>
    fun displayName(value: T): String = value.toString()  // NEW (value-based, not index)
}
```

**Changes:**
1. Update PSI parsing to extract `limit` parameter
2. Implement `displayName(value)` invocation with error handling
3. Update JSON output with custom display names
4. Enforce global limit of 100 results per function

**Deliverable**: Customizable display names and limits

### Phase 3: Multi-Parameter Support

**Allow multiple parameters:**

```kotlin
@Preview
fun preview(
    @PreviewParameter(ThemeProvider::class) theme: Theme,
    @PreviewParameter(SizeProvider::class) size: Size
): String
```

**Changes:**
1. Update validation to allow multiple `@PreviewParameter`
2. Implement cartesian product generation with explosion protection
3. Implement multi-parameter display name formatting
4. Add tests for combinatorial cases

**Deliverable**: Multi-parameter support with cartesian products

### Phase 4: Fast Path Integration

**Make providers work with DirectCompiler:**

1. Detect if provider is in the same file being compiled
2. If yes, compile it with the preview file in fast path
3. If no, fall back to Bazel build (document this)

**Deliverable**: Providers work in watch mode with fast compilation

### Phase 5: Edge Cases & Polish

1. Improve PSI parsing for complex imports (wildcards, nested packages)
2. Handle type aliases, inner classes, companion objects
3. Better error messages with context
4. Performance optimization (cache provider instances)
5. Comprehensive documentation and examples

**Deliverable**: Production-ready implementation

---

## API Design Improvements

### 1. Value-Based Display Names (Recommended)

Instead of index-based:
```kotlin
interface PreviewParameterProvider<T> {
    val values: Sequence<T>
    fun displayName(value: T): String = value.toString()
}
```

Usage:
```kotlin
class UserProvider : PreviewParameterProvider<User> {
    override val values = sequenceOf(
        User("Alice", 25),
        User("Bob", 35)
    )

    override fun displayName(value: User): String = "${value.name} (${value.age})"
}
```

This keeps value and name together, preventing desync.

### 2. Alternative: PreviewParameterData Class

For even tighter coupling:
```kotlin
data class PreviewParameterData<T>(
    val value: T,
    val displayName: String? = null
)

interface PreviewParameterProvider<T> {
    val values: Sequence<PreviewParameterData<T>>
}
```

### 3. Consider Limit on Provider

Alternative to annotation-based limit:
```kotlin
interface PreviewParameterProvider<T> {
    val values: Sequence<T>
    val limit: Int get() = Int.MAX_VALUE
}
```

Then provider controls its own size:
```kotlin
class UserProvider : PreviewParameterProvider<User> {
    override val limit = 5
    override val values = sequenceOf(...)
}
```

Simpler annotation: `@PreviewParameter(UserProvider::class)`

---

## Key Edge Cases to Handle

1. **Empty provider**: `values` is empty sequence
2. **Provider throws in constructor**: Fail entire function with clear error
3. **Provider throws during iteration**: Fail that specific result, continue others
4. **displayName throws**: Fall back to default name
5. **Companion object providers**: Use `INSTANCE` field pattern
6. **Provider in different package**: Requires import resolution
7. **Type mismatches**: Provider returns `String`, parameter expects `User`
8. **Non-string return types**: Preview function returns complex object
9. **Expensive provider initialization**: Document that providers must be cheap
10. **Sequence evaluation**: Use `take(limit).toList()` to prevent infinite sequences

---

## Summary

### What's Good
- Strong research on Compose approach
- Good architectural alignment with current codebase
- Clear examples of usage patterns
- Sensible phased approach

### What's Missing
- Concrete PSI parsing implementation
- ClassLoader lifecycle management
- Fast-path integration strategy
- Error handling specification
- Combinatorial explosion protection
- Type resolution details

### Recommended Path Forward

1. **Don't start coding yet** - resolve the 6 critical issues first
2. **Implement Phase 0** - make design decisions and document them
3. **Build minimal Phase 1** - prove the concept with simplest case
4. **Test extensively** - edge cases will reveal hidden complexity
5. **Iterate incrementally** - add features only after previous phase is solid

### Reality Check

The full Compose-style API is achievable, but the implementation is **2-3x more complex** than the original proposal suggests. Budget accordingly and be prepared to discover additional complexity during implementation.
