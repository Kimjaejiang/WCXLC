package com.Johnny.wcx.loader.utils

import com.Johnny.wcx.utils.reflection.ClassLoaders

object HybridClassLoader : ClassLoader(ClassLoaders.BOOT) {

    private val bootClassLoader = ClassLoaders.BOOT
    lateinit var moduleParentClassLoader: ClassLoader
    lateinit var hostClassLoader: ClassLoader
    val additionalLoaders = mutableListOf<ClassLoader>()

    private const val PREFIX_BOOT = "BOOT."
    private const val PREFIX_MODULE = "MODULE."
    private const val PREFIX_HOST = "HOST."

    /**
     * 正在查找中的类名，用于断开类加载环。
     *
     * 为什么要这个：本对象被设成 MODULE 的 parent（UnifiedEntryPoint），
     * 而 hostClassLoader 是微信的 loader，其 parent 链里又包含 MODULE ——
     * 于是 hostClassLoader.loadClass(name) 会绕回本对象的 findClass，形成：
     *
     *   HybridClassLoader.findClass
     *     -> hostClassLoader.loadClass   (微信 loader)
     *       -> ... -> MODULE.loadClass
     *         -> MODULE.parent = HybridClassLoader.loadClass
     *           -> HybridClassLoader.findClass    <- 回到起点
     *
     * 这是死循环而非异常，runCatching / try-catch 完全拦不住，
     * 主线程 100% CPU、栈涨到数千帧，微信无法启动。
     * 用「同线程 + 同类名」重入即判定成环，直接抛 CNFE 让上面各层正常收尾。
     */
    private val loading = ThreadLocal.withInitial { HashSet<String>() }

    override fun findClass(name: String): Class<*> {
        // 环检测必须放在最前面：无论走哪条路由，只要同名类在本线程里已在查找中，
        // 就说明链回指了自己，此时再递归下去只会耗尽栈。
        val inFlight = loading.get()
        if (!inFlight.add(name)) {
            throw ClassNotFoundException("class loader cycle detected for: $name")
        }
        try {
            return doFindClass(name)
        } finally {
            inFlight.remove(name)
        }
    }

    private fun doFindClass(name: String): Class<*> {
        when {
            name.startsWith(PREFIX_BOOT) -> {
                return bootClassLoader.loadClass(name.removePrefix(PREFIX_BOOT))
            }
            name.startsWith(PREFIX_MODULE) -> {
                if (::moduleParentClassLoader.isInitialized) {
                    return moduleParentClassLoader.loadClass(name.removePrefix(PREFIX_MODULE))
                }
                throw ClassNotFoundException("Forced MODULE route failed: moduleParentClassLoader is not initialized. Class: $name")
            }
            name.startsWith(PREFIX_HOST) -> {
                if (::hostClassLoader.isInitialized) {
                    return hostClassLoader.loadClass(name.removePrefix(PREFIX_HOST))
                }
                throw ClassNotFoundException("Forced HOST route failed: hostClassLoader is not initialized. Class: $name")
            }
        }

        try {
            return bootClassLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {}

        if (::moduleParentClassLoader.isInitialized) {
            try {
                return moduleParentClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        // hostClassLoader !== this：万一配置把 host 指回自己，直接跳过，
        // 避免第一层环（环检测是兜底，这里是快速路径）。
        if (::hostClassLoader.isInitialized && hostClassLoader !== this) {
            try {
                return hostClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        additionalLoaders.forEach {
            try {
                return it.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        throw ClassNotFoundException(name)
    }
}
