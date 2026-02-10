package preview

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }

    val workspaceRoot = parsed.workspaceRoot
    val filePath = parsed.filePath
    val watch = parsed.watch
    val profile = parsed.profile

    // Set system property for profiling
    if (profile) {
        System.setProperty("preview.profile", "true")
        System.err.println("[profile] Profiling enabled")
    }

    val wsDir = File(workspaceRoot)
    if (!wsDir.isDirectory) {
        System.err.println("Error: workspace root is not a directory: $workspaceRoot")
        exitProcess(1)
    }
    val sourceFile = File(wsDir, filePath)
    if (!sourceFile.isFile) {
        System.err.println("Error: source file not found: $sourceFile")
        exitProcess(1)
    }

    try {
        if (watch) {
            PreviewServer(workspaceRoot, filePath).run()
        } else {
            runOnce(workspaceRoot, filePath, sourceFile)
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}

private fun runOnce(workspaceRoot: String, filePath: String, sourceFile: File) {
    println("Finding target for ${File(filePath).name}...")
    val target = BazelRunner.findTarget(workspaceRoot, filePath)
    println("Target: $target")

    println("Building and resolving classpath for $target...")
    val classpath = BazelRunner.buildAndResolveClasspath(workspaceRoot, target)
    println("Build complete. Found ${classpath.size} classpath entries.")

    println("Analyzing $filePath...")
    val analysisResult = SourceAnalyzer().use { it.findPreviewFunctionsValidated(sourceFile.absolutePath) }

    // Check for validation errors
    when (analysisResult) {
        is AnalysisResult.ValidationError -> {
            System.err.println("Validation errors in ${analysisResult.fileName}:")
            for (issue in analysisResult.errors) {
                val location = issue.line?.let { " (line $it)" } ?: ""
                System.err.println("  ${issue.functionName}::${issue.parameterName}$location")
                System.err.println("    ${issue.message}")
                issue.suggestion?.let { System.err.println("    Suggestion: $it") }
            }
            exitProcess(1)
        }
        is AnalysisResult.Success -> {
            val functions = analysisResult.functions
            if (functions.isEmpty()) {
                println("No @Preview functions found.")
                return
            }
            println("Found ${functions.size} @Preview function${if (functions.size == 1) "" else "s"}: ${functions.joinToString { it.name }}")

            for (fn in functions) {
                println("Invoking ${fn.name}()...")
                val results = PreviewRunner.invoke(classpath, fn)

                if (results.isEmpty()) {
                    println("${fn.name}() => (no results)")
                    continue
                }

                for (previewResult in results) {
                    if (previewResult.error != null) {
                        println("${previewResult.fullDisplayName} => ERROR: ${previewResult.error}")
                    } else {
                        println("${previewResult.fullDisplayName} => ${previewResult.result}")
                    }
                }
            }
        }
    }
}
