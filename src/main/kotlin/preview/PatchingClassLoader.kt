package preview

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

class PatchingClassLoader(
    private val patchDir: File,
    classpathUrls: List<URL>,
) : URLClassLoader(classpathUrls.toTypedArray(), ClassLoader.getPlatformClassLoader()) {
    private val canonicalPatchDir = patchDir.canonicalFile

    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val patched = resolveInPatchDir(path) ?: return super.findClass(name)
        val bytes = patched.readBytes()
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun findResource(name: String): URL? {
        val patched = resolveInPatchDir(name)
        if (patched != null) return patched.toURI().toURL()
        return super.findResource(name)
    }

    override fun findResources(name: String): Enumeration<URL> {
        val patched = resolveInPatchDir(name)
        val patchUrls = if (patched != null) listOf(patched.toURI().toURL()) else emptyList()
        val parentUrls = super.findResources(name).toList()
        return Collections.enumeration(patchUrls + parentUrls)
    }

    /** Resolves [relativePath] under [patchDir], returning null if the file doesn't exist
     *  or escapes the patch directory (e.g. via ".." segments). */
    private fun resolveInPatchDir(relativePath: String): File? {
        val candidate = File(patchDir, relativePath)
        if (!candidate.canonicalFile.startsWith(canonicalPatchDir)) return null
        if (!candidate.exists()) return null
        return candidate
    }
}
