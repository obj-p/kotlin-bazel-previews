package examples

import preview.annotations.Preview
import preview.annotations.PreviewParameter

/**
 * Example demonstrating parameterized previews with custom display names.
 *
 * This example shows:
 * - Single @PreviewParameter annotation
 * - Custom display names via getDisplayName()
 * - Efficient O(n) implementation using materialized list
 */
object ParameterizedPreview {

    @Preview
    fun userCard(@PreviewParameter(UserPreviewParameterProvider::class) user: User): String {
        return """
            ╔════════════════════════╗
            ║ User Card              ║
            ╠════════════════════════╣
            ║ Name: ${user.name.padEnd(16)} ║
            ║ Age:  ${user.age.toString().padEnd(16)} ║
            ╚════════════════════════╝
        """.trimIndent()
    }
}
