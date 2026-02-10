package examples

import preview.annotations.Preview
import preview.annotations.PreviewParameter

/**
 * Example demonstrating parameterized previews.
 *
 * SourceAnalyzer now supports @PreviewParameter (Issue 2 complete).
 * PreviewRunner support (Issue 3) is still in progress.
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
