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
    val functions = SourceAnalyzer().use { it.findTopLevelFunctions(sourceFile.absolutePath) }
    if (functions.isEmpty()) {
        println("No top-level functions found.")
        return
    }
    println("Found ${functions.size} top-level function${if (functions.size == 1) "" else "s"}: ${functions.joinToString { it.name }}")

    for (fn in functions) {
        println("Invoking ${fn.name}()...")
        val result = PreviewRunner.invoke(classpath, fn)
        println("${fn.name}() => $result")
    }
}
