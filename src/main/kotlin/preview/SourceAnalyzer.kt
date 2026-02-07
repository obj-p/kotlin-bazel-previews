package preview

import com.intellij.openapi.util.Disposer
import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

data class FunctionInfo(
    val name: String,
    val packageName: String,
    val jvmClassName: String,
)

object SourceAnalyzer {
    // Lazy-init PSI environment. Never disposed — acceptable for CLI/test JVM
    // processes that exit after use. For long-lived processes, call
    // Disposer.dispose(disposable) on shutdown.
    private val disposable = Disposer.newDisposable("SourceAnalyzer")
    private val psiProject by lazy {
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        ).project
    }

    fun findTopLevelFunctions(filePath: String): List<FunctionInfo> {
        val file = File(filePath)
        return findTopLevelFunctionsFromContent(file.readText(), file.name)
    }

    fun findTopLevelFunctionsFromContent(content: String, fileName: String): List<FunctionInfo> {
        val ktFile = KtPsiFactory(psiProject, markGenerated = false).createFile(fileName, content)
        val packageName = ktFile.packageFqName.asString()
        val jvmClassName = deriveJvmClassName(ktFile, fileName, packageName)

        return ktFile.declarations
            .filterIsInstance<KtNamedFunction>()
            .filter { fn ->
                !fn.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
                    !fn.hasModifier(KtTokens.SUSPEND_KEYWORD) &&
                    fn.valueParameters.isEmpty() &&
                    fn.receiverTypeReference == null &&
                    fn.typeParameters.isEmpty()
            }
            .mapNotNull { fn ->
                fn.name?.let { name ->
                    FunctionInfo(name = name, packageName = packageName, jvmClassName = jvmClassName)
                }
            }
    }

    // Handles standard string literal arguments only (e.g. @file:JvmName("Name")).
    // Does not handle raw strings or concatenation — acceptable for real-world usage.
    private fun deriveJvmClassName(ktFile: KtFile, fileName: String, packageName: String): String {
        val jvmName = ktFile.fileAnnotationList?.annotationEntries
            ?.firstOrNull { it.shortName?.asString() == "JvmName" }
            ?.valueArguments?.firstOrNull()
            ?.getArgumentExpression()?.text?.removeSurrounding("\"")
        val baseName = jvmName ?: (fileName.removeSuffix(".kt") + "Kt")
        return if (packageName.isEmpty()) baseName else "$packageName.$baseName"
    }
}
