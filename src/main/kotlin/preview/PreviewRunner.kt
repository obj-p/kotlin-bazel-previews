package preview

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

object PreviewRunner {
    fun invoke(classpathJars: List<String>, fn: FunctionInfo): Any? {
        val urls = classpathJars.map { File(it).toURI().toURL() }.toTypedArray()
        val loader = URLClassLoader(urls, null)
        return try {
            val clazz = loader.loadClass(fn.jvmClassName)
            val method = clazz.getMethod(fn.name)
            val result = method.invoke(null)
            // Convert to string before closing loader, since the result's class
            // may have been loaded by this classloader (R3).
            result?.toString()
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        } finally {
            loader.close()
        }
    }
}
