package preview

import com.intellij.openapi.util.Disposer
import java.io.Closeable
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

class SourceAnalyzer : Closeable {
    private val disposable = Disposer.newDisposable("SourceAnalyzer")
    private var disposed = false
    private val psiProject by lazy {
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        ).project
    }

    fun findTopLevelFunctions(filePath: String): List<FunctionInfo> {
        check(!disposed) { "SourceAnalyzer has been closed" }
        val file = File(filePath)
        return findTopLevelFunctionsFromContent(file.readText(), file.name)
    }

    fun findTopLevelFunctionsFromContent(content: String, fileName: String): List<FunctionInfo> {
        check(!disposed) { "SourceAnalyzer has been closed" }
        val ktFile = KtPsiFactory(psiProject, markGenerated = false).createFile(fileName, content)
        val packageName = ktFile.packageFqName.asString()
        val jvmClassName = deriveJvmClassName(ktFile, fileName, packageName)

        return ktFile.declarations
            .filterIsInstance<KtNamedFunction>()
            .filter { fn ->
                !fn.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
                    !fn.hasModifier(KtTokens.SUSPEND_KEYWORD) &&
                    // Exclude functions with any declared parameters (including those with
                    // defaults) — PreviewRunner uses Class.getMethod(name) which only
                    // matches the zero-arg JVM signature.
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

    override fun close() {
        if (!disposed) {
            disposed = true
            Disposer.dispose(disposable)
        }
    }
}
