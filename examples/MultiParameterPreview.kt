package examples

import preview.annotations.Preview
import preview.annotations.PreviewParameter
import preview.annotations.PreviewParameterProvider

/**
 * Example data class representing a theme.
 */
data class Theme(val name: String, val primaryColor: String)

/**
 * Example provider that supplies test themes for previews.
 *
 * Demonstrates custom display names via getDisplayName().
 */
class ThemePreviewParameterProvider : PreviewParameterProvider<Theme> {
    private val themeList = listOf(
        Theme("Light", "#FFFFFF"),
        Theme("Dark", "#000000")
    )

    override val values = themeList.asSequence()

    override fun getDisplayName(index: Int): String? {
        return themeList.getOrNull(index)?.name
    }
}

/**
 * Example preview with TWO @PreviewParameter annotations.
 *
 * This generates the cartesian product of all parameter values:
 * - 3 users × 2 themes = 6 total preview variants
 *
 * Generated previews:
 * - userProfileCard[Alice Anderson, Light]
 * - userProfileCard[Alice Anderson, Dark]
 * - userProfileCard[Bob Builder, Light]
 * - userProfileCard[Bob Builder, Dark]
 * - userProfileCard[Charlie Chaplin, Light]
 * - userProfileCard[Charlie Chaplin, Dark]
 *
 * Note: User and UserPreviewParameterProvider are defined in UserProvider.kt
 */
@Preview
fun userProfileCard(
    @PreviewParameter(UserPreviewParameterProvider::class) user: User,
    @PreviewParameter(ThemePreviewParameterProvider::class) theme: Theme
): String {
    return """
        ╔══════════════════════════════════╗
        ║  Profile Card - ${theme.name} Theme
        ╠══════════════════════════════════╣
        ║  Name: ${user.name}
        ║  Age:  ${user.age}
        ║  Theme Color: ${theme.primaryColor}
        ╚══════════════════════════════════╝
    """.trimIndent()
}

/**
 * Example with THREE parameters showing extensibility.
 *
 * Simple providers without custom display names (uses indices).
 */
class FontSizeProvider : PreviewParameterProvider<Int> {
    override val values = sequenceOf(12, 16, 20)
}

class FontWeightProvider : PreviewParameterProvider<String> {
    override val values = sequenceOf("Normal", "Bold")
}

/**
 * Three-parameter preview generating 3 × 2 × 2 = 12 combinations.
 *
 * Display names use mix of custom names and indices:
 * - textSample[Alice Anderson, 0, 0]
 * - textSample[Alice Anderson, 0, 1]
 * - ...etc
 */
@Preview
fun textSample(
    @PreviewParameter(UserPreviewParameterProvider::class) user: User,
    @PreviewParameter(FontSizeProvider::class) fontSize: Int,
    @PreviewParameter(FontWeightProvider::class) fontWeight: String
): String {
    val weight = if (fontWeight == "Bold") "**" else ""
    return "$weight${user.name}$weight (${fontSize}pt)"
}
