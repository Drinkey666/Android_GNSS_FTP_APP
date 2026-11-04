package com.example.ftpget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 把已经写完的 TXT/OBS 和本地产品复制到用户授权的公共文件夹。
 * 例如内部存储第一层的 FTPGet；不申请 MANAGE_EXTERNAL_STORAGE，不改变内核输入路径。
 * 注意 SAF 的 content:// URI 不是普通 File 路径，所有公共目录操作须经 ContentResolver。
 */
class PublicGnssExporter(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences(
        "gnss_public_export", Context.MODE_PRIVATE
    )

    data class ExportedFile(val uri: Uri, val name: String, val directory: String,
                            val reused: Boolean = false)

    data class ProductExportSummary(val copied: Int, val reused: Int,
                                    val failures: List<String>, val directory: String) {
        val total: Int get() = copied + reused + failures.size
    }

    fun selectedDirectory(): Uri? = preferences.getString("tree_uri", null)?.let(Uri::parse)

    fun directoryLabel(): String = preferences.getString("tree_label", null)
        ?: "未选择；可在内部存储第一层创建 FTPGet 文件夹"

    /** 在 IO 线程执行。授权持久化后重启应用仍可导出；取消选择不会清除旧目录。 */
    @Synchronized
    fun rememberDirectory(uri: Uri) {
        require(DocumentsContract.isTreeUri(uri)) { "请选择一个文件夹" }
        if (uri.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.getTreeDocumentId(uri).endsWith(':')) {
            throw IOException("请进入 FTPGet 等文件夹，不要选择整个存储根目录")
        }
        val document = DocumentsContract.buildDocumentUriUsingTree(
            uri, DocumentsContract.getTreeDocumentId(uri)
        )
        val label = resolver.query(document, arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS
        ), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("无法读取选中的文件夹")
            val flags = cursor.getInt(1)
            if (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                throw IOException("此文件夹不允许新建文件，请选择可写目录")
            }
            val id = DocumentsContract.getTreeDocumentId(uri)
            if (uri.authority == "com.android.externalstorage.documents" &&
                id.startsWith("primary:")) {
                "内部存储 / " + id.substringAfter(':')
            } else cursor.getString(0) ?: "已选择的文件夹"
        } ?: throw IOException("无法访问选中的文件夹")

        resolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // 新目录验证/授权成功后才替换原设置；若失败，原授权与内部文件均保留。
        if (!preferences.edit().putString("tree_uri", uri.toString())
                .putString("tree_label", label).commit()) {
            throw IOException("保存目录设置失败，请重试")
        }
    }

    /**
     * IO 线程调用，先确认源文件已关闭；不导出仍在采集中的 TXT。
     * 同名文件采用 _1/_2 后缀另存，绝不覆盖目录中已有测量。
     * 失败只清理本次刚创建的公共副本，内部原始文件始终保留。
     */
    @Synchronized
    fun export(source: File): ExportedFile {
        if (!source.isFile) throw IOException("源文件不存在")
        val expectedBytes = source.length()
        if (expectedBytes == 0L) throw IOException("源文件为空，未导出")
        val tree = selectedDirectory() ?: throw IOException("请先选择公共保存目录")
        val selectedLabel = directoryLabel()
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) {
            throw IOException("目录授权已失效，请重新选择保存目录")
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val names = mutableSetOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(names::add)
        } ?: throw IOException("无法读取保存目录，请重新授权")
        val name = uniqueGnssExportName(source.name, names)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val target = DocumentsContract.createDocument(resolver, parent,
            "application/octet-stream", name) ?: throw IOException("无法创建导出文件")
        try {
            source.inputStream().use { input ->
                val stream = resolver.openOutputStream(target, "w")
                    ?: throw IOException("无法写入导出文件")
                stream.use { output -> copyGnssExport(input, output, expectedBytes) }
            }
            return ExportedFile(target, name, selectedLabel)
        } catch (error: Exception) {
            // target 仅指本次 createDocument 的新文件，不删除用户已有文件。
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw IOException("导出失败；应用内原文件已保留，请重选目录后重试", error)
        }
    }

    /**
     * 批量导出产品，期间锁定本导出器，防止用户中途更换目录造成同批文件散落。
     * .part/空文件/Raw TXT/OBS/凭据不会作为产品导出；内部原文件和下载流程不受影响。
     */
    @Synchronized
    fun exportProducts(directory: File): ProductExportSummary {
        if (selectedDirectory() == null) throw IOException("请先选择公共保存目录")
        val selectedLabel = directoryLabel()
        var copied = 0
        var reused = 0
        val failures = mutableListOf<String>()
        for (file in gnssProductFiles(directory)) {
            try {
                if (exportProduct(file).reused) reused++ else copied++
            } catch (error: Exception) {
                failures += "${file.name}：${error.message ?: "导出失败"}"
            }
        }
        return ProductExportSummary(copied, reused, failures, selectedLabel)
    }

    /**
     * 仅复用本应用以前记录的副本 URI，且源/目标 SHA-256 相同。
     * 不能只看文件名/长度就声称一致；目标被修改、删除或源发生变化时另存新副本。
     * 这是导出副本去重，不是网络下载缓存判断，不会使下载器跳过下载。
     */
    @Synchronized
    fun exportProduct(source: File): ExportedFile {
        require(isGnssProductName(source.name)) { "不是受支持的产品文件" }
        if (!source.isFile || source.length() == 0L) throw IOException("产品文件为空或不存在")
        val tree = selectedDirectory() ?: throw IOException("请先选择公共保存目录")
        val key = "product:$tree:${source.name}"
        val digest = source.inputStream().use(::gnssExportDigest)
        val remembered = preferences.getString("$key:uri", null)
        if (remembered != null && preferences.getString("$key:digest", null) == digest) {
            val uri = Uri.parse(remembered)
            val unchanged = runCatching {
                resolver.openInputStream(uri)?.use(::gnssExportDigest) == digest
            }.getOrDefault(false)
            if (unchanged) return ExportedFile(uri,
                preferences.getString("$key:name", source.name) ?: source.name,
                directoryLabel(), reused = true)
        }
        val result = export(source)
        // 再读公共副本确认内容完整；只对新创建文件做校验/失败清理，不删除旧文件。
        val verified = runCatching {
            resolver.openInputStream(result.uri)?.use(::gnssExportDigest) == digest
        }.getOrDefault(false)
        if (!verified) {
            runCatching { DocumentsContract.deleteDocument(resolver, result.uri) }
            throw IOException("公共产品副本内容校验失败，内部原产品保留")
        }
        preferences.edit().putString("$key:uri", result.uri.toString())
            .putString("$key:name", result.name).putString("$key:digest", digest).apply()
        return result
    }
}

/** 白名单只选产品，避免将 TXT、OBS、日志或账号配置当作下载产品复制出去。 */
internal fun isGnssProductName(name: String): Boolean {
    val upper = name.uppercase(Locale.US)
    val uncompressed = upper.removeSuffix(".GZ")
    return uncompressed.endsWith(".SP3") || uncompressed.endsWith(".CLK") ||
        uncompressed.endsWith(".BIA") || uncompressed.endsWith(".INX") ||
        uncompressed.endsWith(".ATX") || uncompressed == "OROGRAPHY_ELL_5X5" ||
        Regex("^VMF3_\\d{8}\\.H(?:00|06|12|18)$").matches(uncompressed) ||
        ((uncompressed.startsWith("BRDC") || uncompressed.startsWith("BRDM")) && (uncompressed.endsWith(".RNX") ||
            Regex("\\.\\d{2}[NPG]$").containsMatchIn(uncompressed)))
}

internal fun gnssProductFiles(directory: File): List<File> = directory.listFiles()
    ?.filter { it.isFile && it.length() > 0L && isGnssProductName(it.name) }
    ?.sortedBy { it.name }.orEmpty()

/** 全内容哈希，不把账号、测量值或产品内容写入日志；调用方负责关闭流。 */
internal fun gnssExportDigest(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** 纯文件名策略，可在 PC 单元测试；重复导出保留以前副本，不执行覆盖。 */
internal fun uniqueGnssExportName(name: String, existing: Set<String>): String {
    if (name !in existing) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    var suffix = 1
    while ("${stem}_$suffix$extension" in existing) suffix++
    return "${stem}_$suffix$extension"
}

/** 按字节复制，不重新编码 TXT、不改 RINEX 内容；长度不一致视为失败，不提示导出成功。 */
internal fun copyGnssExport(input: InputStream, output: OutputStream, expectedBytes: Long): Long {
    val copied = input.copyTo(output)
    output.flush()
    if (copied != expectedBytes) throw IOException("导出字节数与源文件不一致")
    return copied
}
