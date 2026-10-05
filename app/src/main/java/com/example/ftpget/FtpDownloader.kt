package com.example.ftpget // ★ 注意：保持你真实的包名 ★

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.zip.GZIPInputStream

object FtpDownloader {

    private fun validGzip(file: File): Boolean {
        if (!file.isFile || file.length() < 18L) return false
        return try {
            val hasHeader = FileInputStream(file).use { it.read() == 0x1f && it.read() == 0x8b }
            if (!hasHeader) return false
            GZIPInputStream(FileInputStream(file)).use { input ->
                val buffer = ByteArray(8192)
                while (input.read(buffer) != -1) { /* CRC is checked at EOF */ }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Content validation for already-selected local input files, not a download cache. */
    internal fun reusableLocalFile(file: File, remoteSize: Long? = null): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (remoteSize != null && remoteSize > 0L && file.length() != remoteSize) return false
        if (file.name.endsWith(".gz", ignoreCase = true)) {
            return validGzip(file)
        }
        return remoteSize == null || remoteSize > 0L
    }

    /** Publish a verified .part file even when an older incomplete target exists. */
    internal fun publishDownload(temporary: File, destination: File) {
        // ATOMIC_MOVE is not consistently supported by Android's emulated
        // external storage. Both files live in the same app-owned directory.
        Files.move(temporary.toPath(), destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING)
    }

    /** The 5x5 VMF3 forecast is a global grid, not merely a nonempty text file. */
    internal fun reusableVmf3File(file: File, year: Int, month: Int, day: Int, hour: Int): Boolean {
        if (!reusableLocalFile(file) || file.length() < 100_000L) return false
        val epoch = String.format(Locale.US, "%04d %02d %02d %02d", year, month, day, hour)
        return try {
            file.bufferedReader().use { reader ->
                var epochSeen = false
                var gridRows = 0
                reader.forEachLine { line ->
                    if (line.startsWith("! Epoch:") && line.contains(epoch)) epochSeen = true
                    if (line.isNotBlank() && !line.startsWith("!")) gridRows++
                }
                epochSeen && gridRows >= 2_592
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 文件级进度，total 为 -1 表示服务器未返回文件大小。 */
    private class ProgressOutputStream(
        output: OutputStream,
        private val fileName: String,
        private val total: Long,
        private val callback: (String, Long, Long) -> Unit
    ) : FilterOutputStream(output) {
        var bytesWritten = 0L
            private set
        private var lastReportNanos = 0L

        init { callback(fileName, 0L, total) }

        override fun write(value: Int) {
            out.write(value)
            report(1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            report(length)
        }

        private fun report(length: Int) {
            bytesWritten += length
            val now = System.nanoTime()
            if (now - lastReportNanos >= 100_000_000L || (total > 0 && bytesWritten >= total)) {
                callback(fileName, bytesWritten, total)
                lastReportNanos = now
            }
        }

        fun finish() = callback(fileName, bytesWritten, total)
    }

    private fun retrieveWithProgress(
        ftp: FTPClient,
        remoteName: String,
        destination: File,
        total: Long,
        callback: (String, Long, Long) -> Unit
    ): Boolean {
        val temporaryFile = File(destination.parentFile, "${destination.name}.part")
        try {
            val progress = ProgressOutputStream(
                FileOutputStream(temporaryFile), remoteName, total, callback
            )
            val success = progress.use { ftp.retrieveFile(remoteName, it) }
            if (!success || temporaryFile.length() == 0L ||
                (total > 0L && temporaryFile.length() != total)) return false
            if (destination.name.endsWith(".gz", ignoreCase = true) &&
                !validGzip(temporaryFile)) return false
            publishDownload(temporaryFile, destination)
            progress.finish()
            return true
        } finally {
            temporaryFile.delete()
        }
    }

    private fun copyHttpWithProgress(
        connection: HttpURLConnection,
        destination: File,
        fileName: String,
        callback: (String, Long, Long) -> Unit
    ) {
        val total = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
        val progress = ProgressOutputStream(FileOutputStream(destination), fileName, total, callback)
        connection.inputStream.use { input -> progress.use { input.copyTo(it) } }
        if (total > 0L && destination.length() != total) {
            throw java.io.IOException("文件大小不匹配：${destination.length()} / $total")
        }
        progress.finish()
    }

    // ==========================================
    // 引擎 1：下载 WHU MGEX NRT 精密轨道与钟差。
    // 文件位于 /pub/gnss/products/mgex/<GPS周>，例如：
    // WUM0MGXNRT_20262641200_02D_05M_CLK.CLK[.gz]
    // ==========================================
    suspend fun downloadWhuNrtProduct(
        server: String,
        remoteDir: String,
        productStartDate: String,
        productSuffix: String,
        savePath: File,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
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

            val matchedFiles = files.filter { file ->
                val name = file.name.uppercase(Locale.US)
                file.isFile &&
                    name.startsWith("WUM0MGXNRT_${productStartDate}") &&
                    (name.endsWith("_${productSuffix.uppercase(Locale.US)}") ||
                     name.endsWith("_${productSuffix.uppercase(Locale.US)}.GZ"))
            }
            if (matchedFiles.isEmpty()) {
                onProgress("⚠️ 未找到 WHU NRT 产品: WUM0MGXNRT_${productStartDate}*_${productSuffix}")
                return@withContext false
            }

            val targetFile = matchedFiles.maxByOrNull { it.name }!!
            val localFile = File(savePath, targetFile.name)
            onProgress("⬇️ 开始下载最新高精资产: ${targetFile.name} ...")
            val success = retrieveWithProgress(
                ftp, targetFile.name, localFile, targetFile.size, onTransferProgress
            )

            if (success) {
                onProgress("🎯 资产下载成功: ${targetFile.name}")
                return@withContext true
            } else {
                onProgress("❌ 下载受损，清理碎片: ${targetFile.name}")
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

    /* 保留旧的通用下载器，当前用于 phasebias 目录的 OSB 文件。 */
    suspend fun downloadLatestProduct(
        server: String,
        remoteDir: String,
        yearStr: String,
        doyStr: String,
        keyword: String,
        savePath: File,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val ftp = FTPClient()
        try {
            ftp.connectTimeout = 15000
            ftp.setDataTimeout(15000)
            ftp.connect(server, 21)
            if (!ftp.login("anonymous", "guest@")) return@withContext false
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            if (!ftp.changeWorkingDirectory(remoteDir)) return@withContext false

            val targetDate = "$yearStr$doyStr"
            val targetFile = ftp.listFiles()
                ?.filter { file ->
                    file.isFile && file.name.contains(targetDate) &&
                        file.name.contains(keyword, ignoreCase = true) &&
                        file.name.endsWith(".gz", ignoreCase = true)
                }
                ?.maxByOrNull { it.name }
                ?: return@withContext false

            val localFile = File(savePath, targetFile.name)
            onProgress("⬇️ 开始下载偏差产品: ${targetFile.name} ...")
            val success = retrieveWithProgress(
                ftp, targetFile.name, localFile, targetFile.size, onTransferProgress
            )
            success
        } catch (e: Exception) {
            onProgress("❌ 偏差产品下载异常: ${e.message}")
            false
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (_: Exception) { }
        }
    }

    // ==========================================
    // 引擎 2：跨国直连获取最新广播星历 (NAV)
    // ==========================================
    suspend fun downloadNavProduct(
        server: String,
        remoteDir: String,
        savePath: File,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
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
            onProgress("⬇️ 开始拉取: ${targetFile.name} ...")
            return@withContext retrieveWithProgress(
                ftp, targetFile.name, localFile, targetFile.size, onTransferProgress
            )
        } catch (e: Exception) {
            return@withContext false
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (e: Exception) { }
        }
    }

    // ATX is shipped as an app asset and copied to GNSS_Products once.
    suspend fun downloadAtxProduct(
        savePath: File,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFileName = "igs20.atx"
        val localTargetFile = File(savePath, targetFileName)
        if (BundledProducts.ready(localTargetFile)) {
            onTransferProgress(targetFileName, localTargetFile.length(), localTargetFile.length())
            onProgress("✅ 使用本地天线文件，无需下载: $targetFileName")
            return@withContext true
        }
        onProgress("❌ 本地缺少完整 $targetFileName；请检查 App 内置文件复制结果。")
        false
    }

    // ==========================================
    // 引擎 4：HTTPS 获取 CODE 预报电离层文件
    // AIUB 已迁移至 CODE/IONO/PRD，预测版本依次为 P0D 至 P4D。
    // ==========================================
    suspend fun downloadIonexProduct(
        year: Int,
        month: Int,
        day: Int,
        savePath: File,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. 将常规公历日期转换为年积日 (DOY)
            val calendar = Calendar.getInstance(Locale.US).apply {
                clear()
                set(year, month - 1, day) // Calendar 月份从 0 开始
            }
            val doy = calendar.get(Calendar.DAY_OF_YEAR)
            val formattedDoy = String.format(Locale.US, "%03d", doy)

            // P0D 为最近的预测；若服务器尚未发布，按预测时效回退至 P4D。
            for (forecastDay in 0..4) {
                val productId = "COD0OPSP${forecastDay}D"
                val fileName = String.format(
                    Locale.US,
                    "%s_%04d%s0000_01D_01H_GIM.INX.gz",
                    productId,
                    year,
                    formattedDoy
                )
                val localFile = File(savePath, fileName)

                val temporaryFile = File(savePath, "$fileName.part")
                if (temporaryFile.exists()) temporaryFile.delete()

                val fileUrl = "https://www.aiub.unibe.ch/download/CODE/IONO/PRD/$fileName"
                onProgress("🌐 正在尝试 CODE $productId 电离层预报: $fileName ...")

                val connection = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }

                try {
                    val responseCode = connection.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        onProgress("⚠️ CODE $productId 暂不可用（HTTP $responseCode），尝试下一版本...")
                        continue
                    }

                    copyHttpWithProgress(connection, temporaryFile, fileName, onTransferProgress)

                    if (!validGzip(temporaryFile)) {
                        temporaryFile.delete()
                        onProgress("⚠️ CODE $productId gzip 校验失败，尝试下一版本...")
                        continue
                    }
                    publishDownload(temporaryFile, localFile)

                    onProgress("🎯 电离层预报下载成功（$productId）: $fileName")
                    return@withContext true
                } catch (e: Exception) {
                    temporaryFile.delete()
                    onProgress("⚠️ CODE $productId 访问异常（${e.message}），尝试下一版本...")
                } finally {
                    connection.disconnect()
                }
            }

            onProgress("❌ CODE P0D 至 P4D 均未提供 $year 年第 $formattedDoy 天的电离层文件。")
            return@withContext false

        } catch (e: Exception) {
            onProgress("❌ 构建电离层路径异常: ${e.message}")
            return@withContext false
        }
    }

    // ==========================================
    // 引擎 5：HTTPS + Basic Auth 获取 VMF3 预报对流层格网
    // 文件格式：VMF3_yyyyMMdd.H00 / H06 / H12 / H18
    // ==========================================
    suspend fun downloadVmf3ForecastProduct(
        year: Int,
        month: Int,
        day: Int,
        username: String,
        password: String,
        savePath: File,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val date = String.format(Locale.US, "%04d%02d%02d", year, month, day)
        val hours = listOf(0, 6, 12, 18)
        if (username.isBlank() || password.isBlank()) {
            onProgress("❌ 缺少 VMF3 文件，且用户名或密码为空。")
            return@withContext false
        }
        val remoteDir =
            "https://vmf.geo.tuwien.ac.at/trop_products/GRID/5x5/VMF3/VMF3_FC/$year"
        val authorization = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.ISO_8859_1)
        )

        try {
            for (hour in hours) {
                val fileName = "VMF3_${date}.H%02d".format(Locale.US, hour)
                val localFile = File(savePath, fileName)

                val temporaryFile = File(savePath, "$fileName.part")
                if (temporaryFile.exists()) temporaryFile.delete()

                onProgress("⬇️ 正在下载 VMF3 预报: $fileName ...")
                val connection = (URL("$remoteDir/$fileName").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("Authorization", "Basic $authorization")
                }

                try {
                    when (val responseCode = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            copyHttpWithProgress(connection, temporaryFile, fileName, onTransferProgress)

                            if (!reusableVmf3File(temporaryFile, year, month, day, hour)) {
                                temporaryFile.delete()
                                onProgress("❌ VMF3 文件内容校验失败: $fileName")
                                return@withContext false
                            }
                            publishDownload(temporaryFile, localFile)
                            onProgress("🎯 VMF3 预报下载成功: $fileName")
                        }

                        HttpURLConnection.HTTP_UNAUTHORIZED -> {
                            onProgress("❌ VMF3 认证失败（HTTP 401），请检查用户名和密码。")
                            return@withContext false
                        }

                        HttpURLConnection.HTTP_NOT_FOUND -> {
                            onProgress("⚠️ VMF3 暂无该时次预报（HTTP 404）: $fileName")
                            return@withContext false
                        }

                        else -> {
                            onProgress("❌ VMF3 下载失败（HTTP $responseCode）: $fileName")
                            return@withContext false
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

            true
        } catch (e: Exception) {
            onProgress("❌ VMF3 下载异常: ${e.message}")
            false
        }
    }

    // VMF3 格网高度是静态表，随 App 安装，不从网络重复获取。
    suspend fun downloadVmf3Orography(
        savePath: File,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val fileName = "orography_ell_5x5"
        val localFile = File(savePath, fileName)
        if (BundledProducts.ready(localFile)) {
            onTransferProgress(fileName, localFile.length(), localFile.length())
            onProgress("✅ 使用本地格网高程，无需下载: $fileName")
            return@withContext true
        }
        onProgress("❌ 本地缺少完整 $fileName；请检查 App 内置文件复制结果。")
        false
    }
}
