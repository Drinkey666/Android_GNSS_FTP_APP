package com.example.ftpget

import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import java.io.File
import java.util.zip.GZIPOutputStream

/** Explicit offline invocation; uses EXACT production Kotlin algorithm, no products/truth/filter. */
class CaptureRepairReplayTest {
    @Test fun replayFourExistingCaptures() {
        assumeTrue(System.getenv("CAPTURE_REPAIR_REPLAY") == "1")
        val output = File("E:/GNSS/PPP_only/validation/acquisition_code_phase_repair_v1/replay").apply { mkdirs() }
        val files = listOf(
            File("E:/RTKLIB_Data/k70/RawData_20261018_082050.txt"),
            File("E:/RTKLIB_Data/k70/RawData_20261018_084040.txt"),
            File("E:/RTKLIB_Data/10.21/RawData_20261021_102241.txt"),
            File("E:/GNSS/PPP_only/validation/android_live_20261025_v1/inputs/RawData_20261025_133353.txt"),
        )
        for (file in files) {
            val processor = CaptureCodePhaseRepair()
            var rows = 0L; var parsed = 0L; var epochs = 0L; var invalid = 0L
            val counts = sortedMapOf<String, Long>()
            val epoch = ArrayList<CaptureObservation>(); var time: Long? = null
            GZIPOutputStream(File(output,"${file.nameWithoutExtension}.csv.gz").outputStream()).bufferedWriter().use { out ->
                out.appendLine(CaptureRepairRow.HEADER)
                fun process() {
                    if (epoch.isEmpty()) return
                    epochs++
                    for (r in processor.process(epoch)) {
                        out.appendLine(r.csv())
                        val key = "${r.type}:${r.status}"
                        counts[key] = (counts[key] ?: 0) + 1
                        assertTrue(r.candidate == null || r.candidate.isFinite())
                        assertTrue(r.raw == null || r.raw.isFinite())
                    }
                    epoch.clear()
                }
                file.forEachLine { line ->
                    if (line.startsWith("Raw,")) {
                        rows++
                        val o = CaptureObservation.fromRaw(line)
                        if (o == null) { invalid++; processor.reset() } else {
                            parsed++
                            if (time != o.timeNanos) { process(); time = o.timeNanos }
                            epoch += o
                        }
                    }
                }
                process()
            }
            File(output,"${file.nameWithoutExtension}_summary.json").writeText(
                "{\"input\":\"${file.path.replace('\\','/')}\",\"raw_rows\":$rows,\"parsed\":$parsed,\"invalid_rows\":$invalid,\"epochs\":$epochs,\"counts\":{" +
                    counts.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}}")
            assertTrue(epochs > 600)
        }
    }

    @Test fun knownFaultOnRealRawPrefixes() {
        assumeTrue(System.getenv("CAPTURE_REPAIR_REPLAY") == "1")
        val files = listOf(
            "E:/RTKLIB_Data/k70/RawData_20261018_082050.txt",
            "E:/RTKLIB_Data/k70/RawData_20261018_084040.txt",
            "E:/RTKLIB_Data/10.21/RawData_20261021_102241.txt",
            "E:/GNSS/PPP_only/validation/android_live_20261025_v1/inputs/RawData_20261025_133353.txt")
        val output = File("E:/GNSS/PPP_only/validation/acquisition_code_phase_repair_v1/replay/fault_injection.csv")
        output.parentFile.mkdirs() // each test is independent of test execution order
        output.bufferedWriter().use { out ->
            out.appendLine("input,time_nanos,signal,type,injected_m,baseline_status,test_status,known_fault_removed_m,candidate_delta_from_control_m")
            for (path in files) {
                var base=CaptureCodePhaseRepair();var code=CaptureCodePhaseRepair();var phase=CaptureCodePhaseRepair()
                val epoch=ArrayList<CaptureObservation>();var time:Long?=null;var k=0
                fun process() {
                    if(epoch.isEmpty())return
                    k++
                    val control=base.process(epoch)
                    val target=if(k%40==0) epoch.filter { o ->
                        o.phaseContinuous && o.codeMeters != null && o.codeSigma != null &&
                            control.any { it.observation.key==o.key && it.type=="P" && it.status=="ORIGINAL_ACCEPTED" } &&
                            control.any { it.observation.key==o.key && it.type=="L" && it.status=="ORIGINAL_ACCEPTED" }
                    }.maxByOrNull { it.cn0 } else null
                    val codeRows=code.process(epoch.map { if(it.key==target?.key) it.copy(codeMeters=it.codeMeters!!+100.0) else it })
                    val phaseRows=phase.process(epoch.map { if(it.key==target?.key) it.copy(adrMeters=it.adrMeters!!+100.0) else it })
                    if(target!=null) for((type,testRows) in listOf("P" to codeRows,"L" to phaseRows)) {
                        val original=control.single { it.observation.key==target.key && it.type==type }
                        val tested=testRows.single { it.observation.key==target.key && it.type==type }
                        val delta=tested.candidate!!-original.candidate!!
                        out.appendLine("${File(path).name},${target.timeNanos},${target.key},$type,100,${original.status},${tested.status},${100-delta},$delta")
                    }
                    // Each new block starts empty; within a block all three have identical causal history.
                    if(k%40==0) {base=CaptureCodePhaseRepair();code=CaptureCodePhaseRepair();phase=CaptureCodePhaseRepair()}
                    epoch.clear()
                }
                File(path).forEachLine { line ->
                    if(line.startsWith("Raw,")) CaptureObservation.fromRaw(line)?.let { o ->
                        if(time!=o.timeNanos){process();time=o.timeNanos};epoch+=o
                    }
                }
                process()
            }
        }
        assertTrue(output.length()>100)
    }
}
