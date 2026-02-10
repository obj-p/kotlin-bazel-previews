package examples

import preview.annotations.PreviewParameter

/**
 * Example demonstrating parameterized previews.
 *
 * NOTE: This will not work yet until SourceAnalyzer is updated to support parameters.
 * Currently exists as a demonstration of the intended usage pattern.
 */
object ParameterizedPreview {

    // NOTE: Commented out until preview system supports parameters
    // @Preview
    // fun userCard(@PreviewParameter(UserPreviewParameterProvider::class) user: User): String {
    //     return """
    //         ╔════════════════════════╗
    //         ║ User Card              ║
    //         ╠════════════════════════╣
    //         ║ Name: ${user.name.padEnd(16)} ║
    //         ║ Age:  ${user.age.toString().padEnd(16)} ║
    //         ╚════════════════════════╝
    //     """.trimIndent()
    // }
}
