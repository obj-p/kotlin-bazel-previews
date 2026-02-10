package examples

import preview.annotations.Preview
import preview.annotations.PreviewParameter
import preview.annotations.PreviewParameterProvider

/**
 * Provider with many values - demonstrates the new limit parameter.
 */
class LargeNumberProvider : PreviewParameterProvider<Int> {
    override val values = sequence {
        for (i in 1..50) yield(i)
    }
}

/**
 * Provider with theme values.
 */
class ThemeProvider : PreviewParameterProvider<String> {
    override val values = sequenceOf("light", "dark", "auto")
}

/**
 * Example using the limit parameter to restrict the number of values
 * taken from a provider without modifying the provider itself.
 *
 * Without the limit, this would generate 50 previews.
 * With limit=5, only the first 5 values are used.
 */
@Preview
fun limitedPreview(
    @PreviewParameter(provider = LargeNumberProvider::class, limit = 5)
    number: Int
): String {
    return "Number: $number"
}

/**
 * Example with multiple parameters using different limits.
 *
 * This generates 5 × 2 = 10 previews instead of 50 × 3 = 150.
 */
@Preview
fun multiLimitedPreview(
    @PreviewParameter(provider = LargeNumberProvider::class, limit = 5)
    number: Int,
    @PreviewParameter(provider = ThemeProvider::class, limit = 2)
    theme: String
): String {
    return "[$theme] Number: $number"
}

/**
 * Example showing that limit=-1 (or omitted) uses the default limit of 100.
 */
@Preview
fun defaultLimitPreview(
    @PreviewParameter(provider = LargeNumberProvider::class)
    number: Int
): String {
    return "Default limit: $number"
}
