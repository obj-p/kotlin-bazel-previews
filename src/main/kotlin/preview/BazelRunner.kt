package preview

import java.io.File
import java.util.concurrent.TimeUnit

object BazelRunner {
    private val VALID_FILENAME = Regex("""^[\w.\-]+$""")
    private const val TIMEOUT_MINUTES = 5L

    fun findTarget(workspaceRoot: String, filePath: String): String {
        val filename = File(filePath).name
        require(VALID_FILENAME.matches(filename)) {
            "Invalid filename: $filename"
        }
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

        val expr = """
            "\n".join([j.path for p in providers(target).values()
              if hasattr(p, "transitive_runtime_jars")
              for j in p.transitive_runtime_jars.to_list()])
        """.trimIndent()
        val cqueryOutput = runCommand(
            workspaceRoot,
            "bazelisk", "cquery", "--output=starlark", "--starlark:expr=$expr", target,
        )

        return cqueryOutput.lines()
            .map { it.trim() }
            .filter { it.endsWith(".jar") }
            .map { "$execRoot/$it" }
    }

    fun runCommand(workspaceRoot: String, vararg cmd: String): String {
        val process = ProcessBuilder(*cmd)
            .directory(File(workspaceRoot))
            .start()

        var stderr = ""
        val stderrThread = Thread {
            stderr = process.errorStream.bufferedReader().use { it.readText() }
        }.also { it.start() }

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        stderrThread.join()

        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException(
                "Command timed out after ${TIMEOUT_MINUTES}m: ${cmd.joinToString(" ")}"
            )
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            val stderrSuffix = if (stderr.isNotBlank()) "\n${stderr.trim()}" else ""
            throw RuntimeException(
                "Command failed (exit $exitCode): ${cmd.joinToString(" ")}$stderrSuffix"
            )
        }
        return stdout
    }
}
