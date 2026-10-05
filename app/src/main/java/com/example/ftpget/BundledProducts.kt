package com.example.ftpget

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Static reference tables shipped with the app, not fetched on every run. */
object BundledProducts {
    private const val assetDirectory = "gnss_products"
    private val names = listOf("igs20.atx", "orography_ell_5x5")

    fun ready(file: File): Boolean {
        if (!file.isFile) return false
        return when (file.name.lowercase()) {
            "igs20.atx" -> {
                if (file.length() < 1_000_000L) return false
                file.bufferedReader().use { reader ->
                    reader.readLine()?.contains("ANTEX VERSION / SYST") == true
                }
            }
            "orography_ell_5x5" -> file.length() > 20_000L
            else -> false
        }
    }

    /** Called on a worker thread. Existing valid user files are left untouched. */
    fun ensure(context: Context, directory: File, status: (String) -> Unit = {}) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建产品目录：${directory.absolutePath}")
        }
        for (name in names) {
            val destination = File(directory, name)
            if (ready(destination)) {
                status("本地已有静态文件，跳过复制：$name")
                continue
            }
            if (destination.exists()) {
                throw IOException("静态文件不完整，请检查或移走：${destination.absolutePath}")
            }
            val temporary = File(directory, "$name.bundled.part")
            try {
                context.assets.open("$assetDirectory/$name").use { source ->
                    FileOutputStream(temporary).use { target -> source.copyTo(target) }
                }
                if (!readyAs(temporary, name)) {
                    throw IOException("内置静态文件校验失败：$name")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("无法放置静态文件：${destination.absolutePath}")
                }
                status("已放入内置静态文件：$name")
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    private fun readyAs(file: File, name: String): Boolean {
        // The temporary suffix must not change which product format is checked.
        if (!file.isFile) return false
        return when (name) {
            "igs20.atx" -> file.length() >= 1_000_000L &&
                file.bufferedReader().use { it.readLine()?.contains("ANTEX VERSION / SYST") == true }
            "orography_ell_5x5" -> file.length() > 20_000L
            else -> false
        }
    }
}
