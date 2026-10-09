package com.gios.brightchatlite.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * iMessage-style absolute timestamps, ported from BrightChat's `listTime`: a clock time for
 * today, "Yesterday", the weekday within the last week, then a short date. Absolute past a
 * day, because "18 hours ago" stops being parseable.
 *
 * The original used `android.text.format.DateUtils`; this uses `java.time` with the zone,
 * locale and clock injected so it can be tested.
 */
class TimeFormat(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)

    private fun date(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    /** Clock time, for example "3:14 PM". */
    fun clock(ts: Long): String = timeFormatter.format(Instant.ofEpochMilli(ts).atZone(zone))

    /** List timestamp. Blank for an unknown date. */
    fun listTime(ts: Long): String {
        if (ts <= 0L) return ""
        val d = date(ts)
        val today = date(now())
        return when {
            d == today -> clock(ts)
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(7)) && d.isBefore(today) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> dateFormatter.format(d)
        }
    }

    /** Thread day divider: "Today", "Yesterday", a weekday, or a short date. */
    fun dayHeader(ts: Long): String {
        val d = date(ts)
        val today = date(now())
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(7)) && d.isBefore(today) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> dateFormatter.format(d)
        }
    }

    /** Epoch millis of the start of [ts]'s local day. */
    fun dayStart(ts: Long): Long = date(ts).atStartOfDay(zone).toInstant().toEpochMilli()
}
