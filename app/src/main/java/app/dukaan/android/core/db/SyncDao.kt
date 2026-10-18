package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Outbox and pull position for live sync (core/sync, D-77). */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_outbox ORDER BY seq LIMIT :limit")
    suspend fun outbox(limit: Int): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE seq <= :seq")
    suspend fun pushed(seq: Long)

    /** Rows queued while applying another phone's changes are theirs, not ours: never echo them. */
    @Query("DELETE FROM sync_outbox WHERE seq > :seq")
    suspend fun dropAfter(seq: Long)

    @Query("DELETE FROM sync_outbox")
    suspend fun clearOutbox()

    @Query("SELECT COALESCE(MAX(seq), 0) FROM sync_outbox")
    suspend fun lastSeq(): Long

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun waiting(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun waitingNow(): Int

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun state(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setState(state: SyncStateEntity)
}
