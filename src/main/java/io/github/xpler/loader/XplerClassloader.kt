package io.github.xpler.loader

import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.xpler.HookEntrance
import java.net.URL

class XplerClassloader(
    private val host: ClassLoader,
    private val parent: ClassLoader,
) : ClassLoader() {

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        try {
            return parent.loadClass(name)
        } catch (e: ClassNotFoundException) {
            // e.printStackTrace()
        }

        try {
            return host.loadClass(name)
        } catch (e: ClassNotFoundException) {
            // e.printStackTrace()
        }

        throw ClassNotFoundException(name)
    }

    override fun getResource(name: String?): URL {
        return parent.getResource(name) ?: host.getResource(name)
    }
}

fun injectClassLoader(
    lpparam: XC_LoadPackage.LoadPackageParam,
    loader: ClassLoader,
) {
    val fParent = ClassLoader::class.java.declaredFields.first { it.name == "parent" }.apply { isAccessible = true }
    val mine = HookEntrance::class.java.classLoader
    val parent = fParent.get(mine) as ClassLoader
    if (parent::class.java != XplerClassloader::class.java) {
        hostClassloader = loader
        moduleClassloader = mine
        fParent.set(mine, XplerClassloader(loader, parent).also { lpparam.classLoader = it })
    } else {
        lpparam.classLoader = parent
    }
}

var hostClassloader: ClassLoader? = null
var moduleClassloader: ClassLoader? = null