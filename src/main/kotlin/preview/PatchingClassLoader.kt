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
    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val patched = File(patchDir, path)
        if (patched.exists()) {
            val bytes = patched.readBytes()
            return defineClass(name, bytes, 0, bytes.size)
        }
        return super.findClass(name)
    }

    override fun findResource(name: String): URL? {
        val patched = File(patchDir, name)
        if (patched.exists()) {
            return patched.toURI().toURL()
        }
        return super.findResource(name)
    }

    override fun findResources(name: String): Enumeration<URL> {
        val patched = File(patchDir, name)
        val patchUrls = if (patched.exists()) listOf(patched.toURI().toURL()) else emptyList()
        val parentUrls = super.findResources(name).toList()
        return Collections.enumeration(patchUrls + parentUrls)
    }
}
