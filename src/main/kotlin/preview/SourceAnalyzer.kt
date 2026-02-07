package preview

import java.io.File

data class FunctionInfo(
    val name: String,
    val packageName: String,
    val jvmClassName: String,
)

object SourceAnalyzer {
    private val EXCLUDED_MODIFIERS = setOf("private", "suspend")

    fun findTopLevelFunctions(filePath: String): List<FunctionInfo> {
        val file = File(filePath)
        return findTopLevelFunctionsFromContent(file.readText(), file.name)
    }

    fun findTopLevelFunctionsFromContent(content: String, fileName: String): List<FunctionInfo> {
        val packageRegex = Regex("""^package\s+(\S+)""", RegexOption.MULTILINE)
        val packageName = packageRegex.find(content)?.groupValues?.get(1) ?: ""

        // Match zero-arg functions at column 0 with optional modifiers.
        // Group 1 = modifier prefix (may be empty), Group 2 = function name.
        // \(\s*\) ensures only zero-arg functions match (C3).
        val funRegex = Regex("""^((?:\w+\s+)*)fun\s+(\w+)\s*\(\s*\)""", RegexOption.MULTILINE)
        val baseName = fileName.removeSuffix(".kt")
        val jvmClassName = if (packageName.isEmpty()) "${baseName}Kt" else "$packageName.${baseName}Kt"

        return funRegex.findAll(content)
            .filter { match ->
                val modifiers = match.groupValues[1].trim().split(Regex("""\s+""")).toSet()
                modifiers.none { it in EXCLUDED_MODIFIERS }
            }
            .map { match ->
                FunctionInfo(
                    name = match.groupValues[2],
                    packageName = packageName,
                    jvmClassName = jvmClassName,
                )
            }
            .toList()
    }
}
