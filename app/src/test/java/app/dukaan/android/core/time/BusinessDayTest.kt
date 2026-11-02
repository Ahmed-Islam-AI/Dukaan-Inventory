package app.dukaan.android.core.time

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BusinessDayTest {

    private val karachi = ZoneId.of("Asia/Karachi") // UTC+5, no DST

    @Test
    fun `a sale at 01-30 in Lahore belongs to that local day, whatever zone the test JVM runs in`() {
        val t = Instant.parse("2026-10-14T20:30:00Z").toEpochMilli() // 15 Oct 01:30 PKT
        assertEquals(20261015, businessDay(t, karachi))
        assertEquals(20261014, businessDay(t, ZoneId.of("UTC")))
    }

    @Test
    fun `last minute of the day stays on that day`() {
        val t = Instant.parse("2026-10-14T18:59:59Z").toEpochMilli() // 14 Oct 23:59:59 PKT
        assertEquals(20261014, businessDay(t, karachi))
    }
}
