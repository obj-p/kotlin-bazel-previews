package preview

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // --- buildJsonOutput tests ---

    private fun fn(name: String) = FunctionInfo(name = name, packageName = "pkg", jvmClassName = "pkg.TestKt")

    @Test
    fun buildJsonOutput_emptyFunctions_returnsEmptyArray() {
        val json = buildJsonOutput(emptyList()) { emptyList() }
        assertEquals("{\"previews\":[]}", json)
    }

    @Test
    fun buildJsonOutput_singleSuccess_returnsResultJson() {
        val json = buildJsonOutput(listOf(fn("hello"))) {
            listOf(PreviewResult("hello", null, result = "world"))
        }
        assertEquals(
            "{\"previews\":[\n  {\"name\":\"hello\",\"result\":\"world\"}\n]}",
            json,
        )
    }

    @Test
    fun buildJsonOutput_singleNullResult_returnsNullInJson() {
        val json = buildJsonOutput(listOf(fn("hello"))) {
            listOf(PreviewResult("hello", null, result = null))
        }
        assertEquals(
            "{\"previews\":[\n  {\"name\":\"hello\",\"result\":null}\n]}",
            json,
        )
    }

    @Test
    fun buildJsonOutput_singleError_returnsErrorJson() {
        val json = buildJsonOutput(listOf(fn("boom"))) { throw RuntimeException("kaboom") }
        assertEquals(
            "{\"previews\":[\n  {\"name\":\"boom\",\"error\":\"kaboom\"}\n]}",
            json,
        )
    }

    @Test
    fun buildJsonOutput_multipleFunctions_mixedResults() {
        val fns = listOf(fn("ok"), fn("fail"), fn("nil"))
        val json = buildJsonOutput(fns) { fi ->
            when (fi.name) {
                "ok" -> listOf(PreviewResult("ok", null, result = "good"))
                "fail" -> throw RuntimeException("bad")
                else -> listOf(PreviewResult("nil", null, result = null))
            }
        }
        assertTrue(json.contains("\"name\":\"ok\",\"result\":\"good\""))
        assertTrue(json.contains("\"name\":\"fail\",\"error\":\"bad\""))
        assertTrue(json.contains("\"name\":\"nil\",\"result\":null"))
    }

    @Test
    fun buildJsonOutput_specialCharsInNameAndResult() {
        val fi = FunctionInfo(name = "a\"b", packageName = "", jvmClassName = "TestKt")
        val json = buildJsonOutput(listOf(fi)) {
            listOf(PreviewResult("a\"b", null, result = "line1\nline2"))
        }
        assertTrue(json.contains("\"a\\\"b\""))
        assertTrue(json.contains("\"line1\\nline2\""))
    }
}
