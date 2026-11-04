package com.example.ftpget

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Optional bounded sidecar worker. No writes to the original Raw or the PPP input channel. */
class CaptureRepairRecorder(
    val file: File, scope: CoroutineScope,
    config: CaptureRepairConfig = CaptureRepairConfig(),
) {
    private data class Packet(val sequence: Long, val lines: List<String>)
    private val queue = Channel<Packet>(64)
    private val sequence = AtomicLong()
    val droppedEpochs = AtomicLong()
    @Volatile var failure: String? = null
        private set
    private val writer = GZIPOutputStream(file.outputStream()).bufferedWriter()
    private val job = scope.launch {
        val processor = CaptureCodePhaseRepair(config)
        var previous: Long? = null
        try {
            writer.use { out ->
                out.appendLine("# EXPERIMENTAL_MU2024_RATE_V1; candidate_only; not_PPP_input")
                out.appendLine("# gate_sigma=${config.gateSigma};driving_std_m_s4=${config.drivingStd};max_gap_s=${config.maxGapSeconds};max_prediction_epochs=${config.maxPredictionEpochs};phase_candidates=${config.enablePhaseCandidates}")
                out.appendLine(CaptureRepairRow.HEADER)
                for (packet in queue) {
                    if (previous != null && packet.sequence != previous!! + 1) processor.reset()
                    previous = packet.sequence
                    val observations = packet.lines.mapNotNull(CaptureObservation::fromRaw)
                    if (observations.size != packet.lines.size) {
                        // An unparsable row may be an otherwise unseen arc boundary. Do not bridge it.
                        processor.reset()
                        out.appendLine("# PARSE_PARTIAL,epoch=${packet.sequence},parsed=${observations.size},raw=${packet.lines.size}")
                    }
                    for (row in processor.process(observations)) out.appendLine(row.csv())
                }
                out.appendLine("# DroppedRepairEpochs,${droppedEpochs.get()}")
            }
        } catch (error: Exception) {
            failure = "${error.javaClass.simpleName}:${error.message}"
            queue.close()
            runCatching { writer.close() }
        }
    }
    fun offer(lines: List<String>) {
        // Copy list ownership; strings immutable. Failure cannot cancel the canonical Raw logger.
        if (queue.trySend(Packet(sequence.getAndIncrement(), lines.toList())).isFailure) droppedEpochs.incrementAndGet()
    }
    suspend fun finish() { queue.close(); job.join() }
}
