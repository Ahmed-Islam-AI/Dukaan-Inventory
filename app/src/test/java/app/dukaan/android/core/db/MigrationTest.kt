package app.dukaan.android.core.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.dukaan.android.core.sync.SyncTables
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CLAUDE.md rule 7: a real v1 database (built from the committed app/schemas 1.json) opens on the
 * current version with its data intact. Room checks the migrated schema against the entities.
 */
class MigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Every CREATE statement of a schema version, table names filled in, plus Room's own setup. */
    private fun schema(version: Int): List<String> {
        val json = File("schemas/${DukaanDatabase::class.qualifiedName}/$version.json").readText()
        val statements = mutableListOf<String>()
        // Each entity block starts at its tableName; its table and index statements follow.
        json.split("\"tableName\": \"").drop(1).forEach { block ->
            val table = block.substringBefore('"')
            Regex("\"createSql\": \"([^\"]*)\"").findAll(block).forEach { statements += it.groupValues[1].replace("\${TABLE_NAME}", table) }
        }
        val setup = json.substringAfter("\"setupQueries\": [").substringBefore("]")
        Regex("\"([^\"]*)\"").findAll(setup).forEach { statements += it.groupValues[1] }
        return statements
    }

    @Test
    fun `v3 items get their purchase price from their newest batch`() = runTest {
        val file = File(tmp.root, "v3.db")
        BundledSQLiteDriver().open(file.absolutePath).use { c ->
            schema(3).forEach { c.execSQL(it) }
            c.execSQL("INSERT INTO product (id, name, categoryId, trackingType, unit, allowDecimalQty, createdAt, updatedAt, deletedAt, deviceId) VALUES ('p1', 'Charger', NULL, 'QUANTITY', 'pcs', 0, 1, 1, NULL, 'dev-A')")
            c.execSQL("INSERT INTO variant (id, productId, label, sku, barcode, salePricePaisa, lowStockMilli, onHandMilli, createdAt, updatedAt, deletedAt, deviceId) VALUES ('v1', 'p1', 'Default', NULL, NULL, 120000, NULL, 20000, 1, 1, NULL, 'dev-A')")
            c.execSQL("INSERT INTO stock_lot (id, variantId, sourceType, sourceId, qtyInMilli, qtyRemainingMilli, unitCostPaisa, receivedAt, createdAt, updatedAt, deletedAt, deviceId) VALUES ('l1', 'v1', 'OPENING', NULL, 10000, 10000, 80000, 1, 1, 1, NULL, 'dev-A')")
            c.execSQL("INSERT INTO stock_lot (id, variantId, sourceType, sourceId, qtyInMilli, qtyRemainingMilli, unitCostPaisa, receivedAt, createdAt, updatedAt, deletedAt, deviceId) VALUES ('l2', 'v1', 'OPENING', NULL, 10000, 10000, 90000, 2, 2, 2, NULL, 'dev-A')")
            c.execSQL("PRAGMA user_version = 3")
        }
        val db = SyncTables.configure(Room.databaseBuilder<DukaanDatabase>(name = file.absolutePath)).build()
        try {
            assertEquals(90000L, db.ledgerDao().variant("v1")!!.purchasePricePaisa)
            // A backfill on every phone, not an edit: nothing is queued to sync.
            assertEquals(0, db.syncDao().waitingNow())
        } finally {
            db.close()
        }
    }

    @Test
    fun `v2 shop gets the default invoice design`() = runTest {
        val file = File(tmp.root, "v2.db")
        BundledSQLiteDriver().open(file.absolutePath).use { c ->
            schema(2).forEach { c.execSQL(it) }
            c.execSQL(
                """INSERT INTO shop_profile (id, name, shopType, phone, address, logoPath, currencyCode, currencySymbol, timeZone,
                   taxRateBp, receiptFooter, createdAt, updatedAt, deletedAt, deviceId)
                   VALUES ('shop', 'Ali Mobiles', 'mobiles', NULL, NULL, NULL, 'PKR', 'Rs', 'Asia/Karachi', NULL, 'Shukriya', 1, 1, NULL, 'dev-A')"""
            )
            c.execSQL("PRAGMA user_version = 2")
        }
        val db = SyncTables.configure(Room.databaseBuilder<DukaanDatabase>(name = file.absolutePath)).build()
        try {
            val shop = db.shopDao().get()!!
            assertEquals("Shukriya", shop.receiptFooter)
            assertEquals(PageTemplate.MODERN, shop.pageTemplate)
            assertEquals(SlipTemplate.CLASSIC, shop.slipTemplate)
            assertEquals(0xE8590C, shop.accentRgb)
            assertEquals(null, shop.logoPng)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v1 shop opens with its sales, sync triggers and a non-unique bill number`() = runTest {
        val file = File(tmp.root, "dukaan.db")
        BundledSQLiteDriver().open(file.absolutePath).use { c ->
            schema(1).forEach { c.execSQL(it) }
            c.execSQL(
                """INSERT INTO sale (id, invoicePrefix, invoiceSeq, customerId, date, businessDay, subtotalPaisa, discountPaisa,
                   taxRateBp, taxPaisa, totalPaisa, status, voidReason, voidedAt, createdAt, updatedAt, deletedAt, deviceId)
                   VALUES ('s1', 'A', 1, NULL, 1, 20251009, 500, 0, NULL, 0, 500, 'ACTIVE', NULL, NULL, 1, 1, NULL, 'dev-A')"""
            )
            c.execSQL("PRAGMA user_version = 1")
        }

        val db = SyncTables.configure(Room.databaseBuilder<DukaanDatabase>(name = file.absolutePath)).build()
        try {
            val old = db.ledgerDao().sale("s1")!!
            assertEquals(500L, old.totalPaisa)
            // Another phone's bill with the same number can now be merged (D-77).
            db.ledgerDao().insertSale(old.copy(id = "s2", meta = old.meta.copy(deviceId = "dev-B")))
            // ...and the insert was queued for pushing by the sync trigger.
            assertEquals(1, db.syncDao().waitingNow())
            assertEquals(null, db.syncDao().state())
        } finally {
            db.close()
        }
    }
}
