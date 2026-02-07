package preview

import java.io.File
import java.net.URLClassLoader

object PreviewRunner {
    fun invoke(classpathJars: List<String>, fn: FunctionInfo): Any? {
        val urls = classpathJars.map { File(it).toURI().toURL() }.toTypedArray()
        val loader = URLClassLoader(urls, null)
        return try {
            val clazz = loader.loadClass(fn.jvmClassName)
            val method = clazz.getMethod(fn.name)
            method.invoke(null)
        } finally {
            loader.close()
        }
    }
}
