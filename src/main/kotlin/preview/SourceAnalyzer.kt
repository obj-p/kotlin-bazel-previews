package preview

import java.io.File

data class FunctionInfo(
    val name: String,
    val packageName: String,
    val jvmClassName: String,
)

object SourceAnalyzer {
    fun findTopLevelFunctions(filePath: String): List<FunctionInfo> {
        val file = File(filePath)
        return findTopLevelFunctionsFromContent(file.readText(), file.name)
    }

    fun findTopLevelFunctionsFromContent(content: String, fileName: String): List<FunctionInfo> {
        val packageRegex = Regex("""^package\s+(\S+)""", RegexOption.MULTILINE)
        val packageName = packageRegex.find(content)?.groupValues?.get(1) ?: ""

        val funRegex = Regex("""^(?:\w+\s+)*fun\s+(\w+)\s*\(""", RegexOption.MULTILINE)
        val baseName = fileName.removeSuffix(".kt")
        val jvmClassName = if (packageName.isEmpty()) "${baseName}Kt" else "$packageName.${baseName}Kt"

        return funRegex.findAll(content).map { match ->
            FunctionInfo(
                name = match.groupValues[1],
                packageName = packageName,
                jvmClassName = jvmClassName,
            )
        }.toList()
    }
}
