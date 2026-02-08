package preview

import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PreviewServer(
    private val workspaceRoot: String,
    private val filePath: String,
) {
    private val wsDir = File(workspaceRoot)
    private val sourceFile = File(wsDir, filePath)
    private val scratchDir = File(System.getProperty("java.io.tmpdir"), "preview-patch-${ProcessHandle.current().pid()}")
    private val analyzer = SourceAnalyzer()
    private val closed = AtomicBoolean(false)

    // All mutable state is accessed only from this single-thread executor,
    // eliminating the need for @Volatile or locks on cachedClasspath/cachedTarget.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "preview-server").apply { isDaemon = true }
    }
    private var cachedClasspath: List<String> = emptyList()
    private var cachedTarget: String? = null

    fun run() {
        // Clean up stale scratch directory from a previous crashed run (e.g. kill -9).
        scratchDir.deleteRecursively()

        val shutdownLatch = CountDownLatch(1)
        val mainThread = Thread.currentThread()

        val shutdownHook = Thread {
            close()
            shutdownLatch.countDown()
            mainThread.interrupt()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        // Initial build and warmup run on the main thread before the FileWatcher starts,
        // so there is no concurrent access to cachedClasspath/cachedTarget yet. The
        // single-thread executor only receives tasks after watcher.start() below.
        System.err.println("[server] Initial build...")
        fullBuild()
        renderPreview()

        System.err.println("[server] Warming up compiler...")
        warmupCompiler()
        System.err.println("[server] Ready. Watching for changes...")

        // Watch the source file's parent directory (recursively), not the entire workspace
        // root, to avoid noise from bazel-out/ and other non-source directories. This means
        // changes to files in sibling packages won't trigger a rebuild — acceptable for the
        // common case of editing the previewed file itself. A full Bazel build handles the
        // rest on the slow path.
        val watchDir = sourceFile.parentFile.toPath()
        val watcher = FileWatcher(watchDir, debounceMs = 150L) { changedPaths ->
            executor.submit { onFilesChanged(changedPaths) }
        }
        watcher.start()

        try {
            shutdownLatch.await()
        } catch (_: InterruptedException) {
            // shutdown
        } finally {
            watcher.close()
            close()
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) { }
        }
    }

    private fun warmupCompiler() {
        val warmupDir = File(System.getProperty("java.io.tmpdir"), "preview-warmup-${ProcessHandle.current().pid()}")
        try {
            DirectCompiler.warmup(cachedClasspath, warmupDir)
        } finally {
            warmupDir.deleteRecursively()
        }
    }

    /** Called on the single-thread executor — no concurrent access to mutable state. */
    private fun onFilesChanged(changedPaths: Set<Path>) {
        System.err.println("[server] Changed: ${changedPaths.joinToString { it.fileName.toString() }}")

        // Compile only the previewed source file on the fast path.
        // Other changed files may not be compilable in isolation (missing deps not on
        // the cached classpath), and including them would cause the fast path to fail
        // unnecessarily.
        val result = DirectCompiler.compile(listOf(sourceFile), cachedClasspath, scratchDir)
        if (result.success) {
            System.err.println("[server] Fast compile succeeded.")
            renderPreview(usePatchDir = true)
        } else {
            for (d in result.diagnostics) {
                System.err.println("[server] ${d.severity}: ${d.message}${d.location?.let { " at $it" } ?: ""}")
            }
            System.err.println("[server] Fast compile failed, falling back to Bazel build...")
            try {
                fullBuild(invalidateTarget = true)
                renderPreview()
            } catch (e: Exception) {
                System.err.println("[server] Bazel build failed: ${e.message}")
            }
        }
    }

    private fun fullBuild(invalidateTarget: Boolean = false) {
        if (invalidateTarget) cachedTarget = null
        val target = cachedTarget ?: BazelRunner.findTarget(workspaceRoot, filePath).also { cachedTarget = it }
        cachedClasspath = BazelRunner.buildAndResolveClasspath(workspaceRoot, target)
    }

    private fun renderPreview(usePatchDir: Boolean = false) {
        val functions = analyzer.findTopLevelFunctions(sourceFile.absolutePath)
        val invoker: (FunctionInfo) -> String? = { fn ->
            if (usePatchDir) invokeFastPath(fn) else PreviewRunner.invoke(cachedClasspath, fn)
        }
        val json = buildJsonOutput(functions, invoker)
        System.out.println(json)
        System.out.flush()
    }

    private fun invokeFastPath(fn: FunctionInfo): String? {
        val urls = cachedClasspath.map { File(it).toURI().toURL() }
        val loader = PatchingClassLoader(scratchDir, urls)
        return try {
            val clazz = loader.loadClass(fn.jvmClassName)
            val method = clazz.getMethod(fn.name)
            val result = method.invoke(null)
            // Convert to string before closing the classloader — the result object's class
            // may have been loaded by this classloader, so toString() must complete first.
            result?.toString()
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        } finally {
            loader.close()
        }
    }

    private fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
        analyzer.close()
        scratchDir.deleteRecursively()
    }
}

fun buildJsonOutput(
    functions: List<FunctionInfo>,
    invoker: (FunctionInfo) -> String?,
): String {
    if (functions.isEmpty()) return "{\"functions\":[]}"

    val results = mutableListOf<String>()
    for (fn in functions) {
        try {
            val result = invoker(fn)
            results.add("  {\"name\":${jsonString(fn.name)},\"result\":${jsonString(result)}}")
        } catch (e: Exception) {
            results.add("  {\"name\":${jsonString(fn.name)},\"error\":${jsonString(e.message)}}")
        }
    }
    return "{\"functions\":[\n${results.joinToString(",\n")}\n]}"
}

fun jsonString(value: String?): String {
    if (value == null) return "null"
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code in 0x00..0x1F) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append('"')
    return sb.toString()
}
