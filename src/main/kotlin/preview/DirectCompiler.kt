package preview

import java.io.File
import java.util.Collections
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services

data class CompileResult(
    val success: Boolean,
    val diagnostics: List<Diagnostic>,
)

data class Diagnostic(
    val severity: String,
    val message: String,
    val location: String?,
)

object DirectCompiler {
    private val tmpRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile

    fun compile(
        sourceFiles: List<File>,
        classpath: List<String>,
        outputDir: File,
    ): CompileResult {
        require(outputDir.canonicalFile.startsWith(tmpRoot)) {
            "outputDir must be under java.io.tmpdir: $outputDir"
        }
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val collector = DiagnosticCollector()
        val args = K2JVMCompilerArguments().apply {
            freeArgs = sourceFiles.map { it.absolutePath }
            this.classpath = classpath.joinToString(File.pathSeparator)
            destination = outputDir.absolutePath
            noStdlib = true
            noReflect = true
        }
        val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, args)
        return CompileResult(exitCode == ExitCode.OK, collector.diagnostics)
    }

    fun warmup(classpath: List<String>, outputDir: File) {
        val warmupFile = File.createTempFile("warmup", ".kt").apply {
            writeText("fun _warmup() {}")
            deleteOnExit()
        }
        try {
            compile(listOf(warmupFile), classpath, outputDir)
        } finally {
            warmupFile.delete()
        }
    }
}

private class DiagnosticCollector : MessageCollector {
    val diagnostics: MutableList<Diagnostic> = Collections.synchronizedList(mutableListOf())
    @Volatile private var hasErrors = false

    override fun clear() {
        diagnostics.clear()
        hasErrors = false
    }

    override fun hasErrors(): Boolean = hasErrors

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (severity == CompilerMessageSeverity.ERROR) hasErrors = true
        if (severity == CompilerMessageSeverity.ERROR || severity == CompilerMessageSeverity.WARNING) {
            diagnostics.add(
                Diagnostic(
                    severity = severity.name,
                    message = message,
                    location = location?.let { "${it.path}:${it.line}:${it.column}" },
                )
            )
        }
    }
}
