package app.dukaan.android.core.sync

import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.dukaan.android.core.db.DukaanDatabase

/**
 * Which tables sync, and which of their columns are derived (rebuilt on each phone by
 * StockRebuild, never taken from another phone). lot_allocation is entirely derived and
 * does not sync (D-75).
 */
object SyncTables {
    val DERIVED: Map<String, Set<String>> = linkedMapOf(
        "shop_profile" to emptySet(),
        "category" to emptySet(),
        "product" to emptySet(),
        "variant" to setOf("onHandMilli"),
        "party" to emptySet(),
        "purchase" to emptySet(),
        "purchase_line" to emptySet(),
        "stock_lot" to setOf("qtyRemainingMilli"),
        "serial_unit" to setOf("status", "liveSerial"),
        "serial_event" to emptySet(),
        "sale" to emptySet(),
        "sale_line" to setOf("costPaisa", "pendingQtyMilli"),
        "sale_return" to emptySet(),
        "sale_return_line" to setOf("costReversedPaisa"),
        "stock_adjustment" to emptySet(),
        "payment" to emptySet(),
        "expense" to emptySet(),
        "stock_movement" to emptySet(),
    )

    /**
     * Every insert, and every update that moves updatedAt, queues the row for pushing. Derived
     * writes leave updatedAt alone, so a rebuild never queues anything. Triggers are not part of
     * Room's schema check; they are (re)created on every open, so restored copies get them too.
     */
    private val triggers: List<String> = DERIVED.keys.flatMap { t ->
        listOf(
            "CREATE TRIGGER IF NOT EXISTS sync_ins_$t AFTER INSERT ON $t BEGIN " +
                "INSERT INTO sync_outbox(tableName, rowId) VALUES ('$t', NEW.id); END",
            "CREATE TRIGGER IF NOT EXISTS sync_upd_$t AFTER UPDATE ON $t WHEN NEW.updatedAt IS NOT OLD.updatedAt BEGIN " +
                "INSERT INTO sync_outbox(tableName, rowId) VALUES ('$t', NEW.id); END",
        )
    }

    private val callback = object : RoomDatabase.Callback() {
        override suspend fun onOpen(connection: SQLiteConnection) {
            triggers.forEach { connection.execSQL(it) }
        }
    }

    /** v1 → v2 (M7 phase 3): outbox + sync state; sale number index no longer unique (D-77). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tableName` TEXT NOT NULL, `rowId` TEXT NOT NULL)")
            connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`id` INTEGER NOT NULL, `pulledUpTo` INTEGER NOT NULL, `seeded` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            connection.execSQL("DROP INDEX IF EXISTS `index_sale_invoicePrefix_invoiceSeq`")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_invoicePrefix_invoiceSeq` ON `sale` (`invoicePrefix`, `invoiceSeq`)")
        }
    }

    /** v2 → v3: invoice design on the shop row (D-80). */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `shop_profile` ADD COLUMN `pageTemplate` TEXT NOT NULL DEFAULT 'MODERN'")
            connection.execSQL("ALTER TABLE `shop_profile` ADD COLUMN `slipTemplate` TEXT NOT NULL DEFAULT 'CLASSIC'")
            connection.execSQL("ALTER TABLE `shop_profile` ADD COLUMN `accentRgb` INTEGER NOT NULL DEFAULT 15227148")
            connection.execSQL("ALTER TABLE `shop_profile` ADD COLUMN `taxNumber` TEXT")
            connection.execSQL("ALTER TABLE `shop_profile` ADD COLUMN `invoiceTerms` TEXT")
            connection.execSQL("ALTER TABLE `shop_profile` ADD COLUMN `logoPng` TEXT")
        }
    }

    /**
     * v3 → v4: each item's usual purchase price (D-85), filled from its newest batch so existing
     * items show profit at once. Same values on every phone, so it needs no sync.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `variant` ADD COLUMN `purchasePricePaisa` INTEGER")
            connection.execSQL(
                """UPDATE variant SET purchasePricePaisa = COALESCE(
                     (SELECT l.unitCostPaisa FROM stock_lot l WHERE l.variantId = variant.id ORDER BY l.receivedAt DESC, l.createdAt DESC, l.id DESC LIMIT 1),
                     (SELECT s.costPaisa FROM serial_unit s WHERE s.variantId = variant.id ORDER BY s.createdAt DESC, s.id DESC LIMIT 1))"""
            )
        }
    }

    /** Every DukaanDatabase (app and tests) is built through here: driver, migrations, triggers. */
    fun configure(builder: RoomDatabase.Builder<DukaanDatabase>): RoomDatabase.Builder<DukaanDatabase> =
        // Bundled, not the phone's SQLite: Android 8-10 lack VACUUM INTO (D-32).
        builder.setDriver(BundledSQLiteDriver()).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).addCallback(callback)
}
