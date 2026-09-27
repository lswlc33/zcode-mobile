package dev.zcodemobile.shared.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.dateWithTimeIntervalSince1970

@OptIn(ExperimentalForeignApi::class)
internal actual fun localTimeFields(millis: Long): LocalTimeFields {
    val cal = NSCalendar.currentCalendar
    val date = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)
    val comps: NSDateComponents = cal.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
            NSCalendarUnitHour or NSCalendarUnitMinute,
        date,
    )
    val y = comps.year.toInt()
    val m = comps.month.toInt()
    val d = comps.day.toInt()
    return LocalTimeFields(
        year = y,
        month = m,
        day = d,
        hour = comps.hour.toInt(),
        minute = comps.minute.toInt(),
        // Day-of-year from civil-date math (Julian day number difference,
        // 1-based): the NSCalendar ordinality API's Kotlin name varies across
        // KGP releases, so it is not dependable here.
        dayOfYear = julianDay(y, m, d) - julianDay(y, 1, 1) + 1,
    )
}

/** Proleptic Gregorian Julian day number for a civil date. */
internal fun julianDay(y: Int, m: Int, d: Int): Int {
    val a = (14 - m) / 12
    val yy = y + 4800 - a
    val mm = m + 12 * a - 3
    return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
}

internal actual fun currentTimeMillis(): Long =
    (NSDate.date.timeIntervalSince1970 * 1000).toLong()
