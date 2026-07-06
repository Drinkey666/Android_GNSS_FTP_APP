package com.example.ftpget // ★ 注意：保持你真实的包名 ★

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileOutputStream

object FtpDownloader {

    // ==========================================
    // 引擎 1：智能模糊嗅探 V3 版精密轨道与钟差 (SP3 / CLK)
    // ==========================================
    suspend fun downloadLatestProduct(
        server: String,
        remoteDir: String,
        yearStr: String,
        doyStr: String,
        keyword: String,
        savePath: File,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val ftp = FTPClient()
        try {
            onProgress("🌐 正在连接: $server...")
            ftp.connectTimeout = 15000
            ftp.setDataTimeout(15000)

            ftp.connect(server, 21)
            if (!ftp.login("anonymous", "guest@")) {
                onProgress("❌ 匿名登录 FTP 失败")
                return@withContext false
            }

            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)

            if (!ftp.changeWorkingDirectory(remoteDir)) {
                onProgress("❌ 远程目录不存在: $remoteDir")
                return@withContext false
            }

            val files = ftp.listFiles()
            if (files == null || files.isEmpty()) {
                onProgress("❌ 目录为空: $remoteDir")
                return@withContext false
            }

            // 🎯 核心逻辑：移除死板的前缀匹配，改为极端包容的“模糊嗅探”
            val targetDateStr = "$yearStr$doyStr"

            val matchedFiles = files.filter { file ->
                file.isFile &&
                        file.name.contains(targetDateStr) && // 1. 必须匹配今天的 年+年积日 (如 2026142)
                        file.name.uppercase().contains(keyword.uppercase()) && // 2. 匹配关键字 SP3 或 CLK
                        file.name.endsWith(".gz", ignoreCase = true) // 3. 必须是标准压缩包
            }

            if (matchedFiles.isEmpty()) {
                onProgress("⚠️ 未找到资产: 年积日 $targetDateStr , 关键字 $keyword")
                return@withContext false
            }

            // 🎯 获取最新：按名字的字母序排个序，取最后一个！(利用 V3 命名里嵌入的 0000, 0600, 1800 自动找最新)
            val targetFile = matchedFiles.maxByOrNull { it.name }!!
            val localFile = File(savePath, targetFile.name)

            if (localFile.exists() && localFile.length() == targetFile.size) {
                onProgress("✅ 本地已存在最新资产: ${targetFile.name}")
                return@withContext true
            }

            onProgress("⬇️ 开始下载最新高精资产: ${targetFile.name} ...")
            var success = false
            FileOutputStream(localFile).use { outputStream ->
                success = ftp.retrieveFile(targetFile.name, outputStream)
            }

            if (success) {
                onProgress("🎯 资产下载成功: ${targetFile.name}")
                return@withContext true
            } else {
                onProgress("❌ 下载受损，清理碎片: ${targetFile.name}")
                localFile.delete()
                return@withContext false
            }

        } catch (e: Exception) {
            onProgress("❌ 抓取异常: ${e.message}")
            return@withContext false
        } finally {
            if (ftp.isConnected) {
                try { ftp.disconnect() } catch (e: Exception) { }
            }
        }
    }

    // ==========================================
    // 引擎 2：跨国直连获取最新广播星历 (NAV)
    // ==========================================
    suspend fun downloadNavProduct(
        server: String,
        remoteDir: String,
        savePath: File,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val ftp = FTPClient()
        try {
            onProgress("🌐 直连 BKG 服务器获取广播星历...")
            ftp.connectTimeout = 15000
            ftp.setDataTimeout(15000)
            ftp.connect(server, 21)
            ftp.login("anonymous", "guest@")
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)

            if (!ftp.changeWorkingDirectory(remoteDir)) return@withContext false

            val files = ftp.listFiles()
            val targetFile = files?.filter { it.isFile && it.name.endsWith("MN.rnx.gz", ignoreCase = true) }
                ?.maxByOrNull { it.timestamp }

            if (targetFile == null) return@withContext false

            val localFile = File(savePath, targetFile.name)
            if (localFile.exists() && localFile.length() == targetFile.size) {
                onProgress("✅ 本地已存在广播星历: ${targetFile.name}")
                return@withContext true
            }

            onProgress("⬇️ 开始拉取: ${targetFile.name} ...")
            FileOutputStream(localFile).use { outputStream ->
                return@withContext ftp.retrieveFile(targetFile.name, outputStream)
            }
        } catch (e: Exception) {
            return@withContext false
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (e: Exception) { }
        }
    }

    // ==========================================
    // 引擎 3：拉取 IGS 接收机天线改正文件 (ATX)
    // ==========================================
    suspend fun downloadAtxProduct(
        savePath: File,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFileName = "igs20.atx"
        val localTargetFile = File(savePath, targetFileName)

        if (localTargetFile.exists() && localTargetFile.length() > 0) return@withContext true

        val ftp = FTPClient()
        try {
            onProgress("👉 正在连接 BKG 获取天线文件 (ATX)...")
            ftp.connectTimeout = 15000
            ftp.setDataTimeout(15000)
            ftp.connect("igs-ftp.bkg.bund.de", 21)
            ftp.login("anonymous", "guest@")
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)

            if (!ftp.changeWorkingDirectory("/IGS/pub/station/general/")) return@withContext false

            var success = false
            FileOutputStream(localTargetFile).use { outputStream ->
                success = ftp.retrieveFile(targetFileName, outputStream)
            }
            if (!success) localTargetFile.delete()
            return@withContext success
        } catch (e: Exception) {
            return@withContext false
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (e: Exception) { }
        }
    }
}