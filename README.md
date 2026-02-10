# Kotlin Bazel Previews

A tool for running preview functions in Kotlin projects built with Bazel.

## Overview

This tool allows you to mark functions with `@Preview` and run them to quickly see their output without running the full application. It supports watch mode for instant feedback during development.

## Quick Start

### Basic Previews

Define preview functions in your code:

```kotlin
package examples

annotation class Preview

@Preview
fun greetWorld(): String {
    return "Hello, World!"
}
```

Run the preview tool:

```bash
bazel run //src/main/kotlin/preview:preview-tool examples/Greeter.kt
```

### Parameterized Previews (Phase 1 - In Development)

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

class UserPreviewParameterProvider : PreviewParameterProvider<User> {
    override val values = sequenceOf(
        User("Alice", 25),
        User("Bob", 35),
        User("Charlie", 45)
    )
}
```

3. **Use in preview functions**:

```kotlin
import preview.annotations.PreviewParameter

@Preview
fun userCard(@PreviewParameter(UserPreviewParameterProvider::class) user: User): String {
    return "User: ${user.name}, Age: ${user.age}"
}
```

This will generate three previews, one for each user.

> **Note**: Parameterized preview support is currently being implemented. See [PREVIEW_PROVIDER_PROPOSAL.md](PREVIEW_PROVIDER_PROPOSAL.md) for details.

## Preview Annotations Module

The `preview-annotations` module provides:

- `@PreviewParameter` - Annotation for preview function parameters
- `PreviewParameterProvider<T>` - Interface for supplying test values

### API Reference

#### `@PreviewParameter`

```kotlin
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class PreviewParameter(
    val provider: KClass<out PreviewParameterProvider<*>>
)
```

Marks a preview function parameter to receive values from a provider.

#### `PreviewParameterProvider<T>`

```kotlin
interface PreviewParameterProvider<T> {
    val values: Sequence<T>
}
```

Implement this interface to provide test data for previews.

**Requirements**:
- Must have a no-argument constructor
- `values` should return a reasonable number of items (<100)

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

```bash
bazel test //...
```

### Watch Mode

The tool supports watch mode for instant recompilation:

```bash
bazel run //src/main/kotlin/preview:preview-tool -- --watch examples/Greeter.kt
```

## Architecture

See the proposal documents for detailed implementation plans:

- [PREVIEW_PROVIDER_PROPOSAL.md](PREVIEW_PROVIDER_PROPOSAL.md) - Technical proposal for parameterized previews
- [PREVIEW_PROVIDER_EXAMPLES.md](PREVIEW_PROVIDER_EXAMPLES.md) - Usage examples
- [REVIEW_FINDINGS.md](REVIEW_FINDINGS.md) - Review of the proposal
- [ADDRESSING_CRITICAL_ISSUES.md](ADDRESSING_CRITICAL_ISSUES.md) - Step-by-step implementation guide

## Project Structure

```
.
├── preview-annotations/          # Annotations module (user-facing API)
│   ├── BUILD.bazel
│   └── src/main/kotlin/preview/annotations/
│       ├── PreviewParameter.kt
│       └── PreviewParameterProvider.kt
├── src/main/kotlin/preview/      # Preview tool implementation
│   ├── SourceAnalyzer.kt        # Discovers preview functions via PSI
│   ├── PreviewRunner.kt         # Invokes previews via reflection
│   ├── BazelRunner.kt           # Bazel integration
│   └── Main.kt                  # CLI entry point
├── examples/                     # Example preview functions
│   ├── Greeter.kt               # Basic previews
│   ├── Shapes.kt                # More examples
│   └── UserProvider.kt          # Example provider
└── src/test/                    # Tests
```

## Status

**Current Status**: Phase 1 in progress - Implementing parameterized preview support

### Issue 1: Annotation Location ✅ COMPLETE
- [x] Created `preview-annotations` module
- [x] Defined `@PreviewParameter` annotation
- [x] Defined `PreviewParameterProvider<T>` interface
- [x] Module builds successfully
- [x] Examples can depend on it
- [x] Documentation written

### Remaining Issues (In Progress)
- [ ] Issue 2: PSI Parsing - Extract provider class from annotations
- [ ] Issue 3: ClassLoader Lifecycle - Keep loader alive for multiple invocations
- [ ] Issue 4: Fast Path - Test with DirectCompiler
- [ ] Issue 5: Type Resolution - Support simple types
- [ ] Issue 6: Combinatorial Explosion - Enforce limits

## License

(Add your license information here)
