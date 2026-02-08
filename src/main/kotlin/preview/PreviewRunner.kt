package preview

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

object PreviewRunner {
    fun invoke(classpathJars: List<String>, fn: FunctionInfo): String? {
        val urls = classpathJars.map { File(it).toURI().toURL() }.toTypedArray()
        return invoke(URLClassLoader(urls, ClassLoader.getPlatformClassLoader()), fn)
    }

    fun invoke(loader: URLClassLoader, fn: FunctionInfo): String? {
        return try {
            val clazz = loader.loadClass(fn.jvmClassName)
            val method = clazz.getMethod(fn.name)
            val result = method.invoke(null)
            result?.toString()
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        } finally {
            loader.close()
        }
    }
}
