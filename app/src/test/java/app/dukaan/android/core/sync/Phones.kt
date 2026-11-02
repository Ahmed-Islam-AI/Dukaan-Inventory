package app.dukaan.android.core.sync

import androidx.room3.Room
import androidx.room3.useReaderConnection
import app.dukaan.android.core.db.DukaanDatabase
import app.dukaan.android.core.db.PaymentMode
import app.dukaan.android.core.db.ShopProfileEntity
import app.dukaan.android.core.db.SyncMeta
import app.dukaan.android.core.ledger.InvariantChecker
import app.dukaan.android.core.ledger.LedgerService
import app.dukaan.android.core.ledger.SaleIn
import app.dukaan.android.core.ledger.SaleLineIn
import app.dukaan.android.core.ledger.SaleMath
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty
import java.time.ZoneId

/** One shared clock: every phone's actions happen at distinct, increasing moments. */
class Clock(var now: Long = 1_760_000_000_000L) {
    fun tick() = now++
}

/** A phone of the shop: its own database, device id and id sequence. */
class Phone(val name: String, clock: Clock) {
    val db: DukaanDatabase = SyncTables.configure(Room.inMemoryDatabaseBuilder<DukaanDatabase>()).build()
    private var ids = 0
    val ledger = LedgerService(db, deviceId = { name }, zone = { ZoneId.of("Asia/Karachi") }, clock = clock::tick, newId = { "$name-${++ids}" })
    val sync = SyncLocal(db)
    /** Index of the next cloud change to pull (the test's stand-in for pulledUpTo). */
    var cursor = 0

    suspend fun shop() = db.shopDao().insert(
        ShopProfileEntity("shop", "Ali Mobiles", "mobiles", null, null, null, "PKR", "Rs", "Asia/Karachi", null, null, SyncMeta(1, 1, null, name))
    )

    fun sale(vararg lines: SaleLineIn, prefix: String = name, customer: String? = null, paid: Money? = null): SaleIn {
        val total = SaleMath.totals(lines.map { it.unitPrice to if (it.serialUnitId != null) Qty.ONE else it.qty }, Money.ZERO, null).total
        return SaleIn(prefix, customer, 1_760_000_000_000L, lines.toList(), Money.ZERO, paid ?: total, PaymentMode.CASH)
    }

    suspend fun violations() = InvariantChecker(db.auditDao()).violations()

    /** Every synced table plus allocations, row by row, derived columns included. */
    suspend fun digest(): String = db.useReaderConnection { c ->
        val out = StringBuilder()
        for (t in SyncTables.DERIVED.keys + "lot_allocation") {
            c.usePrepared("SELECT * FROM `$t` ORDER BY id") { st ->
                while (st.step()) {
                    out.append(t).append(':')
                    for (i in 0 until st.getColumnCount()) out.append(if (st.isNull(i)) "∅" else st.getText(i)).append('|')
                    out.append('\n')
                }
            }
        }
        out.toString()
    }

    fun close() = db.close()
}

/** The shop's change log in Firestore, as a list. */
class Cloud {
    val changes = mutableListOf<Pair<String, ByteArray>>()

    suspend fun push(p: Phone) {
        while (true) {
            val batch = p.sync.nextBatch() ?: break
            changes += p.name to batch.bytes
            p.sync.pushed(batch.upToSeq)
        }
    }

    suspend fun pull(p: Phone) {
        while (p.cursor < changes.size) {
            val (by, bytes) = changes[p.cursor++]
            if (by != p.name) p.sync.apply(bytes)
        }
    }

    /** Everyone pushes, then everyone pulls; twice, so late pushes reach everybody. */
    suspend fun syncAll(vararg phones: Phone) {
        repeat(2) {
            phones.forEach { push(it) }
            phones.forEach { pull(it) }
        }
    }
}
