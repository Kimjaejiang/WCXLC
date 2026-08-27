package com.Johnny.wcx.utils.fs

import android.os.Environment
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.utils.HostInfo
import java.io.File
import java.nio.file.Path
import kotlin.io.path.div

object KnownPaths {

    /**
     * 数据目录名固定为 WCXLC，与显示名 BuildConfig.TAG 解耦。
     * 品牌从 WCX 改 WCXLC 时目录曾跟随 TAG 漂移（.../WCX/ → .../WCXLC/），
     * 导致 chat_folders.json 等配置读不到；固定后切换品牌不影响数据目录。
     */
    private const val DATA_DIR_NAME = "WCXLC"
    private const val LEGACY_DATA_DIR_NAME = "WCX"

    val internalStorage: Path by lazy {
        Environment.getExternalStorageDirectory().asPath
    }

    val moduleData by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT) /
                DATA_DIR_NAME).createDirsSafe().also { migrateLegacyDataDir(it) }
    }

    val codeCacheDir: Path by lazy {
        HostInfo.application.codeCacheDir.asPath
    }

    val moduleCache by lazy {
        (internalStorage / "Android" / "data" /
                runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT)
                / "cache" / DATA_DIR_NAME).createDirsSafe()
    }

    val moduleAssets by lazy {
        (moduleData / "assets").createDirsSafe()
    }

    val downloads by lazy {
        (Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath() / DATA_DIR_NAME)
            .createDirsSafe()
    }

    /**
     * 启动首次访问 moduleData 时，把旧 WCX/ 目录内容合并到 WCXLC/：
     * 保留旧目录不删，已存在同名文件保留新目录版本（幂等，可重复执行）。
     */
    private fun migrateLegacyDataDir(newDir: Path) {
        runCatching {
            val base = internalStorage / "Android" / "data" /
                    runCatching { HostInfo.packageName }.getOrDefault(PackageNames.WECHAT)
            val legacy = base / LEGACY_DATA_DIR_NAME
            if (!legacy.toFile().isDirectory) return
            legacy.toFile().listFiles()?.forEach { src ->
                val dst = File(newDir.toFile(), src.name)
                if (dst.exists()) return@forEach
                if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
            }
        }
    }
}
