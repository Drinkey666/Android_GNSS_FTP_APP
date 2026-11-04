package com.example.ftpget

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.zip.GZIPInputStream

class CaptureRepairRecorderTest {
    @Test fun sidecarDrainsAndClosesWithoutChangingRawLines() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory(File("E:/android/test/FTPGet/tools/acquisition_repair_20261030").toPath(),"capture_repair_").toFile()
        val file = File(dir,"candidate.csv.gz")
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val recorder = CaptureRepairRecorder(file,scope)
            val lines = listOf("not Raw")
            val before = lines.toList()
            recorder.offer(lines);recorder.finish()
            val text = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
            assertEquals(before,lines);assertNull(recorder.failure)
            assertTrue(text.contains("PARSE_PARTIAL"));assertTrue(text.contains("DroppedRepairEpochs,0"))
            assertTrue(text.contains(CaptureRepairRow.HEADER))
        } finally { scope.cancel(); file.delete();dir.delete() }
    }
    @Test fun closedWorkerDoesNotBlockProducer() = runBlocking {
        val file = File.createTempFile("capture_repair_", ".gz", File("E:/android/test/FTPGet/tools/acquisition_repair_20261030"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val recorder=CaptureRepairRecorder(file,scope);recorder.finish()
            recorder.offer(emptyList());assertEquals(1L,recorder.droppedEpochs.get())
        } finally { scope.cancel();file.delete() }
    }
    @Test fun pathFailureIsExplicit() {
        val dir=java.nio.file.Files.createTempDirectory(File("E:/android/test/FTPGet/tools/acquisition_repair_20261030").toPath(),"capture_repair_").toFile()
        val scope=CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            try { CaptureRepairRecorder(dir,scope);fail("directory cannot be output file") }
            catch (_: java.io.IOException) { /* logger captures this; canonical Raw remains independent */ }
        } finally { scope.cancel();dir.delete() }
    }
}
