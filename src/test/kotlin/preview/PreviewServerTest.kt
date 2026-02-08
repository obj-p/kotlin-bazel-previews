package preview

import org.junit.Test
import kotlin.test.assertEquals

class PreviewServerTest {
    @Test
    fun jsonStringEscapesQuotes() {
        assertEquals("\"hello \\\"world\\\"\"", jsonString("hello \"world\""))
    }

    @Test
    fun jsonStringEscapesBackslash() {
        assertEquals("\"a\\\\b\"", jsonString("a\\b"))
    }

    @Test
    fun jsonStringEscapesNewlineAndTab() {
        assertEquals("\"line1\\nline2\\ttab\"", jsonString("line1\nline2\ttab"))
    }

    @Test
    fun jsonStringEscapesCarriageReturn() {
        assertEquals("\"cr\\r\"", jsonString("cr\r"))
    }

    @Test
    fun jsonStringEscapesBackspaceAndFormFeed() {
        assertEquals("\"\\b\\f\"", jsonString("\b\u000C"))
    }

    @Test
    fun jsonStringEscapesControlCharacters() {
        // U+0001 should be escaped as \u0001
        assertEquals("\"\\u0001\"", jsonString("\u0001"))
    }

    @Test
    fun jsonStringReturnsNullLiteralForNull() {
        assertEquals("null", jsonString(null))
    }

    @Test
    fun jsonStringHandlesEmptyString() {
        assertEquals("\"\"", jsonString(""))
    }

    @Test
    fun jsonStringHandlesBacktickFunctionName() {
        // Kotlin backtick-quoted function names can contain special chars
        val name = "my \"special\" fun"
        val result = jsonString(name)
        assertEquals("\"my \\\"special\\\" fun\"", result)
    }
}
