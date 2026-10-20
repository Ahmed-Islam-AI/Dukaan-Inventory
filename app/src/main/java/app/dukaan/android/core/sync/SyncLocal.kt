package app.dukaan.android.core.sync

import androidx.room3.PooledConnection
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import app.dukaan.android.core.db.DukaanDatabase
import app.dukaan.android.core.db.SyncStateEntity
import app.dukaan.android.core.ledger.StockRebuild

/**
 * The database half of live sync (D-77), no network: packs this phone's queued rows into one
 * change, and merges another phone's change. Fully testable on the JVM with two databases.
 *
 *   push: sync_outbox ──▶ current rows ──▶ RowCodec bytes ──▶ (cloud) ──▶ pushed(upToSeq)
 *   pull: (cloud) bytes ──▶ upsert, newest updatedAt wins ──▶ StockRebuild(touched variants)
 *         ──▶ drop what the merge itself queued (never echo another phone's rows)
 */
class SyncLocal(private val db: DukaanDatabase) {
    private val dao get() = db.syncDao()
    private val rebuild = StockRebuild(db)
    private val columns = HashMap<String, Set<String>>()

    class Batch(val bytes: ByteArray, val rows: Int, val upToSeq: Long)
    class Applied(val rows: Int, val skipped: Int)

    /** The next change to push (at most [maxEntries] queued rows), or null when nothing waits. */
    suspend fun nextBatch(maxEntries: Int = 400): Batch? = db.withReadTransaction {
        val entries = dao.outbox(maxEntries)
        if (entries.isEmpty()) return@withReadTransaction null
        val rows = entries.filter { it.tableName in SyncTables.DERIVED }
            .groupBy({ it.tableName }, { it.rowId })
            .flatMap { (table, ids) -> ids.distinct().chunked(500).flatMap { select(table, it) } }
        Batch(RowCodec.encode(rows), rows.size, entries.last().seq)
    }

    suspend fun pushed(upToSeq: Long) = dao.pushed(upToSeq)

    /** Merges another phone's change in one transaction. Rows the schema can't take are skipped. */
    suspend fun apply(data: ByteArray): Applied {
        val rows = RowCodec.decode(data)
        return db.withWriteTransaction {
            val before = dao.lastSeq()
            var skipped = 0
            for (row in rows) {
                val derived = SyncTables.DERIVED[row.table] ?: continue
                val local = columns(row.table)
                val values = row.values.filterKeys { it in local }.toMutableMap()
                // Derived and unique: a stale value could collide; the rebuild sets it (D-35).
                if (row.table == "serial_unit") values["liveSerial"] = null
                if (!upsert(row.table, values, derived)) skipped++
            }
            rebuild.variants(touchedVariants(rows))
            dao.dropAfter(before)
            Applied(rows.size, skipped)
        }
    }

    /** First sync on this database: queue every existing row once (the client's data, D-77). */
    suspend fun seedIfNeeded() {
        db.withWriteTransaction {
            val state = dao.state()
            if (state?.seeded == true) return@withWriteTransaction
            dao.clearOutbox()
            SyncTables.DERIVED.keys.forEach { t ->
                usePrepared("INSERT INTO sync_outbox (tableName, rowId) SELECT '$t', id FROM `$t` ORDER BY createdAt") { it.step() }
            }
            dao.setState(SyncStateEntity(pulledUpTo = state?.pulledUpTo ?: 0, seeded = true))
        }
    }

    suspend fun pulledUpTo(): Long = dao.state()?.pulledUpTo ?: 0

    suspend fun setPulledUpTo(micros: Long) {
        db.withWriteTransaction {
            val state = dao.state()
            if (micros > (state?.pulledUpTo ?: 0)) dao.setState(SyncStateEntity(pulledUpTo = micros, seeded = state?.seeded ?: false))
        }
    }

    // ---------------------------------------------------------------- SQL

    private suspend fun PooledConnection.select(table: String, ids: List<String>): List<SyncRow> =
        usePrepared("SELECT * FROM `$table` WHERE id IN (${ids.joinToString { "?" }})") { st ->
            ids.forEachIndexed { i, id -> st.bindText(i + 1, id) }
            buildList { while (st.step()) add(SyncRow(table, st.row())) }
        }

    private fun SQLiteStatement.row(): Map<String, Any?> = LinkedHashMap<String, Any?>().also { m ->
        for (i in 0 until getColumnCount()) {
            m[getColumnName(i)] = when (getColumnType(i)) {
                SQLITE_DATA_NULL -> null
                SQLITE_DATA_INTEGER -> getLong(i)
                SQLITE_DATA_TEXT -> getText(i)
                else -> error("unexpected column type in ${getColumnName(i)}")
            }
        }
    }

    private suspend fun PooledConnection.columns(table: String): Set<String> = columns.getOrPut(table) {
        usePrepared("PRAGMA table_info(`$table`)") { st -> buildSet { while (st.step()) add(st.getText(1)) } }
    }

    /**
     * Insert, or replace the facts when the incoming row is newer (updatedAt, then deviceId so
     * every phone picks the same winner). Derived columns are never overwritten. False = refused
     * by a constraint (e.g. a second reversal of one payment made on two phones; D-76).
     */
    private suspend fun PooledConnection.upsert(table: String, values: Map<String, Any?>, derived: Set<String>): Boolean {
        val names = values.keys.toList()
        val set = names.filter { it != "id" && it !in derived }.joinToString { "`$it` = excluded.`$it`" }
        val sql = "INSERT INTO `$table` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }}) " +
            "ON CONFLICT(id) DO UPDATE SET $set " +
            "WHERE excluded.updatedAt > `$table`.updatedAt OR (excluded.updatedAt = `$table`.updatedAt AND excluded.deviceId > `$table`.deviceId)"
        return try {
            usePrepared(sql) { st ->
                names.forEachIndexed { i, n ->
                    when (val v = values[n]) {
                        null -> st.bindNull(i + 1)
                        is Long -> st.bindLong(i + 1, v)
                        is String -> st.bindText(i + 1, v)
                        else -> error("unsupported value in $table.$n")
                    }
                }
                st.step()
            }
            true
        } catch (e: SQLiteException) {
            false
        }
    }

    /** Variants whose derived stock may have changed because of these rows. */
    private suspend fun PooledConnection.touchedVariants(rows: List<SyncRow>): Set<String> {
        val ids = HashSet<String>()
        suspend fun query(sql: String, arg: Any?) {
            if (arg !is String) return
            usePrepared(sql) { st -> st.bindText(1, arg); while (st.step()) if (!st.isNull(0)) ids += st.getText(0) }
        }
        for (r in rows) when (r.table) {
            "variant" -> ids += r.id
            "stock_lot", "serial_unit", "sale_line", "purchase_line", "stock_adjustment", "stock_movement" ->
                (r.values["variantId"] as? String)?.let { ids += it }
            "product" -> query("SELECT id FROM variant WHERE productId = ?", r.id)
            "sale" -> query("SELECT variantId FROM sale_line WHERE saleId = ?", r.id)
            "purchase" -> query("SELECT variantId FROM purchase_line WHERE purchaseId = ?", r.id)
            "sale_return" -> query("SELECT sl.variantId FROM sale_return_line rl JOIN sale_line sl ON sl.id = rl.saleLineId WHERE rl.returnId = ?", r.id)
            "sale_return_line" -> query("SELECT variantId FROM sale_line WHERE id = ?", r.values["saleLineId"])
            "serial_event" -> query("SELECT variantId FROM serial_unit WHERE id = ?", r.values["serialUnitId"])
        }
        return ids
    }
}
