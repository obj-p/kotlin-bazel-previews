package preview

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileWatcherTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    @Test
    fun detectsNewKotlinFile() {
        val latch = CountDownLatch(1)
        val collected = CopyOnWriteArrayList<Set<Path>>()

        val watcher = FileWatcher(tmpDir.root.toPath(), debounceMs = 50L) { paths ->
            collected.add(paths)
            latch.countDown()
        }
        watcher.use {
            it.start()
            // Give watcher time to register
            Thread.sleep(200)

            File(tmpDir.root, "Test.kt").writeText("fun test() {}")

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Should detect file change")
            assertEquals(1, collected.size)
            assertTrue(collected[0].any { p -> p.fileName.toString() == "Test.kt" })
        }
    }

    @Test
    fun ignoresNonKotlinFiles() {
        val ktLatch = CountDownLatch(1)
        val collected = CopyOnWriteArrayList<Set<Path>>()

        val watcher = FileWatcher(tmpDir.root.toPath(), debounceMs = 50L) { paths ->
            collected.add(paths)
            ktLatch.countDown()
        }
        watcher.use {
            it.start()
            Thread.sleep(200)

            // Write a non-kt file first — should be ignored
            File(tmpDir.root, "readme.txt").writeText("hello")
            File(tmpDir.root, "data.json").writeText("{}")
            Thread.sleep(200)

            // Write a kt file — should be detected
            File(tmpDir.root, "Real.kt").writeText("fun real() {}")

            assertTrue(ktLatch.await(5, TimeUnit.SECONDS), "Should detect .kt change")
            // All collected batches should only contain .kt files
            for (batch in collected) {
                for (path in batch) {
                    assertEquals("kt", path.fileName.toString().substringAfterLast('.'))
                }
            }
        }
    }

    @Test
    fun debounceCoalescesRapidChanges() {
        val latch = CountDownLatch(1)
        val collected = CopyOnWriteArrayList<Set<Path>>()

        val watcher = FileWatcher(tmpDir.root.toPath(), debounceMs = 300L) { paths ->
            collected.add(paths)
            latch.countDown()
        }
        watcher.use {
            it.start()
            Thread.sleep(200)

            // Write multiple files in rapid succession (within debounce window)
            File(tmpDir.root, "A.kt").writeText("fun a() {}")
            Thread.sleep(20)
            File(tmpDir.root, "B.kt").writeText("fun b() {}")
            Thread.sleep(20)
            File(tmpDir.root, "C.kt").writeText("fun c() {}")

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Should detect changes")
            // Should be coalesced into a single callback with multiple paths
            assertEquals(1, collected.size, "Rapid changes should coalesce into one batch")
            assertTrue(collected[0].size >= 2, "Batch should contain multiple files: ${collected[0]}")
        }
    }

    @Test
    fun closePreventsFurtherCallbacks() {
        val collected = CopyOnWriteArrayList<Set<Path>>()

        val watcher = FileWatcher(tmpDir.root.toPath(), debounceMs = 50L) { paths ->
            collected.add(paths)
        }
        watcher.start()
        Thread.sleep(200)
        watcher.close()

        // Write after close — should not trigger callback
        File(tmpDir.root, "After.kt").writeText("fun after() {}")
        Thread.sleep(500)

        assertTrue(collected.isEmpty(), "No callbacks should fire after close")
    }

    @Test
    fun startThrowsAfterClose() {
        val watcher = FileWatcher(tmpDir.root.toPath()) { }
        watcher.close()

        assertFailsWith<IllegalStateException> {
            watcher.start()
        }
    }

    @Test
    fun doubleStartThrows() {
        val watcher = FileWatcher(tmpDir.root.toPath()) { }
        watcher.use {
            it.start()
            assertFailsWith<IllegalStateException> {
                it.start()
            }
        }
    }
}
