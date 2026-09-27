package dev.zcodemobile.shared.ui

import java.util.Calendar

internal actual fun localTimeFields(millis: Long): LocalTimeFields {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return LocalTimeFields(
        year = c.get(Calendar.YEAR),
        month = c.get(Calendar.MONTH) + 1,
        day = c.get(Calendar.DAY_OF_MONTH),
        hour = c.get(Calendar.HOUR_OF_DAY),
        minute = c.get(Calendar.MINUTE),
        dayOfYear = c.get(Calendar.DAY_OF_YEAR),
    )
}

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()
