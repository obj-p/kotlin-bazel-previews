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
    val providerClass: String,  // FQN (e.g., "examples.UserProvider")
    val limit: Int = -1,        // Max values from provider (-1 = use default 100)
    val sourceLine: Int? = null // 1-indexed line number in source file
)

/**
 * Result of analyzing a source file for preview functions.
 */
sealed class AnalysisResult {
    data class Success(val functions: List<FunctionInfo>) : AnalysisResult()
    data class ValidationError(
        val fileName: String,
        val errors: List<ValidationIssue>,
        val partialResults: List<FunctionInfo> = emptyList()
    ) : AnalysisResult()
}

/**
 * Represents a validation issue found during analysis.
 */
data class ValidationIssue(
    val parameterName: String,
    val functionName: String,
    val line: Int?,
    val message: String,
    val suggestion: String?
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

    /**
     * Find preview functions with validation.
     * Returns AnalysisResult.Success or AnalysisResult.ValidationError.
     */
    fun findPreviewFunctionsValidated(filePath: String): AnalysisResult {
        check(!disposed) { "SourceAnalyzer has been closed" }
        val file = File(filePath)
        return findPreviewFunctionsFromContentValidated(file.readText(), file.name)
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

    fun findPreviewFunctionsFromContentValidated(content: String, fileName: String): AnalysisResult {
        check(!disposed) { "SourceAnalyzer has been closed" }
        val ktFile = KtPsiFactory(psiProject, markGenerated = false).createFile(fileName, content)
        val packageName = ktFile.packageFqName.asString()
        val jvmClassName = deriveJvmClassName(ktFile, fileName, packageName)

        val results = mutableListOf<FunctionInfo>()
        val validationIssues = mutableListOf<ValidationIssue>()

        // Top-level functions
        ktFile.declarations
            .filterIsInstance<KtNamedFunction>()
            .filter { isPreviewCandidate(it) }
            .mapNotNullTo(results) { fn ->
                fn.name?.let { name ->
                    val (parameters, issues) = extractParameterInfoWithValidation(fn, packageName, ktFile)
                    validationIssues.addAll(issues)

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
            collectFromContainerValidated(classOrObject, packageName, results, validationIssues)
        }

        return if (validationIssues.isEmpty()) {
            AnalysisResult.Success(results)
        } else {
            AnalysisResult.ValidationError(fileName, validationIssues, results)
        }
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

    private fun collectFromContainerValidated(
        classOrObject: KtClassOrObject,
        packageName: String,
        results: MutableList<FunctionInfo>,
        validationIssues: MutableList<ValidationIssue>
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
                    val (parameters, issues) = extractParameterInfoWithValidation(fn, packageName, ktFile)
                    validationIssues.addAll(issues)

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
            ?.forEach { nested -> collectFromContainerValidated(nested, packageName, results, validationIssues) }
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

        // Phase 3: All parameters must have @PreviewParameter annotation
        return params.all { hasPreviewParameterAnnotation(it) }
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

        // Extract limit parameter (default to -1 if not specified)
        val limitArg = annotation.valueArguments
            .find { it.getArgumentName()?.asName?.asString() == "limit" }
            ?.getArgumentExpression()
            ?.text
            ?.toIntOrNull() ?: -1

        // Extract source line number for better error reporting
        val sourceLine = param.textRange?.startOffset?.let { offset ->
            ktFile.viewProvider.document?.getLineNumber(offset)?.plus(1)  // 1-indexed
        }

        return ParameterInfo(paramName, paramType, providerClass, limitArg, sourceLine)
    }

    /**
     * Extract parameter info with validation.
     * Returns a pair of (list of parameters, list of validation issues).
     */
    private fun extractParameterInfoWithValidation(
        fn: KtNamedFunction,
        packageName: String,
        ktFile: KtFile
    ): Pair<List<ParameterInfo>, List<ValidationIssue>> {
        val parameters = mutableListOf<ParameterInfo>()
        val issues = mutableListOf<ValidationIssue>()
        val functionName = fn.name ?: "unknown"

        for (param in fn.valueParameters) {
            val paramName = param.name ?: continue
            val paramType = param.typeReference?.text ?: continue

            val annotation = param.annotationEntries.firstOrNull {
                it.shortName?.asString() == "PreviewParameter" ||
                    it.text.contains("preview.annotations.PreviewParameter")
            } ?: continue

            val sourceLine = param.textRange?.startOffset?.let { offset ->
                ktFile.viewProvider.document?.getLineNumber(offset)?.plus(1)
            }

            val providerClass = try {
                extractProviderClass(annotation, packageName, ktFile)
            } catch (e: IllegalArgumentException) {
                issues.add(ValidationIssue(
                    parameterName = paramName,
                    functionName = functionName,
                    line = sourceLine,
                    message = "Failed to extract provider: ${e.message}",
                    suggestion = "Check that provider class is imported correctly"
                ))
                continue
            }

            // Validate provider class name
            if (!isValidClassName(providerClass)) {
                issues.add(ValidationIssue(
                    parameterName = paramName,
                    functionName = functionName,
                    line = sourceLine,
                    message = "Invalid provider class name: '$providerClass'",
                    suggestion = "Check that provider class is imported correctly"
                ))
                continue
            }

            // Extract limit parameter
            val limitArg = annotation.valueArguments
                .find { it.getArgumentName()?.asName?.asString() == "limit" }
                ?.getArgumentExpression()
                ?.text
                ?.toIntOrNull() ?: -1

            parameters.add(ParameterInfo(paramName, paramType, providerClass, limitArg, sourceLine))
        }

        return parameters to issues
    }

    /**
     * Validate that a fully-qualified class name is valid.
     */
    private fun isValidClassName(fqn: String): Boolean {
        if (fqn.isEmpty()) return false
        return fqn.split('.').all { segment ->
            segment.isNotEmpty() &&
            segment[0].isJavaIdentifierStart() &&
            segment.all { it.isJavaIdentifierPart() }
        }
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
