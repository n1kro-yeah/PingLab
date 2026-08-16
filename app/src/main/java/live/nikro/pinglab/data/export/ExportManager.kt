package live.nikro.pinglab.data.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import live.nikro.pinglab.core.model.LatencyStats
import live.nikro.pinglab.core.model.MonitoredHost
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.model.QualityAssessment
import live.nikro.pinglab.core.model.TracerouteHop
import live.nikro.pinglab.core.util.Formatters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Writes measurement data to shareable files.
 *
 * Files land in `cacheDir/exports` and are handed out through a [FileProvider]: writing to
 * public storage would need runtime permissions on old API levels and is restricted by
 * scoped storage on new ones, while a content URI works everywhere.
 */
class ExportManager(private val context: Context) {

    data class Export(val file: File, val mimeType: String) {
        val displayName: String get() = file.name
        val sizeBytes: Long get() = file.length()
    }

    suspend fun exportCsv(target: String, results: List<ProbeResult>): Export =
        withContext(Dispatchers.IO) {
            val file = newFile(target, "csv")
            file.bufferedWriter().use { writer ->
                writer.append(CSV_HEADER).append('\n')
                results.forEach { result ->
                    writer.append(result.sequence.toString()).append(',')
                    writer.append(result.timestampMs.toString()).append(',')
                    writer.append(escape(Formatters.iso(result.timestampMs))).append(',')
                    writer.append(escape(result.hostname)).append(',')
                    writer.append(escape(result.resolvedAddress ?: "")).append(',')
                    writer.append(result.protocol.name).append(',')
                    writer.append(result.status.name).append(',')
                    writer.append(result.rttMs?.let { fixed(it) } ?: "").append(',')
                    writer.append(result.ttl?.toString() ?: "").append(',')
                    writer.append(result.payloadBytes?.toString() ?: "").append(',')
                    writer.append(result.transport.name).append(',')
                    writer.append(escape(result.detail ?: "")).append('\n')
                }
            }
            Export(file, "text/csv")
        }

    suspend fun exportJson(
        host: MonitoredHost?,
        target: String,
        results: List<ProbeResult>,
        stats: LatencyStats,
        quality: QualityAssessment?,
    ): Export = withContext(Dispatchers.IO) {
        val root = JSONObject().apply {
            put("application", "PingLab")
            put("exportedAt", Formatters.iso(System.currentTimeMillis()))
            put("target", target)
            host?.let { monitored ->
                put(
                    "host",
                    JSONObject().apply {
                        put("label", monitored.label)
                        put("target", monitored.target)
                        put("protocol", monitored.protocol.name)
                        put("port", monitored.port ?: JSONObject.NULL)
                        put("intervalMs", monitored.intervalMs)
                        put("timeoutMs", monitored.timeoutMs)
                    },
                )
            }
            put("statistics", statsJson(stats))
            quality?.let { put("quality", qualityJson(it)) }
            put("samples", samplesJson(results))
        }
        val file = newFile(target, "json")
        file.writeText(root.toString(2))
        Export(file, "application/json")
    }

    suspend fun exportTraceroute(target: String, hops: List<TracerouteHop>): Export =
        withContext(Dispatchers.IO) {
            val file = newFile("traceroute-$target", "txt")
            file.bufferedWriter().use { writer ->
                writer.append("traceroute to ").append(target)
                writer.append(", ").append(hops.size.toString()).append(" hops\n")
                hops.forEach { hop ->
                    writer.append(hop.ttl.toString().padStart(2, ' ')).append("  ")
                    writer.append(hop.displayName.padEnd(44, ' '))
                    hop.rttsMs.forEach { rtt ->
                        writer.append("  ").append(rtt?.let { fixed(it) + " ms" } ?: "*")
                    }
                    writer.append('\n')
                }
            }
            Export(file, "text/plain")
        }

    /** Builds a chooser-ready share intent for a previously written export. */
    fun shareIntent(export: Export): Intent = Intent(Intent.ACTION_SEND).apply {
        type = export.mimeType
        putExtra(Intent.EXTRA_STREAM, uriFor(export.file))
        putExtra(Intent.EXTRA_SUBJECT, export.displayName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** Removes exports older than a day so the cache does not grow without bound. */
    suspend fun pruneOldExports(maxAgeMs: Long = 24L * 60L * 60L * 1_000L): Int =
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            var removed = 0
            exportDir().listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff && file.delete()) removed++
            }
            removed
        }

    private fun statsJson(stats: LatencyStats): JSONObject = JSONObject().apply {
        put("sent", stats.sent)
        put("received", stats.received)
        put("lost", stats.lost)
        put("lossPercent", round(stats.lossPercent))
        putNullable("minMs", stats.minMs)
        putNullable("avgMs", stats.avgMs)
        putNullable("maxMs", stats.maxMs)
        putNullable("medianMs", stats.medianMs)
        putNullable("p90Ms", stats.p90Ms)
        putNullable("p95Ms", stats.p95Ms)
        putNullable("p99Ms", stats.p99Ms)
        putNullable("stdDevMs", stats.stdDevMs)
        putNullable("jitterRfc3550Ms", stats.rfc3550JitterMs)
        putNullable("jitterMeanDeviationMs", stats.meanDeviationJitterMs)
        putNullable("rFactor", stats.rFactor)
        putNullable("mos", stats.mos)
        put("longestOutage", stats.longestOutage)
        put("durationMs", stats.durationMs)
    }

    private fun qualityJson(quality: QualityAssessment): JSONObject = JSONObject().apply {
        put("grade", quality.grade.name)
        put("score", quality.score)
        put("headline", quality.headline)
        put("details", JSONArray(quality.details))
        put(
            "useCases",
            JSONArray().also { array ->
                quality.useCases.forEach { rating ->
                    array.put(
                        JSONObject().apply {
                            put("useCase", rating.useCase.name)
                            put("grade", rating.grade.name)
                            put("reason", rating.reason)
                        },
                    )
                }
            },
        )
    }

    private fun samplesJson(results: List<ProbeResult>): JSONArray = JSONArray().also { array ->
        results.forEach { result ->
            array.put(
                JSONObject().apply {
                    put("sequence", result.sequence)
                    put("timestamp", Formatters.iso(result.timestampMs))
                    put("epochMs", result.timestampMs)
                    put("status", result.status.name)
                    putNullable("rttMs", result.rttMs)
                    put("address", result.resolvedAddress ?: JSONObject.NULL)
                    put("ttl", result.ttl ?: JSONObject.NULL)
                    put("transport", result.transport.name)
                    result.dnsMs?.let { put("dnsMs", round(it)) }
                    result.connectMs?.let { put("connectMs", round(it)) }
                    result.tlsMs?.let { put("tlsMs", round(it)) }
                    result.firstByteMs?.let { put("firstByteMs", round(it)) }
                    result.httpStatusCode?.let { put("httpStatus", it) }
                    result.detail?.let { put("detail", it) }
                },
            )
        }
    }

    private fun JSONObject.putNullable(key: String, value: Double?) {
        put(key, value?.let { round(it) } ?: JSONObject.NULL)
    }

    private fun exportDir(): File =
        File(context.cacheDir, "exports").also { if (!it.exists()) it.mkdirs() }

    private fun newFile(target: String, extension: String): File {
        val safeTarget = target.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)
        return File(exportDir(), "pinglab-" + safeTarget + "-" + Formatters.fileStamp() + "." + extension)
    }

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""
        } else {
            value
        }

    private fun fixed(value: Double): String = String.format("%.3f", value)

    private fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    companion object {
        private const val CSV_HEADER =
            "sequence,epoch_ms,timestamp,hostname,address,protocol,status,rtt_ms,ttl,bytes,transport,detail"
    }
}
