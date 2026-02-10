# Kotlin Bazel Previews

A tool for running preview functions in Kotlin projects built with Bazel, with support for parameterized previews using data providers.

## Overview

This tool allows you to mark functions with `@Preview` and run them to quickly see their output without running the full application. It supports:

- ✅ **Basic previews** - Zero-parameter functions
- ✅ **Single-parameter previews** - Functions with one `@PreviewParameter`
- ✅ **Multi-parameter previews** - Functions with multiple `@PreviewParameter` annotations (cartesian product)
- ✅ **Custom display names** - Provider-defined names for better readability
- ✅ **Watch mode** - Instant recompilation and preview updates

## Quick Start

### Basic Previews

Define preview functions in your code:

```kotlin
package examples

import preview.annotations.Preview

@Preview
fun greetWorld(): String {
    return "Hello, World!"
}
```

Run the preview tool:

```bash
bazel run //src/main/kotlin/preview:preview-tool -- $(pwd) examples/Greeter.kt
```

Output:
```
greetWorld() => Hello, World!
```

## Parameterized Previews

### Single Parameter

For previews that need test data, use `@PreviewParameter`:

1. **Add the annotations dependency** to your `BUILD.bazel`:

```python
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

kt_jvm_library(
    name = "mylib",
    srcs = glob(["*.kt"]),
    deps = ["//preview-annotations"],
)
```

2. **Define a provider** that supplies test values:

```kotlin
import preview.annotations.PreviewParameterProvider

data class User(val name: String, val age: Int)

class UserProvider : PreviewParameterProvider<User> {
    override val values = sequenceOf(
        User("Alice Anderson", 25),
        User("Bob Builder", 35),
        User("Charlie Chaplin", 45)
    )
}
```

3. **Use in preview functions**:

```kotlin
import preview.annotations.Preview
import preview.annotations.PreviewParameter

@Preview
fun userCard(@PreviewParameter(UserProvider::class) user: User): String {
    return "User: ${user.name}, Age: ${user.age}"
}
```

Output:
```
userCard[0] => User: Alice Anderson, Age: 25
userCard[1] => User: Bob Builder, Age: 35
userCard[2] => User: Charlie Chaplin, Age: 45
```

### Custom Display Names

Add a `getDisplayName()` method to your provider for better readability:

```kotlin
class UserProvider : PreviewParameterProvider<User> {
    // Materialize to list for efficient getDisplayName() access
    private val userList = listOf(
        User("Alice Anderson", 25),
        User("Bob Builder", 35),
        User("Charlie Chaplin", 45)
    )

    override val values = userList.asSequence()

    override fun getDisplayName(index: Int): String? {
        return userList.getOrNull(index)?.name
    }
}
```

Output:
```
userCard[Alice Anderson] => User: Alice Anderson, Age: 25
userCard[Bob Builder] => User: Bob Builder, Age: 35
userCard[Charlie Chaplin] => User: Charlie Chaplin, Age: 45
```

### Multi-Parameter Previews

Use multiple `@PreviewParameter` annotations to test all combinations:

```kotlin
data class Theme(val name: String, val primaryColor: String)

class ThemeProvider : PreviewParameterProvider<Theme> {
    override val values = sequenceOf(
        Theme("Light", "#FFFFFF"),
        Theme("Dark", "#000000")
    )

    override fun getDisplayName(index: Int): String? {
        return values.elementAtOrNull(index)?.name
    }
}

@Preview
fun userProfileCard(
    @PreviewParameter(UserProvider::class) user: User,
    @PreviewParameter(ThemeProvider::class) theme: Theme
): String {
    return """
        ╔════════════════════════════════╗
        ║  Profile - ${theme.name} Theme
        ╠════════════════════════════════╣
        ║  ${user.name}, ${user.age}
        ║  Color: ${theme.primaryColor}
        ╚════════════════════════════════╝
    """.trimIndent()
}
```

This generates **3 users × 2 themes = 6 previews**:

```
userProfileCard[Alice Anderson, Light] => (profile card output)
userProfileCard[Alice Anderson, Dark] => (profile card output)
userProfileCard[Bob Builder, Light] => (profile card output)
userProfileCard[Bob Builder, Dark] => (profile card output)
userProfileCard[Charlie Chaplin, Light] => (profile card output)
userProfileCard[Charlie Chaplin, Dark] => (profile card output)
```

## Preview Annotations API

### `@Preview`

```kotlin
@Target(AnnotationTarget.FUNCTION)
annotation class Preview
```

Marks a function as a preview. Preview functions must:
- Be top-level, or in a `class`/`object` with a no-arg constructor
- Not be `private`, `suspend`, or `abstract`
- Either have zero parameters, or all parameters annotated with `@PreviewParameter`

### `@PreviewParameter`

```kotlin
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class PreviewParameter(
    val provider: KClass<out PreviewParameterProvider<*>>
)
```

Marks a preview function parameter to receive values from a provider.

### `PreviewParameterProvider<T>`

```kotlin
interface PreviewParameterProvider<T> {
    val values: Sequence<T>

    fun getDisplayName(index: Int): String? = null
}
```

Implement this interface to provide test data for previews.

**Requirements**:
- Must have a no-argument constructor OR be a Kotlin `object`
- `values` should return a reasonable number of items (hard limit: 100 per provider)
- `getDisplayName()` is optional - returns custom display names for each value

**Limits**:
- Hard limit: 100 values per provider
- Hard limit: 100 total combinations for multi-parameter previews
- Soft warning: 20+ combinations logs a warning

## Examples

See the `examples/` directory:

- **`Greeter.kt`** - Basic zero-parameter previews
- **`ParameterizedPreview.kt`** - Single-parameter preview with custom display names
- **`MultiParameterPreview.kt`** - Multi-parameter previews with 2 and 3 parameters

Run examples:

```bash
# Basic previews
bazel run //src/main/kotlin/preview:preview-tool -- $(pwd) examples/Greeter.kt

# Single-parameter with custom names
bazel run //src/main/kotlin/preview:preview-tool -- $(pwd) examples/ParameterizedPreview.kt

# Multi-parameter cartesian product
bazel run //src/main/kotlin/preview:preview-tool -- $(pwd) examples/MultiParameterPreview.kt
```

## Development

### Building

Build the entire project:

```bash
bazel build //...
```

Build just the preview tool:

```bash
bazel build //src/main/kotlin/preview:preview-tool
```

Build the annotations module:

```bash
bazel build //preview-annotations:preview-annotations
```

### Running Tests

Run all tests:

```bash
bazel test //...
```

Run specific test suite:

```bash
bazel test //src/test/kotlin/preview:PreviewRunnerTest
bazel test //src/test/kotlin/preview:SourceAnalyzerTest
```

**Current Test Coverage**: 94 tests (43 in PreviewRunnerTest, 51 in SourceAnalyzerTest)

### Watch Mode

The tool supports watch mode for instant recompilation:

```bash
bazel run //src/main/kotlin/preview:preview-tool -- --watch $(pwd) examples/Greeter.kt
```

Changes to preview files are detected and recompiled without running Bazel.

## Architecture

The preview system has three main components:

1. **SourceAnalyzer** - Parses Kotlin source files using PSI to discover `@Preview` functions and extract parameter providers
2. **PreviewRunner** - Uses reflection to instantiate providers, iterate through values, and invoke preview functions
3. **BazelRunner** - Integrates with Bazel to build targets and resolve classpaths

### Key Design Decisions

- **Separate annotations module**: `preview-annotations` is a compile-only dependency for users
- **Reflection-based invocation**: Avoids classloader isolation issues between tool and user code
- **Sequence-based providers**: Lazy evaluation for memory efficiency
- **Cartesian product**: Multi-parameter previews generate all value combinations
- **Error isolation**: Individual preview failures don't stop batch processing

## Project Structure

```
.
├── preview-annotations/          # Annotations module (user-facing API)
│   ├── BUILD.bazel
│   └── src/main/kotlin/preview/annotations/
│       ├── Preview.kt
│       ├── PreviewParameter.kt
│       └── PreviewParameterProvider.kt
├── src/main/kotlin/preview/      # Preview tool implementation
│   ├── SourceAnalyzer.kt        # PSI-based discovery
│   ├── PreviewRunner.kt         # Reflection-based invocation
│   ├── BazelRunner.kt           # Bazel integration
│   ├── DirectCompiler.kt        # Fast path for watch mode
│   ├── PreviewServer.kt         # JSON output server
│   └── Main.kt                  # CLI entry point
├── examples/                     # Example preview functions
│   ├── Greeter.kt               # Basic previews
│   ├── ParameterizedPreview.kt  # Single-parameter with custom names
│   ├── MultiParameterPreview.kt # Multi-parameter cartesian product
│   └── UserProvider.kt          # Example provider
└── src/test/                    # Tests (94 total)
    ├── PreviewRunnerTest.kt     # 43 tests for invocation
    ├── SourceAnalyzerTest.kt    # 51 tests for PSI parsing
    └── ...
```

## Implementation Status

**All core features complete!** ✅

- ✅ **Phase 1**: Single-parameter support (provider instantiation, iteration, invocation)
- ✅ **Phase 2**: Custom display names (`getDisplayName()` method support)
- ✅ **Phase 3**: Multi-parameter support (cartesian product generation)

See [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for detailed implementation history.

### Future Enhancements

Potential improvements for future releases:

- **Type validation**: Expand support for generic types (`List<User>`), nullables (`User?`), and primitives
- **Advanced features**: Per-provider `limit` parameter, provider composition
- **Integration improvements**: Better error reporting, IDE integration helpers

See the GitHub issues for planned enhancements.

## Documentation

- [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) - Phase-by-phase implementation tracking
- [PREVIEW_PROVIDER_PROPOSAL.md](PREVIEW_PROVIDER_PROPOSAL.md) - Original technical proposal
- [PREVIEW_PROVIDER_EXAMPLES.md](PREVIEW_PROVIDER_EXAMPLES.md) - Usage examples and patterns
- [REVIEW_FINDINGS.md](REVIEW_FINDINGS.md) - Critical issues and solutions
- [ADDRESSING_CRITICAL_ISSUES.md](ADDRESSING_CRITICAL_ISSUES.md) - Step-by-step implementation guide

## License

(Add your license information here)
