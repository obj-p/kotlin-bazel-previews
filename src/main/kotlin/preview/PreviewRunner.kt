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
            val receiver = resolveReceiver(clazz, fn.containerKind)
            val result = method.invoke(receiver)
            result?.toString()
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        } finally {
            loader.close()
        }
    }

    private fun resolveReceiver(clazz: Class<*>, kind: ContainerKind): Any? =
        when (kind) {
            ContainerKind.TOP_LEVEL -> null
            ContainerKind.OBJECT -> clazz.getDeclaredField("INSTANCE").get(null)
            ContainerKind.CLASS -> clazz.getDeclaredConstructor().newInstance()
        }
}
