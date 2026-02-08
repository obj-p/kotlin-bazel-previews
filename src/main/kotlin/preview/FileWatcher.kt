package preview

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher
import java.io.Closeable
import java.nio.file.Path
import java.util.Timer
import java.util.TimerTask
import kotlin.io.path.extension

class FileWatcher(
    private val watchDir: Path,
    private val debounceMs: Long = 150L,
    private val onChange: (Set<Path>) -> Unit,
) : Closeable {
    private val pendingPaths = mutableSetOf<Path>()
    private val lock = Object()
    private val timer = Timer("file-watcher-debounce", true)
    private var pendingTask: TimerTask? = null
    private var watcher: DirectoryWatcher? = null
    @Volatile private var closed = false

    fun start() {
        check(!closed) { "FileWatcher has been closed" }
        check(watcher == null) { "FileWatcher has already been started" }
        watcher = DirectoryWatcher.builder()
            .path(watchDir)
            .listener { event ->
                if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) return@listener
                val path = event.path() ?: return@listener
                if (path.extension != "kt") return@listener
                scheduleFlush(path)
            }
            .build()
        watcher!!.watchAsync()
    }

    private fun scheduleFlush(path: Path) {
        synchronized(lock) {
            if (closed) return
            pendingPaths.add(path)
            pendingTask?.cancel()
            pendingTask = object : TimerTask() {
                override fun run() {
                    flush()
                }
            }.also { timer.schedule(it, debounceMs) }
        }
    }

    private fun flush() {
        val snapshot: Set<Path>
        synchronized(lock) {
            if (closed || pendingPaths.isEmpty()) return
            snapshot = pendingPaths.toSet()
            pendingPaths.clear()
        }
        onChange(snapshot)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            pendingTask?.cancel()
            pendingTask = null
        }
        timer.cancel()
        watcher?.close()
    }
}
