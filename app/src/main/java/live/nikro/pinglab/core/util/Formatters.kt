package live.nikro.pinglab.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Central place for every number -> string conversion in the app so that
 * latency values look identical on every screen.
 */
object Formatters {

    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val clockWithMillisFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    const val PLACEHOLDER = "\u2014"

    /** 8.42 ms / 23.4 ms / 148 ms \u2014 precision shrinks as the number grows. */
    fun latency(valueMs: Double?): String {
        if (valueMs == null || valueMs.isNaN()) return PLACEHOLDER
        return when {
            valueMs < 10.0 -> String.format(Locale.US, "%.2f", valueMs)
            valueMs < 100.0 -> String.format(Locale.US, "%.1f", valueMs)
            else -> valueMs.roundToInt().toString()
        }
    }

    fun latencyWithUnit(valueMs: Double?): String =
        if (valueMs == null) PLACEHOLDER else "${latency(valueMs)} ms"

    fun percent(value: Double?, decimals: Int = 1): String {
        if (value == null || value.isNaN()) return PLACEHOLDER
        return String.format(Locale.US, "%.${decimals}f%%", value)
    }

    fun integerPercent(value: Double?): String {
        if (value == null || value.isNaN()) return PLACEHOLDER
        return "${value.roundToInt()}%"
    }

    fun mos(value: Double?): String {
        if (value == null || value.isNaN()) return PLACEHOLDER
        return String.format(Locale.US, "%.2f", value)
    }

    fun count(value: Int): String = value.toString()

    /** 1.2s / 3m 04s / 2h 17m / 3d 4h */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "0s"
        val totalSeconds = millis / 1000
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
            totalSeconds > 0 -> "${seconds}s"
            else -> String.format(Locale.US, "%.1fs", millis / 1000.0)
        }
    }

    /** Compact interval label used in pickers: 500 ms / 1s / 30s / 5m. */
    fun interval(millis: Long): String = when {
        millis < 1_000L -> "$millis ms"
        millis < 60_000L -> {
            val seconds = millis / 1000.0
            if (seconds % 1.0 == 0.0) "${seconds.roundToLong()}s" else String.format(Locale.US, "%.1fs", seconds)
        }

        else -> "${millis / 60_000}m"
    }

    fun clock(timestampMs: Long): String = clockFormat.format(Date(timestampMs))

    fun clockPrecise(timestampMs: Long): String = clockWithMillisFormat.format(Date(timestampMs))

    fun dateTime(timestampMs: Long): String = dateTimeFormat.format(Date(timestampMs))

    fun iso(timestampMs: Long): String = isoFormat.format(Date(timestampMs))

    fun fileStamp(timestampMs: Long = System.currentTimeMillis()): String =
        fileStampFormat.format(Date(timestampMs))

    /** "just now", "12s ago", "4m ago", "3h ago", "2d ago". */
    fun relative(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val delta = nowMs - timestampMs
        if (timestampMs <= 0L) return PLACEHOLDER
        if (abs(delta) < 3_000L) return "just now"
        val seconds = delta / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun bitrate(kbps: Int): String = when {
        kbps <= 0 -> PLACEHOLDER
        kbps < 1_000 -> "$kbps Kbps"
        else -> String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
    }

    /** Axis labels want the shortest unambiguous form. */
    fun axisLatency(valueMs: Float): String = when {
        valueMs >= 1000f -> String.format(Locale.US, "%.1fs", valueMs / 1000f)
        valueMs >= 100f -> valueMs.roundToInt().toString()
        valueMs >= 10f -> String.format(Locale.US, "%.0f", valueMs)
        else -> String.format(Locale.US, "%.1f", valueMs)
    }
}
