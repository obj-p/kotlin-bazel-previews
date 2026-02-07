package preview

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Usage: preview-tool <workspaceRoot> <kotlinFilePath>")
        exitProcess(1)
    }

    val workspaceRoot = args[0]
    val filePath = args[1]

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

    println("Finding target for ${File(filePath).name}...")
    val target = BazelRunner.findTarget(workspaceRoot, filePath)
    println("Target: $target")

    println("Building $target...")
    val classpath = BazelRunner.buildAndResolveClasspath(workspaceRoot, target)
    println("Build complete.")

    println("Resolving classpath...")
    println("Found ${classpath.size} classpath entries.")

    println("Analyzing $filePath...")
    val functions = SourceAnalyzer.findTopLevelFunctions(sourceFile.absolutePath)
    if (functions.isEmpty()) {
        println("No top-level functions found.")
        exitProcess(0)
    }
    println("Found ${functions.size} top-level function${if (functions.size == 1) "" else "s"}: ${functions.joinToString { it.name }}")

    for (fn in functions) {
        println("Invoking ${fn.name}()...")
        val result = PreviewRunner.invoke(classpath, fn)
        println("${fn.name}() => $result")
    }
}
