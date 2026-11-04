package com.example.ftpget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 下载传输策略，与产品选择/时效和PPP模型无关。
 * readTimeout是“连续多久收不到数据”的上限，不是整份文件总共只能下载90秒。
 * 每次重试都由调用方新建连接；绝不沿用超时后FTP可能已失步的控制会话。
 */
internal object DownloadNetworkPolicy {
    const val CONNECT_MS = 20_000
    const val CONTROL_MS = 45_000
    const val READ_MS = 90_000
    const val MAX_ATTEMPTS = 3

    fun configure(ftp: FTPClient) {
        ftp.connectTimeout = CONNECT_MS
        // connect之前设置；Commons Net在建立控制socket时应用，包括欢迎语/命令回复读取。
        ftp.defaultTimeout = CONTROL_MS
        ftp.setDataTimeout(java.time.Duration.ofMillis(READ_MS.toLong()))
        ftp.bufferSize = 64 * 1024
    }

    fun configure(http: HttpURLConnection) {
        http.connectTimeout = CONNECT_MS
        http.readTimeout = READ_MS
        http.instanceFollowRedirects = true
        http.setRequestProperty("User-Agent", "FTPGet-GNSS/1.1")
    }

    class TemporaryHttpFailure(val status: Int) : IOException("HTTP $status（服务器暂时不可用）")

    fun checkHttpStatus(status: Int) {
        if (status == 408 || status == 429 || status in 500..599) throw TemporaryHttpFailure(status)
        // 401/403/404不作为网络超时重试，保留认证/未发布的真实含义。
    }

    fun isTransient(error: Throwable): Boolean {
        val seen = hashSetOf<Throwable>()
        var cause: Throwable? = error
        while (cause != null && seen.add(cause)) {
            if (cause is SocketTimeoutException || cause is ConnectException || cause is SocketException ||
                cause is EOFException || cause is FTPConnectionClosedException || cause is TemporaryHttpFailure) return true
            cause = cause.cause
        }
        return false
    }

    suspend fun <T> retry(
        label: String,
        onProgress: (String) -> Unit,
        pause: suspend (Long) -> Unit = { delay(it) },
        action: suspend () -> T
    ): T {
        for (attempt in 1..MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            onProgress("🌐 $label，尝试 $attempt/$MAX_ATTEMPTS")
            try {
                return action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isTransient(e)) throw e
                if (attempt == MAX_ATTEMPTS) {
                    onProgress("❌ $label：网络重试耗尽（${e.javaClass.simpleName}）；连接${CONNECT_MS/1000}s、控制${CONTROL_MS/1000}s、读取${READ_MS/1000}s")
                    throw e
                }
                val waitMs = if (attempt == 1) 1_000L else 3_000L
                onProgress("⚠️ $label：网络读取超时或连接中断（${e.javaClass.simpleName}），${waitMs/1000}秒后重新连接；不会跳过下载或使用不完整文件")
                pause(waitMs)
            }
        }
        error("unreachable")
    }

    suspend fun download(label: String, onProgress: (String) -> Unit, action: suspend () -> Boolean): Boolean =
        try { retry(label, onProgress, action = action) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // 不输出URL认证头、用户名或密码；产品/阶段由label和调用方的阶段日志说明。
            onProgress("❌ $label 失败：${e.javaClass.simpleName}；请保留此前的文件名和阶段日志")
            false
        }
}
