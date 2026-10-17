package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Update

@Database(
    entities = [
        ShopProfileEntity::class,
        CategoryEntity::class,
        ProductEntity::class,
        VariantEntity::class,
        PartyEntity::class,
        PurchaseEntity::class,
        PurchaseLineEntity::class,
        StockLotEntity::class,
        SerialUnitEntity::class,
        SerialEventEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        LotAllocationEntity::class,
        SaleReturnEntity::class,
        SaleReturnLineEntity::class,
        StockAdjustmentEntity::class,
        PaymentEntity::class,
        ExpenseEntity::class,
        StockMovementEntity::class,
        SyncOutboxEntity::class,
        SyncStateEntity::class,
    ],
    version = DukaanDatabase.VERSION,
    exportSchema = true,
)
abstract class DukaanDatabase : RoomDatabase() {
    abstract fun shopDao(): ShopDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun catalogDao(): CatalogDao
    abstract fun auditDao(): AuditDao
    abstract fun saleDao(): SaleDao
    abstract fun partyDao(): PartyDao
    abstract fun reportDao(): ReportDao
    abstract fun rebuildDao(): RebuildDao
    abstract fun syncDao(): SyncDao
    abstract fun clashDao(): ClashDao
    abstract fun pickDao(): PickDao

    companion object {
        const val NAME = "dukaan.db"
        /** Schema version; also stamped on cloud copies so a newer copy is never restored by an older app. */
        const val VERSION = 4
    }
}

@Dao
interface ShopDao {
    @Insert
    suspend fun insert(profile: ShopProfileEntity)

    /** A saved profile IS the "onboarding done" flag; no second source of truth. */
    @Query("SELECT * FROM shop_profile WHERE deletedAt IS NULL LIMIT 1")
    suspend fun get(): ShopProfileEntity?

    /** Owner edits (invoice design); the caller bumps updatedAt so the change syncs. */
    @Update
    suspend fun update(profile: ShopProfileEntity)

    @Query("SELECT * FROM shop_profile WHERE deletedAt IS NULL LIMIT 1")
    fun observe(): kotlinx.coroutines.flow.Flow<ShopProfileEntity?>
}
