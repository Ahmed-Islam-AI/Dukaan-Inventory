package app.dukaan.android.core.time

import java.time.Instant
import java.time.ZoneId

/**
 * The shop's calendar day for a moment, as yyyyMMdd (D-36). Computed once when a row is written
 * and stored, so reports never depend on the zone or clock at query time.
 */
fun businessDay(epochMillis: Long, zone: ZoneId): Int {
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    return date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
}
