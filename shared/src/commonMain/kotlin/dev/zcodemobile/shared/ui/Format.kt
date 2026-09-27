package dev.zcodemobile.shared.ui

/**
 * Cross-platform replacements for the JVM's SimpleDateFormat / String.format
 * used by the shared UI. Local-time formatting without kotlinx-datetime: the
 * epoch-day math is done on the *local* clock fields taken from `Date`-like
 * helpers below, which on iOS come through NSDate and on Android through
 * java.util.Calendar via a platform expect.
 */
object Format {

    /** "%.1f" with one decimal, rounded half-up. */
    fun f1(v: Double): String {
        val scaled = kotlin.math.round(v * 10) / 10
        val int = scaled.toLong()
        val frac = kotlin.math.abs(scaled - int)
        val fracDigit = ((frac * 10) + 0.5).toInt() % 10
        return "$int.$fracDigit"
    }

    /**
     * Local wall-clock "HH:mm" for an epoch-milliseconds instant.
     * Uses the per-platform local field extraction ([LocalTimeFields]).
     */
    fun hhmm(millis: Long): String {
        val f = localTimeFields(millis)
        return "%02d:%02d".let { "${f.hour.toString().padStart(2, '0')}:${f.minute.toString().padStart(2, '0')}" }
    }

    /** Local "MM-dd". */
    fun mmdd(millis: Long): String {
        val f = localTimeFields(millis)
        return "${f.month.toString().padStart(2, '0')}-${f.day.toString().padStart(2, '0')}"
    }

    /** Local "yyyy". */
    fun year(millis: Long): String = localTimeFields(millis).year.toString()

    /** Local day-of-year (1-based), matching Calendar.DAY_OF_YEAR semantics. */
    fun dayOfYear(millis: Long): Int = localTimeFields(millis).dayOfYear
}

data class LocalTimeFields(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val dayOfYear: Int,
)

/** Local calendar fields for an epoch-millis instant, per platform. */
internal expect fun localTimeFields(millis: Long): LocalTimeFields

/** Wall-clock now in epoch millis. */
internal expect fun currentTimeMillis(): Long
