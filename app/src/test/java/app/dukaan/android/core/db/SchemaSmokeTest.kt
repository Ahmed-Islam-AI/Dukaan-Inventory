package app.dukaan.android.core.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Real Room + real SQLite on the JVM. Proves schema v1 opens and round-trips embedded SyncMeta. */
class SchemaSmokeTest {

    private lateinit var db: DukaanDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<DukaanDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `fresh database has no shop profile, so onboarding shows`() = runTest {
        assertNull(db.shopDao().get())
    }

    @Test
    fun `shop profile round-trips`() = runTest {
        val profile = ShopProfileEntity(
            id = "shop-1",
            name = "Ali Mobiles",
            shopType = "mobile",
            phone = "0300-1234567",
            address = null,
            logoPath = null,
            currencyCode = "PKR",
            currencySymbol = "Rs",
            timeZone = "Asia/Karachi",
            taxRateBp = null,
            receiptFooter = "شکریہ",
            meta = SyncMeta(createdAt = 1L, updatedAt = 1L, deviceId = "dev-A"),
        )
        db.shopDao().insert(profile)
        assertEquals(profile, db.shopDao().get())
    }
}
