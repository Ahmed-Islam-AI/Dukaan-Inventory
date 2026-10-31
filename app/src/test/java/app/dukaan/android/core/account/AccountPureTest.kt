package app.dukaan.android.core.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class AccountPureTest {

    @Test
    fun `Pakistani numbers normalise to the same digits however they are typed`() {
        val want = "923001234567"
        listOf("03001234567", "0300-1234567", "+92 300 1234567", "00923001234567", "923001234567", "۰۳۰۰۱۲۳۴۵۶۷")
            .forEach { assertEquals(it, want, PhoneNumbers.normalize(it)) }
        assertNull(PhoneNumbers.normalize("12345"))
        assertNull(PhoneNumbers.normalize(""))
    }

    @Test
    fun `hidden phone email is recognised`() {
        val e = PhoneNumbers.hiddenEmail("923001234567", "dukaan-inventory")
        assertEquals("923001234567@phone.dukaan-inventory.firebaseapp.com", e)
        assertTrue(PhoneNumbers.isHiddenEmail(e))
    }

    @Test
    fun `cloud copy round trip across several chunks, and damage is refused`() {
        // Random bytes don't compress, so this forces more than one chunk.
        val snapshot = Random(7).nextBytes(CloudCopy.CHUNK_BYTES * 2 + 123)
        val packed = CloudCopy.pack(snapshot)
        assertTrue(packed.chunks.size >= 3)
        assertArrayEquals(snapshot, CloudCopy.unpack(packed.chunks, packed.sha256))
        try {
            CloudCopy.unpack(packed.chunks.dropLast(1), packed.sha256)
            fail("missing chunk must be refused")
        } catch (e: IllegalStateException) {
            assertEquals("cloud copy is damaged", e.message)
        }
    }

    @Test
    fun `invite keys match however the owner typed them`() {
        assertEquals("923001234567", inviteKeyOf("0300 1234567"))
        assertEquals("ali@shop.pk", inviteKeyOf("  Ali@Shop.PK "))
        assertNull(inviteKeyOf("@shop.pk"))
        assertNull(inviteKeyOf("123"))
    }
}
