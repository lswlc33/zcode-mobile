package dev.zcodemobile.shared.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
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
    val fields = LocalTimeFields(
        year = comps.year.toInt(),
        month = comps.month.toInt(),
        day = comps.day.toInt(),
        hour = comps.hour.toInt(),
        minute = comps.minute.toInt(),
        dayOfYear = 0,
    )
    // dayOfYear needs an ordinality query; compute from components when the
    // direct flag is unavailable.
    val doy = NSCalendar.currentCalendar.ordinalityOfUnitInUnitForDate(
        NSCalendarUnitDay, NSCalendarUnitYear, date, null,
    ) ?: 0
    return fields.copy(dayOfYear = doy)
}

internal actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()
