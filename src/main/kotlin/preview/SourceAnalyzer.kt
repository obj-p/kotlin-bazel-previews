package preview

import com.intellij.openapi.util.Disposer
import java.io.Closeable
import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory

enum class ContainerKind { TOP_LEVEL, OBJECT, CLASS }

data class ParameterInfo(
    val name: String,           // Parameter name (e.g., "user")
    val type: String,           // Parameter type (e.g., "User")
    val providerClass: String   // FQN (e.g., "examples.UserProvider")
)

data class FunctionInfo(
    val name: String,
    val packageName: String,
    val jvmClassName: String,
    val containerKind: ContainerKind = ContainerKind.TOP_LEVEL,
    val parameters: List<ParameterInfo> = emptyList()
)

class SourceAnalyzer : Closeable {
    private val disposable = Disposer.newDisposable("SourceAnalyzer")
    @Volatile private var disposed = false
    private val psiProject by lazy {
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        ).project
    }

    fun findPreviewFunctions(filePath: String): List<FunctionInfo> {
        check(!disposed) { "SourceAnalyzer has been closed" }
        val file = File(filePath)
        return findPreviewFunctionsFromContent(file.readText(), file.name)
    }

    fun findPreviewFunctionsFromContent(content: String, fileName: String): List<FunctionInfo> {
        check(!disposed) { "SourceAnalyzer has been closed" }
        val ktFile = KtPsiFactory(psiProject, markGenerated = false).createFile(fileName, content)
        val packageName = ktFile.packageFqName.asString()
        val jvmClassName = deriveJvmClassName(ktFile, fileName, packageName)

        val results = mutableListOf<FunctionInfo>()

        // Top-level functions
        ktFile.declarations
            .filterIsInstance<KtNamedFunction>()
            .filter { isPreviewCandidate(it) }
            .mapNotNullTo(results) { fn ->
                fn.name?.let { name ->
                    val parameters = fn.valueParameters
                        .mapNotNull { param -> extractParameterInfo(param, packageName, ktFile) }

                    FunctionInfo(
                        name = name,
                        packageName = packageName,
                        jvmClassName = jvmClassName,
                        containerKind = ContainerKind.TOP_LEVEL,
                        parameters = parameters
                    )
                }
            }

        // Top-level classes and objects
        for (classOrObject in ktFile.declarations.filterIsInstance<KtClassOrObject>()) {
            collectFromContainer(classOrObject, packageName, results)
        }

        return results
    }

    private fun collectFromContainer(
        classOrObject: KtClassOrObject,
        packageName: String,
        results: MutableList<FunctionInfo>,
    ) {
        val containerKind = containerKindOf(classOrObject) ?: return
        val containerJvmClassName = deriveContainerJvmClassName(classOrObject, packageName)
        val ktFile = classOrObject.containingKtFile

        // Functions directly in this container
        classOrObject.body?.declarations
            ?.filterIsInstance<KtNamedFunction>()
            ?.filter { isPreviewCandidate(it) }
            ?.mapNotNullTo(results) { fn ->
                fn.name?.let { name ->
                    val parameters = fn.valueParameters
                        .mapNotNull { param -> extractParameterInfo(param, packageName, ktFile) }

                    FunctionInfo(
                        name = name,
                        packageName = packageName,
                        jvmClassName = containerJvmClassName,
                        containerKind = containerKind,
                        parameters = parameters
                    )
                }
            }

        // One level deeper: companion objects and nested objects inside classes
        classOrObject.body?.declarations
            ?.filterIsInstance<KtClassOrObject>()
            ?.forEach { nested -> collectFromContainer(nested, packageName, results) }
    }

    private fun containerKindOf(classOrObject: KtClassOrObject): ContainerKind? {
        return when {
            classOrObject is KtObjectDeclaration -> ContainerKind.OBJECT
            classOrObject is KtClass && !classOrObject.isInterface() &&
                !classOrObject.isEnum() &&
                !classOrObject.hasModifier(KtTokens.INNER_KEYWORD) &&
                !classOrObject.hasModifier(KtTokens.ABSTRACT_KEYWORD) -> ContainerKind.CLASS
            else -> null // interfaces, enums, inner classes — skip
        }
    }

    private fun isPreviewCandidate(fn: KtNamedFunction): Boolean {
        return fn.annotationEntries.any { it.shortName?.asString() == "Preview" } &&
            !fn.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
            !fn.hasModifier(KtTokens.SUSPEND_KEYWORD) &&
            !fn.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
            hasValidParameters(fn) &&
            fn.receiverTypeReference == null &&
            fn.typeParameters.isEmpty()
    }

    private fun hasValidParameters(fn: KtNamedFunction): Boolean {
        val params = fn.valueParameters
        if (params.isEmpty()) return true  // Legacy: zero params OK
        if (params.size == 1) return hasPreviewParameterAnnotation(params[0])
        return false  // Phase 1: reject multiple params
    }

    private fun hasPreviewParameterAnnotation(param: KtParameter): Boolean {
        return param.annotationEntries.any { annotation ->
            val shortName = annotation.shortName?.asString()
            shortName == "PreviewParameter" ||
                annotation.text.contains("preview.annotations.PreviewParameter")
        }
    }

    private fun extractParameterInfo(
        param: KtParameter,
        packageName: String,
        ktFile: KtFile
    ): ParameterInfo? {
        val paramName = param.name ?: return null
        val paramType = param.typeReference?.text ?: return null

        val annotation = param.annotationEntries.firstOrNull {
            it.shortName?.asString() == "PreviewParameter" ||
                it.text.contains("preview.annotations.PreviewParameter")
        } ?: return null

        val providerClass = try {
            extractProviderClass(annotation, packageName, ktFile)
        } catch (e: IllegalArgumentException) {
            System.err.println("Warning: Failed to extract provider for '$paramName': ${e.message}")
            return null
        }

        return ParameterInfo(paramName, paramType, providerClass)
    }

    private fun extractProviderClass(
        annotation: KtAnnotationEntry,
        currentPackage: String,
        ktFile: KtFile
    ): String {
        val classLiteral = annotation.valueArguments.firstOrNull()
            ?.getArgumentExpression() as? KtClassLiteralExpression
            ?: throw IllegalArgumentException("@PreviewParameter requires provider class")

        val simpleName = classLiteral.receiverExpression?.text?.trim()
            ?: throw IllegalArgumentException("Invalid class literal")

        // Already fully-qualified?
        if (simpleName.contains(".")) return simpleName

        // Resolve from imports
        val resolved = resolveClassFromImports(simpleName, ktFile.importDirectives)
        if (resolved != null) return resolved

        // Same package fallback
        return if (currentPackage.isEmpty()) simpleName else "$currentPackage.$simpleName"
    }

    private fun resolveClassFromImports(
        simpleName: String,
        imports: List<KtImportDirective>
    ): String? {
        for (import in imports) {
            val fqName = import.importedFqName ?: continue
            if (import.isAllUnder) continue  // Skip wildcards (Phase 1 limitation)

            // Match simple name
            if (fqName.shortName().asString() == simpleName) {
                return fqName.asString()
            }

            // Match alias
            if (import.aliasName == simpleName) {
                return fqName.asString()
            }
        }
        return null
    }

    private fun deriveContainerJvmClassName(
        classOrObject: KtClassOrObject,
        packageName: String,
    ): String {
        val segments = mutableListOf<String>()
        var current: KtClassOrObject? = classOrObject
        while (current != null) {
            val name = if (current is KtObjectDeclaration && current.isCompanion() && current.name == null) {
                "Companion"
            } else {
                current.name ?: "Companion"
            }
            segments.add(0, name)
            val parent = current.parent?.parent // KtClassBody -> KtClassOrObject
            current = parent as? KtClassOrObject
        }
        val nested = segments.joinToString("$")
        return if (packageName.isEmpty()) nested else "$packageName.$nested"
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

    @Synchronized
    override fun close() {
        if (!disposed) {
            disposed = true
            Disposer.dispose(disposable)
        }
    }
}
