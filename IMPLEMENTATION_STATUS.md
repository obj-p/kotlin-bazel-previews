# PreviewParameter Implementation Status

This document tracks the progress of implementing @PreviewParameter support. Use this as a guide to understand what's been completed and what's next.

## Current Branch
`feature/preview-parameter-provider`

## Phase 0: Critical Issues Resolution

### ✅ Issue 1: Annotation Location (COMPLETE)

**Decision**: Created separate `preview-annotations/` module

**What's Done**:
- [x] Created `preview-annotations/` directory structure
- [x] Defined `@PreviewParameter` annotation in `preview-annotations/src/main/kotlin/preview/annotations/PreviewParameter.kt`
- [x] Defined `PreviewParameterProvider<T>` interface in `preview-annotations/src/main/kotlin/preview/annotations/PreviewParameterProvider.kt`
- [x] Created `preview-annotations/BUILD.bazel` with public visibility
- [x] Module builds successfully: `bazel build //preview-annotations:preview-annotations`
- [x] Updated `examples/BUILD.bazel` to depend on `//preview-annotations`
- [x] Created example provider in `examples/UserProvider.kt`
- [x] Created example usage in `examples/ParameterizedPreview.kt` (commented out)
- [x] Added comprehensive README.md documentation
- [x] Committed to feature branch (commit: 218ebe3)

**Files Modified**:
- Created: `preview-annotations/BUILD.bazel`
- Created: `preview-annotations/src/main/kotlin/preview/annotations/PreviewParameter.kt`
- Created: `preview-annotations/src/main/kotlin/preview/annotations/PreviewParameterProvider.kt`
- Created: `examples/UserProvider.kt`
- Created: `examples/ParameterizedPreview.kt`
- Modified: `examples/BUILD.bazel`
- Created: `README.md`

---

### ⏳ Issue 2: PSI Parsing Complexity (NEXT)

**Decision**: Start with same-package resolution, add import resolution, document limitations

**What Needs to Be Done**:
1. [ ] Update `SourceAnalyzer.kt` to allow functions with parameters
2. [ ] Implement `extractProviderClass()` method
3. [ ] Add `ParameterInfo` data class to store parameter metadata
4. [ ] Extend `FunctionInfo` to include `parameters: List<ParameterInfo>`
5. [ ] Handle same-package providers
6. [ ] Handle explicit imports
7. [ ] Handle import aliases
8. [ ] Write tests for PSI parsing

**Key Files to Modify**:
- `src/main/kotlin/preview/SourceAnalyzer.kt`
  - Remove or modify `fn.valueParameters.isEmpty()` constraint at line 119
  - Add `hasPreviewParameterAnnotation()` helper
  - Add `extractProviderClass()` implementation
  - Add `extractParameterInfo()` implementation

**Implementation Guide**: See `ADDRESSING_CRITICAL_ISSUES.md` starting at line 175

**Reference Code**:
```kotlin
// Add to SourceAnalyzer.kt
data class ParameterInfo(
    val name: String,
    val type: String,              // e.g., "examples.User"
    val providerClass: String      // e.g., "examples.UserProvider"
)

// Update FunctionInfo
data class FunctionInfo(
    val name: String,
    val packageName: String,
    val jvmClassName: String,
    val containerKind: ContainerKind = ContainerKind.TOP_LEVEL,
    val parameters: List<ParameterInfo> = emptyList()  // NEW
)
```

**Tests to Write**:
- Same-package provider resolution
- Explicit import resolution
- Import alias resolution
- Error handling for unresolved providers

---

### ⏳ Issue 3: ClassLoader Lifecycle (PENDING)

**Decision**: Restructure to keep loader alive for multiple invocations per function

**What Needs to Be Done**:
1. [ ] Add `PreviewResult` data class
2. [ ] Change `PreviewRunner.invoke()` signature from `String?` to `List<PreviewResult>`
3. [ ] Implement `invokeWithLoader()` private method
4. [ ] Implement `invokeSingle()` private method
5. [ ] Use `.use {}` block to manage classloader lifecycle
6. [ ] Update callers in `Main.kt` and `PreviewServer.kt`
7. [ ] Write tests for classloader lifecycle

**Key Files to Modify**:
- `src/main/kotlin/preview/PreviewRunner.kt`
- `src/main/kotlin/Main.kt`
- `src/main/kotlin/preview/PreviewServer.kt`

**Implementation Guide**: See `ADDRESSING_CRITICAL_ISSUES.md` starting at line 333

**Reference Code**:
```kotlin
data class PreviewResult(
    val functionName: String,
    val displayName: String?,
    val result: String? = null,
    val error: String? = null
)
```

---

### ⏳ Issue 4: Fast Path Integration (PENDING)

**Decision**: Verify that fast path works naturally (likely no changes needed)

**What Needs to Be Done**:
1. [ ] Test provider in same file as preview function (watch mode)
2. [ ] Test provider in different file (watch mode)
3. [ ] Verify `PatchingClassLoader` finds both scenarios
4. [ ] Document behavior in README
5. [ ] Add tests for watch mode scenarios

**Key Files to Test**:
- `src/main/kotlin/preview/DirectCompiler.kt`
- `src/main/kotlin/preview/PatchingClassLoader.kt`
- `src/main/kotlin/preview/PreviewServer.kt`

**Implementation Guide**: See `ADDRESSING_CRITICAL_ISSUES.md` starting at line 478

**Expected Result**: Should work without modifications (validate assumption)

---

### ⏳ Issue 5: Type Resolution Gaps (PENDING)

**Decision**: Phase 1 supports simple types only (no generics, nullables, or primitives)

**What Needs to Be Done**:
1. [ ] Add validation in `SourceAnalyzer` to reject unsupported types
2. [ ] Document limitations in README
3. [ ] Add tests for type validation
4. [ ] Provide clear error messages for unsupported types

**Key Files to Modify**:
- `src/main/kotlin/preview/SourceAnalyzer.kt` (add type validation)
- `README.md` (document limitations)

**Implementation Guide**: See `ADDRESSING_CRITICAL_ISSUES.md` starting at line 567

**Supported Types (Phase 1)**:
- ✅ Simple classes: `User`, `Order`
- ✅ Enums: `Status`, `Theme`
- ✅ Interfaces: `Renderer`

**Not Supported (Phase 1)**:
- ❌ Generics: `List<User>`
- ❌ Nullables: `User?`
- ❌ Primitives: `Int`, `Boolean`
- ❌ Type aliases
- ❌ Inner classes

---

### ⏳ Issue 6: Combinatorial Explosion Protection (PENDING)

**Decision**: Hard limit of 100 results per function, soft warning at 20

**What Needs to Be Done**:
1. [ ] Add limit enforcement in `PreviewRunner`
2. [ ] Add warning logs for large preview counts (>20)
3. [ ] Reject multi-parameter in Phase 1 (single parameter only)
4. [ ] Document limits in README
5. [ ] Add tests for limit enforcement

**Key Files to Modify**:
- `src/main/kotlin/preview/PreviewRunner.kt`
- `README.md`

**Implementation Guide**: See `ADDRESSING_CRITICAL_ISSUES.md` starting at line 665

**Limits**:
- Hard limit: 100 results per function (fail fast)
- Soft warning: 20 results (log warning)
- Phase 1: Single parameter only (multi-parameter in Phase 3)

---

## Phase 1: Minimal Single Parameter (PENDING)

After resolving all critical issues, implement the minimal working version:

### What Needs to Be Done:
1. [ ] Combine all Issue 2-6 changes
2. [ ] Implement provider instantiation in `PreviewRunner`
3. [ ] Iterate through provider values
4. [ ] Invoke preview function once per value
5. [ ] Update JSON output format
6. [ ] Handle both class and object providers
7. [ ] Write comprehensive integration tests

**Deliverable**: Working single-parameter providers with auto-generated display names

**Files to Modify**:
- `src/main/kotlin/preview/SourceAnalyzer.kt` (Issues 2, 5)
- `src/main/kotlin/preview/PreviewRunner.kt` (Issues 3, 6)
- `src/main/kotlin/Main.kt` (Issue 3)
- `src/main/kotlin/preview/PreviewServer.kt` (Issues 3, 4)
- `README.md` (document usage)
- `examples/ParameterizedPreview.kt` (uncomment example)

---

## How to Continue

### For Next Agent/Session:

1. **Read these documents first**:
   - `IMPLEMENTATION_STATUS.md` (this file) - Current progress
   - `ADDRESSING_CRITICAL_ISSUES.md` - Step-by-step guide for each issue
   - `REVIEW_FINDINGS.md` - Context on why decisions were made
   - `README.md` - Current state of the project

2. **Check current branch**:
   ```bash
   git checkout feature/preview-parameter-provider
   git log --oneline -5
   ```

3. **Start with Issue 2** (PSI Parsing):
   - Read `ADDRESSING_CRITICAL_ISSUES.md` lines 175-332
   - Open `src/main/kotlin/preview/SourceAnalyzer.kt`
   - Follow the step-by-step implementation guide

4. **Verify builds still work**:
   ```bash
   bazel build //...
   bazel test //...
   ```

5. **After completing each issue**:
   - Update this file's checkboxes
   - Commit changes with descriptive message
   - Run tests to verify

### Quick Commands

```bash
# Build everything
bazel build //...

# Run tests
bazel test //...

# Build specific targets
bazel build //preview-annotations:preview-annotations
bazel build //src/main/kotlin/preview:preview-tool
bazel build //examples:examples

# Run preview tool on examples
bazel run //src/main/kotlin/preview:preview-tool examples/Greeter.kt
```

---

## Testing Strategy

After each issue is resolved:

1. **Unit tests**: Test the specific functionality in isolation
2. **Integration tests**: Test end-to-end with example providers
3. **Manual testing**: Run preview tool on examples
4. **Watch mode testing**: Verify fast path works (Issue 4)

---

## Success Criteria for Phase 1

Phase 1 is complete when:

- [x] Issue 1: Annotations module exists and builds
- [ ] Issue 2: PSI parsing extracts provider classes
- [ ] Issue 3: Multiple invocations work per function
- [ ] Issue 4: Watch mode works with providers
- [ ] Issue 5: Type validation rejects unsupported types
- [ ] Issue 6: Limits are enforced
- [ ] Single-parameter preview functions work end-to-end
- [ ] Examples can be uncommented and run successfully
- [ ] All tests pass
- [ ] Documentation is updated

---

## Resources

- **Proposal**: `PREVIEW_PROVIDER_PROPOSAL.md`
- **Examples**: `PREVIEW_PROVIDER_EXAMPLES.md`
- **Review**: `REVIEW_FINDINGS.md`
- **Implementation Guide**: `ADDRESSING_CRITICAL_ISSUES.md`
- **Current Code**:
  - Annotations: `preview-annotations/src/main/kotlin/preview/annotations/`
  - Source Analyzer: `src/main/kotlin/preview/SourceAnalyzer.kt`
  - Preview Runner: `src/main/kotlin/preview/PreviewRunner.kt`
  - Examples: `examples/`

---

## Notes

- **Minimal API First**: Phase 1 intentionally omits display names and limit parameters
- **No Multi-Parameter Yet**: Phase 1 supports only single parameter per function
- **Simple Types Only**: Generics, nullables, and primitives come later
- **Test Thoroughly**: Each issue has specific test requirements in the guide
