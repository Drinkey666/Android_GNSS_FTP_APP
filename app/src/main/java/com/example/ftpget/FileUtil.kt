package com.example.ftpget // ★ 注意：改为你真实的包名 ★

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

object FileUtil {

    /**
     * 将 .gz 压缩文件解压为普通文件
     * @param gzipFile 下载下来的压缩包 (例如: ORB.SP3.gz)
     * @param outFile  解压后的目标文件 (例如: ORB.SP3)
     * @return 解压是否成功
     */
    fun unGzip(gzipFile: File, outFile: File): Boolean {
        // 如果压缩包不存在，直接返回失败
        if (!gzipFile.exists()) return false

        return try {
            GZIPInputStream(FileInputStream(gzipFile)).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output) // 执行流拷贝解压
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}