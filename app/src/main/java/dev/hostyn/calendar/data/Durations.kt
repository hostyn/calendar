package dev.hostyn.calendar.data

/** RFC 5545 durations, as the calendar provider stores them in `DURATION` ("P3600S" (without the T, as Android writes them), "PT1H", "P1D"). */
object Durations {
    private val PATTERN = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

    /** Millis in [value], or null if it isn't a duration. */
    fun parse(value: String?): Long? {
        val m = PATTERN.matchEntire(value?.trim()?.uppercase() ?: return null) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        if (listOf(w, d, h, min, s).all { it.isEmpty() }) return null
        fun n(x: String) = x.toLongOrNull() ?: 0L
        val seconds = n(w) * 7 * 86400 + n(d) * 86400 + n(h) * 3600 + n(min) * 60 + n(s)
        return seconds * 1000 * (if (sign == "-") -1 else 1)
    }

    /** [millis] as a duration: whole days for all-day events, seconds otherwise. */
    fun format(millis: Long, allDay: Boolean): String =
        if (allDay) "P${(millis / 86_400_000L).coerceAtLeast(1)}D" else "P${(millis / 1000).coerceAtLeast(0)}S"
}
