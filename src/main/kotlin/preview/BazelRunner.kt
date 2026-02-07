package preview

import java.io.File

object BazelRunner {
    fun findTarget(workspaceRoot: String, filePath: String): String {
        val filename = File(filePath).name
        val query = "attr(srcs, ':$filename', //...)"
        val output = runCommand(workspaceRoot, "bazelisk", "query", query)
        val targets = output.lines().filter { it.startsWith("//") }
        if (targets.size != 1) {
            throw RuntimeException(
                "Expected exactly 1 target for $filename, found ${targets.size}: $targets"
            )
        }
        return targets[0]
    }

    fun buildAndResolveClasspath(workspaceRoot: String, target: String): List<String> {
        runCommand(workspaceRoot, "bazelisk", "build", target)

        val execRoot = runCommand(workspaceRoot, "bazelisk", "info", "execution_root").trim()

        val expr = "\"\\n\".join([j.path for p in providers(target).values() " +
            "if hasattr(p, \"transitive_runtime_jars\") " +
            "for j in p.transitive_runtime_jars.to_list()])"
        val cqueryOutput = runCommand(
            workspaceRoot,
            "bazelisk", "cquery", "--output=starlark", "--starlark:expr=$expr", target,
        )

        return cqueryOutput.lines()
            .map { it.trim() }
            .filter { it.endsWith(".jar") }
            .map { "$execRoot/$it" }
    }

    private fun runCommand(workspaceRoot: String, vararg cmd: String): String {
        val process = ProcessBuilder(*cmd)
            .directory(File(workspaceRoot))
            .start()

        val stderrThread = Thread {
            process.errorStream.bufferedReader().use { it.readText() }
        }.also { it.start() }

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        stderrThread.join()

        if (exitCode != 0) {
            throw RuntimeException("Command failed (exit $exitCode): ${cmd.joinToString(" ")}")
        }
        return stdout
    }
}
