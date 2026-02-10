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

    /**
     * Returns a custom display name for the value at the given index.
     *
     * If this method returns null or is not implemented, the default
     * display name format "[index]" will be used.
     *
     * **Important**: If you need to access the sequence values in this method,
     * materialize the sequence to a list first to avoid O(n²) complexity:
     * ```kotlin
     * class MyProvider : PreviewParameterProvider<String> {
     *     private val items = listOf("a", "b", "c")
     *     override val values = items.asSequence()
     *     override fun getDisplayName(index: Int) = items.getOrNull(index)
     * }
     * ```
     *
     * @param index The zero-based index of the value in the sequence
     * @return A custom display name, or null to use the default format
     */
    fun getDisplayName(index: Int): String? = null
}
