package com.example.ftpget

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** 手工启用的公网冒烟测试；普通离线单元测试不依赖服务器。不会使用任何 VMF3 凭据。 */
class NavHttpsPublicSmokeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun whuNrtBiaFromExplicitPairDirectory() = runBlocking {
        assumeTrue(System.getenv("GNSS_PUBLIC_BIA_SMOKE") == "1")
        val directory = checkNotNull(System.getenv("GNSS_PUBLIC_BIA_DIRECTORY"))
        assertTrue("NRT BIA 实际下载失败不能冒称成功", FtpDownloader.downloadWhuNrtBias(
            "igs.gnsswhu.cn", directory, tmp.root, onProgress = { println(it) }))
        val file = tmp.root.listFiles().orEmpty().single { !it.name.endsWith(".part") }
        assertTrue(file.name.startsWith("WUM0MGXNRT_"))
        assertTrue(FtpDownloader.reusableLocalFile(file))
        println("PUBLIC_NRT_BIA_OK,directory=$directory,name=${file.name},bytes=${file.length()}")
    }

    @Test fun bkgHttpsDownloadsAndValidatesRealNav() = runBlocking {
        assumeTrue(System.getenv("GNSS_PUBLIC_NAV_SMOKE") == "1")
        val date = System.getenv("GNSS_PUBLIC_NAV_DAY") ?: "2026-10-18"
        val reference = Instant.parse("${date}T12:00:00Z").toEpochMilli()
        val day = java.time.LocalDate.parse(date)
        val first = "/IGS/BRDC/%04d/%03d/".format(java.util.Locale.US, day.year, day.dayOfYear)
        val ok = FtpDownloader.downloadNavOverHttps(BroadcastNavPolicy.directories(first, 3),
            tmp.root, reference, 3, onProgress = { println(it) })
        assertTrue("BKG HTTPS 应下载一份有效 NAV；网络失败不能冒称测试通过", ok)
        val nav = tmp.root.listFiles().orEmpty().single { !it.name.endsWith(".part") }
        assertTrue(BroadcastNavPolicy.validRinex3(nav, nav.name.endsWith(".gz")))
        println("PUBLIC_NAV_OK,name=${nav.name},bytes=${nav.length()}")
    }

    @Test fun whuFindsSameIssuePairAcrossSundayWeekBoundary() = runBlocking {
        assumeTrue(System.getenv("GNSS_PUBLIC_NRT_SMOKE") == "1")
        val date = System.getenv("GNSS_PUBLIC_NAV_DAY") ?: "2026-10-18"
        val reference = Instant.parse(System.getenv("GNSS_PUBLIC_NRT_UTC") ?: "${date}T12:00:00Z").toEpochMilli()
        var pairDirectory: String? = null
        val ok = FtpDownloader.downloadLatestWhuNrtPair("igs.gnsswhu.cn", tmp.root,
            referenceUtcMillis = reference, onPairDirectory = { pairDirectory = it }, onProgress = { println(it) })
        assertTrue("WHU 应找到仍覆盖参考时刻的同一时次 NRT SP3/CLK", ok)
        val files = tmp.root.listFiles().orEmpty().filter { !it.name.endsWith(".part") }
        assertEquals(2, files.size)
        assertEquals(1, NrtProductPolicy.pairs(files.map { it.name }, reference).size)
        assertTrue(files.all { FtpDownloader.reusableLocalFile(it) })
        println("PUBLIC_NRT_OK,files=${files.map { it.name }},directory=$pairDirectory")
        assertTrue("配套目录的NRT BIA下载失败不能冒称成功", FtpDownloader.downloadWhuNrtBias(
            "igs.gnsswhu.cn", checkNotNull(pairDirectory), tmp.root, onProgress = { println(it) }))
        val bia = tmp.root.listFiles().orEmpty().single { it.name.contains("_OSB.BIA") && !it.name.endsWith(".part") }
        assertTrue(bia.name.startsWith("WUM0MGXNRT_"))
        assertTrue(FtpDownloader.reusableLocalFile(bia))
        println("PUBLIC_NRT_BIA_OK,name=${bia.name},bytes=${bia.length()}")
    }
}
