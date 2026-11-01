package app.dukaan.android.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {

    @Test
    fun `hash matches only the same PIN and differs per salt`() {
        val salt = Pin.newSalt()
        val hash = Pin.hash("4821", salt)
        assertTrue(Pin.matches("4821", salt, hash))
        assertFalse(Pin.matches("4822", salt, hash))
        assertNotEquals(hash, Pin.hash("4821", Pin.newSalt()))
        assertFalse(hash.contains("4821"))
    }

    @Test
    fun `4 to 6 ASCII digits only`() {
        assertTrue(Pin.isValid("1234"))
        assertTrue(Pin.isValid("123456"))
        assertFalse(Pin.isValid("123"))
        assertFalse(Pin.isValid("1234567"))
        assertFalse(Pin.isValid("12a4"))
    }

    @Test
    fun `wrong PIN backoff starts at the fifth try, doubles, caps at 30 minutes`() {
        assertEquals(0L, Pin.waitMillis(4))
        assertEquals(30_000L, Pin.waitMillis(5))
        assertEquals(60_000L, Pin.waitMillis(6))
        assertEquals(30 * 60_000L, Pin.waitMillis(50))
    }
}
