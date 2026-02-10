package preview.annotations

/**
 * Interface for providing test values to preview functions with [@PreviewParameter][PreviewParameter].
 *
 * Implement this interface to create reusable sets of test data for previews.
 * Each value in the sequence will generate a separate preview.
 *
 * Example:
 * ```kotlin
 * class UserProvider : PreviewParameterProvider<User> {
 *     override val values = sequenceOf(
 *         User("Alice", 25),
 *         User("Bob", 35),
 *         User("Charlie", 45)
 *     )
 * }
 * ```
 *
 * Providers must have a no-argument constructor.
 *
 * @param T The type of values this provider supplies
 */
interface PreviewParameterProvider<T> {
    /**
     * A sequence of values to use for generating previews.
     *
     * Each value in the sequence will be passed to the preview function,
     * generating one preview per value.
     *
     * Note: The sequence should have a reasonable size. Very large sequences
     * (>100 values) may be truncated.
     */
    val values: Sequence<T>
}
