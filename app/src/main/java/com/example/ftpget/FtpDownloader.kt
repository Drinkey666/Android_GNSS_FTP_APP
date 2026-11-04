package com.example.ftpget // ★ 注意：保持你真实的包名 ★

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
        callback: (String, Long, Long) -> Unit,
        validate: (File) -> Boolean = { true }
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
            if (!validate(temporaryFile)) return false
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
        // 先进入输出流use；连inputStream打开都超时时也必须关闭.part句柄。
        progress.use { output -> connection.inputStream.use { input -> input.copyTo(output) } }
        if (total > 0L && destination.length() != total) {
            throw java.io.IOException("文件大小不匹配：${destination.length()} / $total")
        }
        progress.finish()
    }

    /** 同一URL网络失败时先重试，不把timeout误当成“该日期/版本没有产品”。 */
    internal suspend fun downloadHttpFile(
        url: String,
        destination: File,
        label: String,
        connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        authorization: String? = null,
        validate: (File) -> Boolean,
        onTransferProgress: (String, Long, Long) -> Unit,
        onProgress: (String) -> Unit
    ): Int = DownloadNetworkPolicy.retry("$label ${destination.name}", onProgress) {
        val temporary = File(destination.parentFile, "${destination.name}.part")
        val connection = connectionFactory(URL(url))
        try {
            DownloadNetworkPolicy.configure(connection)
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            val status = connection.responseCode
            DownloadNetworkPolicy.checkHttpStatus(status)
            if (status != HttpURLConnection.HTTP_OK) return@retry status
            copyHttpWithProgress(connection, temporary, destination.name, onTransferProgress)
            if (!validate(temporary)) {
                onProgress("⚠️ $label 内容校验失败：${destination.name}；保留原完整文件")
                return@retry -1
            }
            publishDownload(temporary, destination)
            status
        } finally {
            try { connection.disconnect() } finally { temporary.delete() }
        }
    }

    // ==========================================
    // 引擎 1：下载 WHU MGEX NRT 精密轨道与钟差。
    // 文件位于 /pub/gnss/products/mgex/<GPS周>，例如：
    // WUM0MGXNRT_20262641200_02D_05M_CLK.CLK[.gz]
    // ==========================================
    suspend fun downloadLatestWhuNrtPair(
        server: String,
        savePath: File,
        referenceUtcMillis: Long = System.currentTimeMillis(),
        clientFactory: () -> FTPClient = { FTPClient() },
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onPairDirectory: (String) -> Unit = {},
        onProgress: (String) -> Unit
    ): Boolean = DownloadNetworkPolicy.download("NRT轨道/钟差 FTP $server", onProgress) {
      withContext(Dispatchers.IO) {
        val ftp = clientFactory()
        try {
            DownloadNetworkPolicy.configure(ftp)
            onProgress("🌐 NRT FTP连接/登录：$server")
            ftp.connect(server, 21)
            if (!ftp.login("anonymous", "guest@")) {
                onProgress("❌ WHU 匿名登录失败")
                return@withContext false
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            val files = linkedMapOf<String, org.apache.commons.net.ftp.FTPFile>()
            for (dir in NrtProductPolicy.weekDirectories(referenceUtcMillis)) {
                onProgress("🔎 检索最新 NRT 配套产品：$dir")
                if (!ftp.changeWorkingDirectory(dir)) {
                    onProgress("⚠️ WHU 目录尚不可用：$dir（${ftp.replyCode}）")
                    continue
                }
                onProgress("📂 NRT读取目录：$dir（数据空闲超时${DownloadNetworkPolicy.READ_MS/1000}s）")
                for (file in ftp.listFiles().orEmpty()) {
                    if (file.isFile && NrtProductPolicy.parse(file.name) != null) files[file.name] = file
                }
            }
            val pairs = NrtProductPolicy.pairs(files.keys, referenceUtcMillis)
            for (pair in pairs) {
                val week = NrtProductPolicy.weekDirectories(pair.start).first()
                if (!ftp.changeWorkingDirectory(week)) continue
                onProgress("⬇️ 下载同一时次 NRT：${pair.orbit} + ${pair.clock}")
                onProgress("⬇️ NRT读取文件：${pair.orbit}")
                val sp3 = retrieveWithProgress(ftp, pair.orbit, File(savePath, pair.orbit),
                    files.getValue(pair.orbit).size, onTransferProgress)
                if (sp3) onProgress("⬇️ NRT读取文件：${pair.clock}")
                val clk = sp3 && retrieveWithProgress(ftp, pair.clock, File(savePath, pair.clock),
                    files.getValue(pair.clock).size, onTransferProgress)
                if (sp3 && clk) {
                    onPairDirectory(week)
                    onProgress("🎯 最新可用 NRT 轨道与钟差下载完成；标称时段覆盖本次下载时刻")
                    return@withContext true
                }
                onProgress("⚠️ 该时次传输失败，尝试下一组仍覆盖当前时间的配套产品")
            }
            onProgress("❌ 未找到覆盖当前时间的完整 NRT SP3/CLK；不把过期文件冒充实时产品")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (DownloadNetworkPolicy.isTransient(e)) throw e
            onProgress("❌ NRT 配套产品下载失败：${e.message}")
            false
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (_: Exception) { }
        }
      }
    }

    suspend fun downloadWhuNrtProduct(
        server: String,
        remoteDir: String,
        productStartDate: String,
        productSuffix: String,
        savePath: File,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = DownloadNetworkPolicy.download("NRT $productSuffix FTP $server", onProgress) {
      withContext(Dispatchers.IO) {
        val ftp = FTPClient()
        try {
            onProgress("🌐 正在连接: $server...")
            DownloadNetworkPolicy.configure(ftp)

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

            onProgress("📂 NRT读取目录：$remoteDir")
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

        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (DownloadNetworkPolicy.isTransient(e)) throw e
            onProgress("❌ 抓取异常: ${e.message}")
            return@withContext false
        } finally {
            if (ftp.isConnected) {
                try { ftp.disconnect() } catch (e: Exception) { }
            }
        }
      }
    }

    /** Only NRT OSB from the successfully downloaded SP3/CLK directory. Never use phasebias/RTS. */
    suspend fun downloadWhuNrtBias(
        server: String,
        remoteDir: String,
        savePath: File,
        clientFactory: () -> FTPClient = { FTPClient() },
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = DownloadNetworkPolicy.download("NRT BIA FTP $server", onProgress) {
      withContext(Dispatchers.IO) {
        require(Regex("^/pub/gnss/products/mgex/\\d+/?$").matches(remoteDir)) {
            "NRT BIA 必须来自配套轨道/钟差的 MGEX GPS 周目录"
        }
        val ftp = clientFactory()
        try {
            DownloadNetworkPolicy.configure(ftp)
            onProgress("🌐 BIA FTP连接/登录：$server")
            ftp.connect(server, 21)
            if (!ftp.login("anonymous", "guest@")) return@withContext false
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            if (!ftp.changeWorkingDirectory(remoteDir)) {
                onProgress("❌ NRT BIA 目录不可用：$remoteDir；不改用 RTS")
                return@withContext false
            }

            onProgress("📂 NRT BIA读取配套目录：$remoteDir，仅接受 WUM0MGXNRT_*_OSB.BIA[.gz]")
            val files = ftp.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name }
            val name = NrtBiasPolicy.latest(files.keys)
            if (name == null) {
                onProgress("⚠️ 配套目录未找到 NRT BIA：$remoteDir；不下载 RTS，不访问 phasebias 目录")
                return@withContext false
            }
            val targetFile = files.getValue(name)

            val localFile = File(savePath, targetFile.name)
            onProgress("⬇️ 开始下载 NRT 偏差产品: ${targetFile.name} ...")
            val success = retrieveWithProgress(
                ftp, targetFile.name, localFile, targetFile.size, onTransferProgress
            )
            if (success) onProgress("🎯 NRT BIA 下载完成：${targetFile.name}；内部 OSB 有效期仍由解算器检查，不外推、不替换为 RTS")
            else onProgress("❌ NRT BIA 传输/完整性检查失败：${targetFile.name}；不改用 RTS")
            success
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (DownloadNetworkPolicy.isTransient(e)) throw e
            onProgress("❌ 偏差产品下载异常: ${e.message}")
            false
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (_: Exception) { }
        }
      }
    }

    /** Download the latest published RTS BIA; daily files are refreshed even if already present. */
    suspend fun downloadWhuRtsBias(
        server: String,
        savePath: File,
        referenceUtcMillis: Long = System.currentTimeMillis(),
        clientFactory: () -> FTPClient = { FTPClient() },
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = DownloadNetworkPolicy.download("RTS BIA FTP $server", onProgress) {
      withContext(Dispatchers.IO) {
        val ftp = clientFactory()
        try {
            DownloadNetworkPolicy.configure(ftp)
            onProgress("🌐 RTS BIA FTP连接/登录：$server")
            ftp.connect(server, 21)
            if (!ftp.login("anonymous", "guest@")) return@withContext false
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            for (remoteDir in RtsBiasPolicy.directories(referenceUtcMillis)) {
                if (!ftp.changeWorkingDirectory(remoteDir)) {
                    onProgress("⚠️ RTS BIA 目录不可用：$remoteDir")
                    continue
                }
                onProgress("📂 RTS BIA读取目录：$remoteDir")
                val files = ftp.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name }
                val year = remoteDir.split('/').dropLast(1).last()
                val name = RtsBiasPolicy.latest(files.keys.filter {
                    it.startsWith("WUM0MGXRTS_$year", ignoreCase = true)
                }, referenceUtcMillis) ?: continue
                val target = files.getValue(name)
                onProgress("⬇️ 开始下载最新 RTS 偏差产品：$name（同名文件也重新获取）")
                val success = retrieveWithProgress(ftp, name, File(savePath, name),
                    target.size, onTransferProgress)
                if (success) onProgress("🎯 RTS BIA 下载完成：$name；码/相位覆盖分别检查，不外推有效期；与 NRT 轨钟的参考兼容性须核对")
                else onProgress("❌ RTS BIA 传输/完整性检查失败：$name；保留旧文件")
                return@withContext success
            }
            onProgress("❌ 未找到可下载的 RTS BIA；不回退下载 NRT BIA")
            false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (DownloadNetworkPolicy.isTransient(e)) throw e
            onProgress("❌ RTS BIA 下载异常：${e.javaClass.simpleName}")
            false
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (_: Exception) { }
        }
      }
    }

    // ==========================================
    // 引擎 2：跨国直连获取最新广播星历 (NAV)
    // ==========================================
    internal suspend fun downloadNavOverHttps(
        remoteDirectories: List<String>,
        savePath: File,
        referenceUtcMillis: Long,
        maxDaysBack: Int,
        connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean {
        val root = "https://igs.bkg.bund.de/root_ftp"
        fun connection(url: String) = connectionFactory(URL(url)).apply { DownloadNetworkPolicy.configure(this) }
        for ((daysAgo, dir) in remoteDirectories.withIndex()) {
            onProgress("🔎 HTTPS 广播星历 UTC 回退 $daysAgo 天：$dir")
            val names = try {
                DownloadNetworkPolicy.retry("NAV HTTPS目录 $dir", onProgress) {
                    val listing = connection("$root$dir")
                    try {
                        val status = listing.responseCode
                        DownloadNetworkPolicy.checkHttpStatus(status)
                        if (status != HttpURLConnection.HTTP_OK) {
                            onProgress("⚠️ NAV HTTPS 目录暂不可用（HTTP $status），继续回退")
                            emptyList()
                        } else listing.inputStream.bufferedReader().use { BroadcastNavPolicy.listingNames(it.readText()) }
                    } finally { listing.disconnect() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (DownloadNetworkPolicy.isTransient(e)) {
                    onProgress("⚠️ NAV HTTPS目录网络重试耗尽；不把超时误当目录未生成，交给FTP后备通道")
                    return false
                }
                onProgress("⚠️ NAV HTTPS 目录访问异常：${e.message}；继续回退")
                emptyList()
            }
            val candidates = BroadcastNavPolicy.candidates(names, referenceUtcMillis, maxDaysBack)
            if (candidates.isEmpty()) onProgress("⚠️ 此 UTC 日期尚无可用 RINEX3 NAV 文件")
            for (name in candidates) {
                val target = File(savePath, name)
                try {
                    onProgress("⬇️ HTTPS 下载广播星历：$name")
                    val compressed = name.endsWith(".gz", ignoreCase = true)
                    val status = downloadHttpFile("$root$dir$name", target, "NAV HTTPS文件",
                        connectionFactory = connectionFactory,
                        validate = { temporary -> (!compressed || validGzip(temporary)) &&
                            BroadcastNavPolicy.validRinex3(temporary, compressed) },
                        onTransferProgress = onTransferProgress, onProgress = onProgress)
                    if (status != HttpURLConnection.HTTP_OK) {
                        onProgress("⚠️ NAV 未下载（HTTP/校验状态 $status）：$name；尝试下一候选")
                        continue
                    }
                    if (daysAgo > 0) onProgress("⚠️ 使用回退广播星历：$name；不是当天产品，" +
                        "是否仍适用当前观测须由 RTKLIB 按星历 TOE/有效期检查")
                    onProgress("🎯 最新可用广播星历下载完成：$name（HTTPS）")
                    return true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (DownloadNetworkPolicy.isTransient(e)) {
                        onProgress("⚠️ NAV HTTPS文件网络重试耗尽：$name；尝试FTP后备，而不是换旧日期掩盖网络故障")
                        return false
                    }
                    onProgress("⚠️ NAV HTTPS 下载异常：${e.message}；尝试下一候选")
                }
            }
        }
        return false
    }

    internal suspend fun downloadNavProduct(
        server: String,
        remoteDir: String,
        savePath: File,
        referenceUtcMillis: Long = System.currentTimeMillis(),
        maxDaysBack: Int = 3,
        clientFactory: () -> FTPClient = { FTPClient() },
        preferHttps: Boolean = true,
        onTransferProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val directories = BroadcastNavPolicy.directories(remoteDir, maxDaysBack)
        onProgress("🌐 获取最新广播星历：按 UTC 日期从新到旧检索...")
        // HTTPS自己已按资源重试；FTP重连不能再次套一轮HTTPS，避免3×3重复等待。
        if (preferHttps && (server == "igs-ftp.bkg.bund.de" || server == "igs.bkg.bund.de")) {
            if (downloadNavOverHttps(directories, savePath, referenceUtcMillis, maxDaysBack,
                onTransferProgress = onTransferProgress, onProgress = onProgress)) return@withContext true
            onProgress("⚠️ BKG HTTPS 未取得 NAV，尝试 FTP 后备通道")
        }
        DownloadNetworkPolicy.download("NAV广播星历 FTP $server", onProgress) {
      withContext(Dispatchers.IO) {
        val ftp = clientFactory()
        try {
            DownloadNetworkPolicy.configure(ftp)
            onProgress("🌐 NAV FTP连接/登录：$server")
            ftp.connect(server, 21)
            if (!ftp.login("anonymous", "guest@")) {
                onProgress("❌ 广播星历 FTP 登录失败（${ftp.replyCode}）")
                return@withContext false
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
            for ((daysAgo, dir) in directories.withIndex()) {
                onProgress("🔎 广播星历 UTC 回退 $daysAgo 天：$dir")
                if (!ftp.changeWorkingDirectory(dir)) {
                    onProgress("⚠️ NAV 目录尚未生成或不可访问（${ftp.replyCode}），继续查找")
                    continue
                }
                onProgress("📂 NAV读取目录：$dir")
                val files = ftp.listFiles().orEmpty().filter { it.isFile }.associateBy { it.name }
                val candidates = BroadcastNavPolicy.candidates(files.keys, referenceUtcMillis, maxDaysBack)
                if (candidates.isEmpty()) onProgress("⚠️ 该目录暂无兼容的 RINEX3 混合广播星历，继续回退")
                for (name in candidates) {
                    onProgress("⬇️ 下载广播星历：$name")
                    val success = retrieveWithProgress(ftp, name, File(savePath, name),
                        files.getValue(name).size, onTransferProgress) { temporary ->
                        BroadcastNavPolicy.validRinex3(temporary, name.endsWith(".gz", ignoreCase = true))
                    }
                    if (success) {
                        if (daysAgo > 0) onProgress("⚠️ 使用回退广播星历：$name；不是当天产品，" +
                            "是否仍适用当前观测须由 RTKLIB 按星历 TOE/有效期检查")
                        onProgress("🎯 最新可用广播星历下载完成：$name")
                        return@withContext true
                    }
                    onProgress("⚠️ NAV 传输或 RINEX3 内容校验失败：$name；继续尝试")
                }
            }
            onProgress("❌ 今日及前 $maxDaysBack 天均未找到可下载的 RINEX3 混合广播星历")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (DownloadNetworkPolicy.isTransient(e)) throw e
            onProgress("❌ 广播星历连接/下载异常：${e.message}")
            false
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (e: Exception) { }
        }
      }
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

                val fileUrl = "https://www.aiub.unibe.ch/download/CODE/IONO/PRD/$fileName"
                onProgress("🌐 正在尝试 CODE $productId 电离层预报: $fileName ...")
                try {
                    val responseCode = downloadHttpFile(fileUrl, localFile, "IONEX HTTPS文件",
                        validate = { validGzip(it) }, onTransferProgress = onTransferProgress, onProgress = onProgress)
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        onProgress("⚠️ CODE $productId 暂不可用（HTTP $responseCode），尝试下一版本...")
                        continue
                    }
                    onProgress("🎯 电离层预报下载成功（$productId）: $fileName")
                    return@withContext true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (DownloadNetworkPolicy.isTransient(e)) {
                        onProgress("❌ IONEX网络重试耗尽：$fileName；这不表示P0D至P4D产品都未发布")
                        return@withContext false
                    }
                    onProgress("⚠️ CODE $productId 访问异常（${e.message}），尝试下一版本...")
                }
            }

            onProgress("❌ CODE P0D 至 P4D 均未提供 $year 年第 $formattedDoy 天的电离层文件。")
            return@withContext false

        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
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

                onProgress("⬇️ 正在下载 VMF3 预报: $fileName ...")
                    val responseCode = downloadHttpFile("$remoteDir/$fileName", localFile, "VMF3 HTTPS文件",
                        authorization = "Basic $authorization",
                        validate = { reusableVmf3File(it, year, month, day, hour) },
                        onTransferProgress = onTransferProgress, onProgress = onProgress)
                    when (responseCode) {
                        HttpURLConnection.HTTP_OK -> {
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
            }

            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
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
