# Addressing Critical Issues: Step-by-Step Guide

This document provides a structured approach to resolving each critical issue identified in the review before beginning implementation.

---

## Issue 1: Annotation Location Undefined

### Decision Needed
Where should `PreviewParameterProvider` interface and `@PreviewParameter` annotation live?

### Options Analysis

#### Option A: Tool's Own Package (`preview.*`)
```
preview/
├── PreviewParameterProvider.kt
├── PreviewParameter.kt
├── SourceAnalyzer.kt
└── PreviewRunner.kt
```

**Pros:**
- No additional dependency for users
- Tool controls the API version
- Simplified distribution

**Cons:**
- Users need runtime access to tool classes
- Mixing tool internals with user-facing API
- Hard to use providers in user's test code

#### Option B: User-Defined (Per Project)
```kotlin
// User creates in their project
package examples

annotation class PreviewParameter(...)
interface PreviewParameterProvider<T> { ... }
```

**Pros:**
- Maximum flexibility
- No dependencies
- Users control the interface

**Cons:**
- Inconsistent across projects
- Tool must discover by name only (fragile)
- Documentation harder (which signature to use?)

#### Option C: Separate Annotations Module (RECOMMENDED)
```
preview-annotations/
├── src/main/kotlin/preview/
│   ├── PreviewParameter.kt
│   └── PreviewParameterProvider.kt
└── BUILD.bazel

user-project/
├── BUILD.bazel (deps = ["//preview-annotations"])
└── src/main/kotlin/examples/
    └── UserProvider.kt
```

**Pros:**
- Clean separation of concerns
- Users can depend on it for compile + test
- Versioned API contract
- IDE support (autocomplete, navigation)

**Cons:**
- One more artifact to distribute
- Users must add dependency

### Recommended Decision

**Choose Option C: Separate annotations module**

### Action Items

1. **Create `preview-annotations/` directory structure**
   ```
   preview-annotations/
   ├── BUILD.bazel
   └── src/main/kotlin/preview/annotations/
       ├── PreviewParameter.kt
       └── PreviewParameterProvider.kt
   ```

2. **Define the minimal API**
   ```kotlin
   // preview-annotations/src/main/kotlin/preview/annotations/PreviewParameter.kt
   package preview.annotations

   @Target(AnnotationTarget.VALUE_PARAMETER)
   @Retention(AnnotationRetention.RUNTIME)
   annotation class PreviewParameter(
       val provider: KClass<out PreviewParameterProvider<*>>
   )
   ```

   ```kotlin
   // preview-annotations/src/main/kotlin/preview/annotations/PreviewParameterProvider.kt
   package preview.annotations

   interface PreviewParameterProvider<T> {
       val values: Sequence<T>
   }
   ```

3. **Create BUILD.bazel for the module**
   ```python
   load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

   kt_jvm_library(
       name = "preview-annotations",
       srcs = glob(["src/main/kotlin/**/*.kt"]),
       visibility = ["//visibility:public"],
   )
   ```

4. **Update examples to use the new annotations**
   ```kotlin
   // examples/BUILD.bazel
   kt_jvm_library(
       name = "examples",
       srcs = glob(["*.kt"]),
       deps = ["//preview-annotations"],
   )
   ```

5. **Update PSI parsing to look for qualified name**
   ```kotlin
   // SourceAnalyzer.kt
   private fun isPreviewCandidate(fn: KtNamedFunction): Boolean {
       return fn.annotationEntries.any { it.shortName?.asString() == "Preview" } &&
           // ... other checks
   }

   private fun hasPreviewParameterAnnotation(param: KtParameter): Boolean {
       return param.annotationEntries.any {
           val shortName = it.shortName?.asString()
           // Accept both simple name and fully-qualified
           shortName == "PreviewParameter" ||
           it.text.contains("preview.annotations.PreviewParameter")
       }
   }
   ```

6. **Document the dependency requirement**
   ```markdown
   # README.md

   ## For Users: Adding Preview Parameters

   Add the annotations dependency to your BUILD file:
   ```bzl
   kt_jvm_library(
       name = "mylib",
       srcs = ["MyPreviews.kt"],
       deps = ["@preview-annotations"],
   )
   ```
   ```

### Validation
- [ ] Module builds successfully
- [ ] Examples can import and use annotations
- [ ] Tool can find annotations via PSI
- [ ] Documentation updated

---

## Issue 2: PSI Parsing Complexity

### Decision Needed
How to resolve `UserProvider::class` from annotations to fully-qualified class names?

### Implementation Strategy

#### Step 1: Simple Case (Same Package)

Handle providers in the same package as preview functions:

```kotlin
private fun extractProviderClass(
    annotation: KtAnnotationEntry,
    currentPackage: String
): String {
    val classLiteral = annotation.valueArguments
        .firstOrNull()
        ?.getArgumentExpression() as? KtClassLiteralExpression
        ?: throw IllegalArgumentException("@PreviewParameter requires provider class")

    val simpleName = classLiteral.receiverExpression?.text
        ?: throw IllegalArgumentException("Invalid class literal")

    // For now, assume same package
    return if (currentPackage.isEmpty()) {
        simpleName
    } else {
        "$currentPackage.$simpleName"
    }
}
```

**Limitation**: Only works for same-package providers.

#### Step 2: Add Import Resolution

```kotlin
private fun extractProviderClass(
    annotation: KtAnnotationEntry,
    currentPackage: String
): String {
    val classLiteral = annotation.valueArguments
        .firstOrNull()
        ?.getArgumentExpression() as? KtClassLiteralExpression
        ?: throw IllegalArgumentException("@PreviewParameter requires provider class")

    val simpleName = classLiteral.receiverExpression?.text
        ?: throw IllegalArgumentException("Invalid class literal")

    // Check if it's already qualified
    if (simpleName.contains(".")) {
        return simpleName
    }

    // Look for matching import
    val ktFile = annotation.containingKtFile
    val imports = ktFile.importDirectives

    // Find exact import
    for (import in imports) {
        val importedFqName = import.importedFqName ?: continue

        // Check if imported name matches
        if (importedFqName.shortName().asString() == simpleName) {
            return importedFqName.asString()
        }

        // Check if imported with alias
        val alias = import.aliasName
        if (alias == simpleName) {
            return importedFqName.asString()
        }
    }

    // Not found in imports, assume same package
    return if (currentPackage.isEmpty()) {
        simpleName
    } else {
        "$currentPackage.$simpleName"
    }
}
```

#### Step 3: Handle Wildcard Imports (Later)

Wildcard imports (`import com.example.*`) require checking if class exists at runtime, which is complex. **Document as limitation for Phase 1**.

### Action Items

1. **Implement Step 1 (same package only)**
2. **Add unit tests**:
   ```kotlin
   @Test
   fun `extracts provider class from same package`() {
       val source = """
           package examples

           class UserProvider : PreviewParameterProvider<User> { ... }

           @Preview
           fun test(@PreviewParameter(UserProvider::class) user: User) = ""
       """.trimIndent()

       val functions = analyzer.findPreviewFunctionsFromContent(source, "Test.kt")
       assertEquals("examples.UserProvider", functions[0].parameters[0].providerClass)
   }
   ```

3. **Test with imports**:
   ```kotlin
   @Test
   fun `extracts provider class with import`() {
       val source = """
           package examples

           import com.other.UserProvider

           @Preview
           fun test(@PreviewParameter(UserProvider::class) user: User) = ""
       """.trimIndent()

       val functions = analyzer.findPreviewFunctionsFromContent(source, "Test.kt")
       assertEquals("com.other.UserProvider", functions[0].parameters[0].providerClass)
   }
   ```

4. **Test with alias**:
   ```kotlin
   @Test
   fun `extracts provider class with alias`() {
       val source = """
           package examples

           import com.other.UserProvider as UP

           @Preview
           fun test(@PreviewParameter(UP::class) user: User) = ""
       """.trimIndent()

       val functions = analyzer.findPreviewFunctionsFromContent(source, "Test.kt")
       assertEquals("com.other.UserProvider", functions[0].parameters[0].providerClass)
   }
   ```

5. **Document limitations**:
   - Wildcard imports not supported in Phase 1
   - Providers should use explicit imports or same package

### Validation
- [ ] Same-package providers work
- [ ] Explicit imports work
- [ ] Import aliases work
- [ ] Clear error message for unresolved classes
- [ ] Tests pass

---

## Issue 3: ClassLoader Lifecycle

### Decision Needed
How to manage classloader lifecycle when multiple invocations per function are needed?

### Current Code Problem

```kotlin
// PreviewRunner.kt (current)
fun invoke(classpathJars: List<String>, fn: FunctionInfo): String? {
    val urls = classpathJars.map { File(it).toURI().toURL() }.toTypedArray()
    return invoke(URLClassLoader(urls, ...), fn)
}

fun invoke(loader: URLClassLoader, fn: FunctionInfo): String? {
    return try {
        // ... invoke once
    } finally {
        loader.close()  // ❌ Problem: closes after single invocation
    }
}
```

With parameters, need multiple invocations per function:
```kotlin
// UserProvider has 3 values
// Need to invoke preview function 3 times with same classloader
```

### Solution Options

#### Option A: Restructure to Keep Loader Alive (RECOMMENDED)

```kotlin
fun invoke(classpathJars: List<String>, fn: FunctionInfo): List<PreviewResult> {
    val urls = classpathJars.map { File(it).toURI().toURL() }.toTypedArray()

    URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
        return invokeWithLoader(loader, fn)
    }
}

private fun invokeWithLoader(loader: URLClassLoader, fn: FunctionInfo): List<PreviewResult> {
    if (fn.parameters.isEmpty()) {
        // Legacy path: single invocation
        val result = invokeSingle(loader, fn, emptyList())
        return listOf(PreviewResult(fn.name, null, result))
    }

    // New path: instantiate provider once, iterate values
    val provider = instantiateProvider(loader, fn.parameters[0])
    val results = mutableListOf<PreviewResult>()

    provider.values.forEachIndexed { index, value ->
        try {
            val result = invokeSingle(loader, fn, listOf(value))
            results.add(PreviewResult(fn.name, "[$index]", result))
        } catch (e: Exception) {
            results.add(PreviewResult(fn.name, "[$index]", error = e.message))
        }
    }

    return results
    // Classloader closes when use block exits
}

private fun invokeSingle(
    loader: URLClassLoader,
    fn: FunctionInfo,
    args: List<Any?>
): String? {
    val clazz = loader.loadClass(fn.jvmClassName)
    val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
    val method = clazz.getMethod(fn.name, *paramTypes)
    val receiver = resolveReceiver(clazz, fn.containerKind)

    return try {
        val result = method.invoke(receiver, *args.toTypedArray())
        result?.toString()
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }
}
```

#### Option B: Cache Classloader (More Complex)

Create a classloader cache that reuses loaders across multiple function invocations. **Not recommended for Phase 1** - adds complexity.

### Action Items

1. **Refactor `PreviewRunner.invoke()` signature**
   ```kotlin
   // Change from:
   fun invoke(classpathJars: List<String>, fn: FunctionInfo): String?

   // To:
   fun invoke(classpathJars: List<String>, fn: FunctionInfo): List<PreviewResult>
   ```

2. **Update data classes**
   ```kotlin
   data class PreviewResult(
       val functionName: String,
       val displayName: String?,
       val result: String? = null,
       val error: String? = null
   )
   ```

3. **Update callers** (Main.kt, PreviewServer.kt)
   ```kotlin
   // Before:
   val result = PreviewRunner.invoke(classpath, fn)
   println("$name -> $result")

   // After:
   val results = PreviewRunner.invoke(classpath, fn)
   results.forEach { r ->
       if (r.error != null) {
           println("${r.displayName} -> ERROR: ${r.error}")
       } else {
           println("${r.displayName} -> ${r.result}")
       }
   }
   ```

4. **Add tests**
   ```kotlin
   @Test
   fun `classloader stays alive for multiple invocations`() {
       val results = PreviewRunner.invoke(classpath, functionWithProvider)
       assertEquals(3, results.size)  // Provider has 3 values
       assertTrue(results.all { it.error == null })
   }
   ```

### Validation
- [ ] Classloader closes after all invocations
- [ ] Provider instances work across invocations
- [ ] No resource leaks
- [ ] Tests pass

---

## Issue 4: Fast Path Integration

### Decision Needed
How should providers work with `DirectCompiler` + `PatchingClassLoader` in watch mode?

### Current Fast Path

```kotlin
// PreviewServer.kt
if (sourceChanged) {
    // Try fast compilation first
    val result = DirectCompiler.compile(listOf(sourceFile), cachedClasspath, scratchDir)

    if (result.success) {
        // Use patching classloader with scratch dir
        renderPreview(usePatchDir = true)
    } else {
        // Fall back to full Bazel build
        runBazelBuild()
    }
}
```

### Challenge

If provider is in **different file** than preview function:
- Fast path only compiles changed file (preview function)
- Provider class is in different file (not compiled)
- Provider class must come from cached classpath
- **This works!** No issue.

If provider is in **same file** as preview function:
- Fast path compiles entire file
- Provider class is compiled into scratch dir
- **This works!** No issue.

### Actually... Not a Problem!

The fast path should work naturally:
1. If provider is already compiled (different file), it's on `cachedClasspath`
2. If provider is in same file, `DirectCompiler` compiles it
3. `PatchingClassLoader` overlays scratch dir on top of cached classpath
4. Provider is found either way

### Action Items

1. **Test with provider in same file**
   ```kotlin
   // examples/Greeter.kt
   class UserProvider : PreviewParameterProvider<User> {
       override val values = sequenceOf(User("Alice"), User("Bob"))
   }

   @Preview
   fun greet(@PreviewParameter(UserProvider::class) user: User) = "Hello, ${user.name}"
   ```

2. **Test with provider in different file**
   ```kotlin
   // examples/providers/UserProvider.kt
   class UserProvider : PreviewParameterProvider<User> { ... }

   // examples/Greeter.kt
   import examples.providers.UserProvider

   @Preview
   fun greet(@PreviewParameter(UserProvider::class) user: User) = "Hello, ${user.name}"
   ```

3. **Verify `PatchingClassLoader` finds both**

4. **Document behavior**
   ```markdown
   ## Watch Mode with Providers

   Providers work in watch mode:
   - If provider is in the same file as preview, it's recompiled instantly
   - If provider is in a different file, ensure it's already built
   - Changing the provider requires a full rebuild (or edit + save the preview file to trigger recompile)
   ```

### Validation
- [ ] Provider in same file works in watch mode
- [ ] Provider in different file works in watch mode
- [ ] Documentation explains behavior
- [ ] No unexpected fallbacks to Bazel build

---

## Issue 5: Type Resolution Gaps

### Decision Needed
Which parameter types to support in Phase 1?

### Type Challenges

```kotlin
@Preview
fun test1(@PreviewParameter(...) user: User) = ""           // ✅ Simple class
fun test2(@PreviewParameter(...) list: List<User>) = ""     // ❓ Generic type
fun test3(@PreviewParameter(...) nullable: User?) = ""      // ❓ Nullable type
fun test4(@PreviewParameter(...) num: Int) = ""             // ❓ Primitive
fun test5(@PreviewParameter(...) alias: UserId) = ""        // ❓ Type alias
fun test6(@PreviewParameter(...) inner: User.Address) = ""  // ❓ Inner class
```

### Phase 1 Decision: Support Simple Types Only

**Supported**:
- Simple classes: `User`, `Order`, `Theme`
- Interfaces: `Drawable`
- Enums: `Status`
- Objects: `Config`

**Not Supported (Phase 1)**:
- Generics: `List<User>` → Document limitation
- Primitives: `Int`, `Boolean` → Use wrapper types
- Nullables: `User?` → Use non-null types
- Type aliases → Use original type
- Inner classes → Define as top-level

### Action Items

1. **Add validation in `SourceAnalyzer`**
   ```kotlin
   private fun extractParameterInfo(param: KtParameter): ParameterInfo? {
       val typeName = param.typeReference?.text ?: return null

       // Phase 1: Reject unsupported types
       when {
           typeName.contains("<") -> {
               throw IllegalArgumentException(
                   "Generic types not supported in Phase 1: $typeName"
               )
           }
           typeName.endsWith("?") -> {
               throw IllegalArgumentException(
                   "Nullable types not supported in Phase 1: $typeName"
               )
           }
           // Add more checks as needed
       }

       // ... rest of extraction
   }
   ```

2. **Document limitations**
   ```markdown
   ## Phase 1 Limitations

   ### Supported Parameter Types
   - Simple classes: `User`, `Order`
   - Enums: `Status`, `Theme`
   - Interfaces: `Renderer`

   ### Not Yet Supported
   - Generic types: `List<User>` - use wrapper class: `data class UserList(val items: List<User>)`
   - Nullable types: `User?` - use non-null types
   - Primitives: `Int`, `Boolean` - use wrapper types or classes
   - Type aliases - use original type
   ```

3. **Add tests for unsupported types**
   ```kotlin
   @Test
   fun `rejects generic type parameters`() {
       val source = """
           @Preview
           fun test(@PreviewParameter(ListProvider::class) items: List<User>) = ""
       """.trimIndent()

       val exception = assertThrows<IllegalArgumentException> {
           analyzer.findPreviewFunctionsFromContent(source, "Test.kt")
       }
       assertTrue(exception.message!!.contains("Generic types not supported"))
   }
   ```

### Validation
- [ ] Simple types work
- [ ] Unsupported types fail with clear error
- [ ] Documentation explains limitations
- [ ] Tests cover edge cases

---

## Issue 6: Combinatorial Explosion Protection

### Decision Needed
What limits to enforce on multi-parameter previews?

### The Problem

```kotlin
@Preview
fun test(
    @PreviewParameter(Provider1::class) p1: T1,  // 50 values
    @PreviewParameter(Provider2::class) p2: T2,  // 50 values
    @PreviewParameter(Provider3::class) p3: T3   // 50 values
): String  // = 50 × 50 × 50 = 125,000 previews!
```

### Recommended Limits

1. **Hard limit: 100 results per function** (fail fast)
2. **Soft warning: 20 results per function** (log warning)
3. **Per-parameter limit: Use provider's limit or 10**

### Implementation Strategy

```kotlin
private fun invokeWithLoader(loader: URLClassLoader, fn: FunctionInfo): List<PreviewResult> {
    if (fn.parameters.isEmpty()) {
        // ... single invocation
    }

    // Phase 1: Single parameter only
    if (fn.parameters.size > 1) {
        throw IllegalArgumentException(
            "Multiple @PreviewParameter parameters not supported in Phase 1"
        )
    }

    val provider = instantiateProvider(loader, fn.parameters[0])
    val values = provider.values.take(100).toList()  // Hard limit

    if (values.size > 20) {
        System.err.println(
            "Warning: ${fn.name} generates ${values.size} previews (>20). Consider reducing."
        )
    }

    // ... generate results
}
```

For Phase 3 (multi-parameter):
```kotlin
private fun checkCombinatorics(providers: List<ProviderInstance>) {
    val totalCombinations = providers.map { it.values.size }
        .fold(1L) { acc, size -> acc * size }

    if (totalCombinations > 100) {
        throw IllegalArgumentException(
            "Too many preview combinations: $totalCombinations (max 100). " +
            "Use limit parameter to reduce: @PreviewParameter(Provider::class, limit = N)"
        )
    }

    if (totalCombinations > 20) {
        System.err.println(
            "Warning: Generating $totalCombinations previews (>20)"
        )
    }
}
```

### Action Items

1. **Add limit enforcement in Phase 1**
2. **Add warning logs for large preview counts**
3. **Document limits**
   ```markdown
   ## Preview Limits

   To prevent overwhelming the preview system:
   - **Hard limit**: 100 results per preview function
   - **Soft warning**: 20 results (consider reducing)

   Reduce preview count by:
   - Using `limit` parameter: `@PreviewParameter(Provider::class, limit = 5)`
   - Splitting into multiple preview functions
   - Using more targeted providers
   ```

4. **Add tests**
   ```kotlin
   @Test
   fun `enforces hard limit of 100 results`() {
       val hugeProvider = object : PreviewParameterProvider<Int> {
           override val values = (1..200).asSequence()
       }

       val exception = assertThrows<IllegalArgumentException> {
           // ... invoke with huge provider
       }
       assertTrue(exception.message!!.contains("max 100"))
   }
   ```

### Validation
- [ ] Hard limit enforced
- [ ] Warning logged for large counts
- [ ] Clear error messages
- [ ] Documentation explains limits

---

## Summary Checklist

Use this to track progress on resolving critical issues:

- [ ] **Issue 1: Annotation Location**
  - [ ] Decision made: Separate module
  - [ ] Module created with BUILD file
  - [ ] API defined (minimal)
  - [ ] Examples updated
  - [ ] Documentation written

- [ ] **Issue 2: PSI Parsing**
  - [ ] Same-package resolution implemented
  - [ ] Import resolution implemented
  - [ ] Alias resolution implemented
  - [ ] Tests written
  - [ ] Limitations documented

- [ ] **Issue 3: ClassLoader Lifecycle**
  - [ ] Signature refactored to return List
  - [ ] Classloader kept alive per function
  - [ ] Callers updated
  - [ ] Tests pass
  - [ ] No resource leaks

- [ ] **Issue 4: Fast Path**
  - [ ] Same-file provider tested
  - [ ] Different-file provider tested
  - [ ] Behavior documented
  - [ ] No unexpected Bazel fallbacks

- [ ] **Issue 5: Type Resolution**
  - [ ] Simple types supported
  - [ ] Unsupported types rejected with errors
  - [ ] Limitations documented
  - [ ] Tests cover edge cases

- [ ] **Issue 6: Combinatorial Explosion**
  - [ ] Hard limit (100) enforced
  - [ ] Soft warning (20) logged
  - [ ] Multi-parameter rejected in Phase 1
  - [ ] Error messages clear
  - [ ] Limits documented

---

## Next Steps After Resolution

Once all critical issues are resolved:

1. **Review decisions** - ensure they're documented
2. **Create GitHub issues/tasks** - track implementation work
3. **Begin Phase 1 implementation** - with confidence that design is solid
4. **Iterate based on learnings** - adjust as needed

The goal is to make informed decisions upfront rather than discovering blockers during implementation.
