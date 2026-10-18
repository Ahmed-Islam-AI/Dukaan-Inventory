package app.dukaan.android.core.ledger

import app.dukaan.android.core.db.AdjustFact
import app.dukaan.android.core.db.AllocationConsumer
import app.dukaan.android.core.db.LineFact
import app.dukaan.android.core.db.LotFact
import app.dukaan.android.core.db.ReturnFact
import app.dukaan.android.core.db.SerialLineFact
import app.dukaan.android.core.db.SerialStatus
import app.dukaan.android.core.db.UnitFact
import app.dukaan.android.core.money.Money
import app.dukaan.android.core.money.Qty

/**
 * Derived stock state as a pure function of the facts (D-75). The one definition of FIFO costing:
 * LedgerService calls it after every stock transaction and sync calls it after merging another
 * phone's rows, so every phone computes the same costs from the same facts, in any arrival order.
 *
 * Events are replayed in time order (ties: kind, then id), each at the moment it happened on its
 * phone (docs/ARCHITECTURE.md § Transactions):
 *
 *   LOT IN      fill pending lines (oldest sale first), the rest is open stock
 *   SELL        FIFO over open lots (receivedAt, createdAt, id); shortfall → pending (D-16)
 *   −ADJUST     FIFO; a shortfall (only possible after a merge) is dropped
 *   RETURN      pending first, then reverse the newest allocations
 *   RETURN VOID FIFO again from today's lots; shortfall → pending (D-58)
 *   SALE VOID   reverse everything, pending 0; the line holds nothing from then on
 *   LOT VOID    its purchase was voided: lot → 0; lines it fed (merge only) take other lots or wait
 *
 * Returns and voids set what a line should hold (sold − active returns, 0 when voided) and the
 * line settles to it, so returns and voids merged from two phones land right in any order.
 */
object StockReplay {

    class Alloc(
        val lotId: String,
        val consumerType: AllocationConsumer,
        val consumerId: String,
        val unitCostPaisa: Long,
        val at: Long,
        val qtyMilli: Long,
        var reversedMilli: Long = 0,
    ) {
        val live get() = qtyMilli - reversedMilli
    }

    data class Quantity(
        val lotRemaining: Map<String, Long>,
        /** In creation order. */
        val allocations: List<Alloc>,
        val linePending: Map<String, Long>,
        val lineCost: Map<String, Long>,
        val returnCostReversed: Map<String, Long>,
    )

    fun quantity(lots: List<LotFact>, lines: List<LineFact>, returns: List<ReturnFact>, adjustments: List<AdjustFact>): Quantity {
        class Lot(val f: LotFact) { var remaining = 0L; var open = false }
        class Line(val f: LineFact) {
            var started = false
            var voided = false
            var returned = 0L
            var pending = 0L
            val allocs = mutableListOf<Alloc>()
            /** What the line holds now: sold − active returns; nothing once its bill is voided. */
            val target get() = if (voided) 0L else maxOf(0L, f.qtyMilli - returned)
        }

        val lotById = lots.associate { it.id to Lot(it) }
        val lineById = lines.associate { it.id to Line(it) }
        val allocations = mutableListOf<Alloc>()
        val costReversed = HashMap<String, Long>()
        val fifo = compareBy<Lot>({ it.f.receivedAt }, { it.f.createdAt }, { it.f.id })
        val oldestSaleFirst = compareBy<Line>({ it.f.saleDate }, { it.f.saleCreatedAt }, { it.f.id })

        fun cost(line: Line) = line.allocs.sumOf { Money(it.unitCostPaisa).times(Qty(it.live)).minor }

        /** FIFO-takes [qty]; returns the shortfall. */
        fun consume(type: AllocationConsumer, consumerId: String, line: Line?, qty: Long, at: Long): Long {
            var left = qty
            // ponytail: re-sorts open lots per event; a TreeSet if a variant ever has thousands of open lots.
            for (lot in lotById.values.filter { it.open && it.remaining > 0 }.sortedWith(fifo)) {
                if (left == 0L) break
                val take = minOf(left, lot.remaining)
                lot.remaining -= take
                left -= take
                val a = Alloc(lot.f.id, type, consumerId, lot.f.unitCostPaisa, at, take)
                allocations += a
                line?.allocs?.add(a)
            }
            return left
        }

        /** Gives up to [qty] back to the lots, newest allocation first. */
        fun reverse(line: Line, qty: Long) {
            var left = qty
            for (a in line.allocs.asReversed()) {
                if (left == 0L) break
                val take = minOf(left, a.live)
                if (take == 0L) continue
                a.reversedMilli += take
                left -= take
                lotById.getValue(a.lotId).remaining += take
            }
        }

        /**
         * Moves a line to what it should hold. Too much: pending first, then the newest allocations
         * go back (a return). Too little: FIFO from today's lots, shortfall pending (sale, return
         * void, D-58). Holding to a target instead of applying deltas keeps a merge of returns and
         * voids from two phones right in any order.
         */
        fun settle(line: Line, at: Long) {
            if (!line.started) return
            val held = line.allocs.sumOf { it.live } + line.pending
            if (held > line.target) {
                val excess = held - line.target
                val fromPending = minOf(excess, line.pending)
                line.pending -= fromPending
                reverse(line, excess - fromPending)
            } else if (held < line.target) {
                line.pending += consume(AllocationConsumer.SALE_LINE, line.f.id, line, line.target - held, at)
            }
        }

        class Event(val at: Long, val rank: Int, val id: String, val run: () -> Unit)
        val events = mutableListOf<Event>()

        for (lot in lotById.values) {
            events += Event(lot.f.createdAt, 0, lot.f.id) {
                lot.open = true
                lot.remaining = lot.f.qtyInMilli
                for (line in lineById.values.filter { it.pending > 0 }.sortedWith(oldestSaleFirst)) {
                    if (lot.remaining == 0L) break
                    val take = minOf(lot.remaining, line.pending)
                    val a = Alloc(lot.f.id, AllocationConsumer.SALE_LINE, line.f.id, lot.f.unitCostPaisa, lot.f.createdAt, take)
                    allocations += a
                    line.allocs += a
                    line.pending -= take
                    lot.remaining -= take
                }
            }
            lot.f.voidedAt?.let { voidedAt ->
                val at = maxOf(voidedAt, lot.f.createdAt)
                events += Event(at, 6, lot.f.id) {
                    lot.remaining = 0
                    lot.open = false
                    val hit = mutableSetOf<Line>()
                    for (a in allocations) {
                        if (a.lotId != lot.f.id || a.live == 0L) continue
                        a.reversedMilli = a.qtyMilli
                        if (a.consumerType == AllocationConsumer.SALE_LINE) lineById[a.consumerId]?.let { hit += it }
                    }
                    hit.forEach { settle(it, at) } // only after a merge: its stock was sold on another phone
                }
            }
        }
        for (line in lineById.values) {
            events += Event(line.f.createdAt, 1, line.f.id) {
                line.started = true
                settle(line, line.f.createdAt)
            }
            line.f.saleVoidedAt?.let { voidedAt ->
                val at = maxOf(voidedAt, line.f.createdAt)
                events += Event(at, 5, line.f.id) {
                    line.voided = true
                    settle(line, at)
                }
            }
        }
        for (adj in adjustments) {
            events += Event(adj.createdAt, 2, adj.id) { consume(AllocationConsumer.ADJUSTMENT, adj.id, null, adj.qtyMilli, adj.createdAt) }
        }
        for (ret in returns) {
            val line = lineById[ret.saleLineId] ?: continue
            val at = maxOf(ret.createdAt, line.f.createdAt)
            events += Event(at, 3, ret.id) {
                val before = cost(line)
                line.returned += ret.qtyMilli
                settle(line, at)
                costReversed[ret.id] = before - cost(line)
            }
            ret.voidedAt?.let { voidedAt ->
                val voidAt = maxOf(voidedAt, at)
                events += Event(voidAt, 4, ret.id) {
                    line.returned -= ret.qtyMilli
                    settle(line, voidAt)
                }
            }
        }

        events.sortedWith(compareBy<Event>({ it.at }, { it.rank }, { it.id })).forEach { it.run() }

        return Quantity(
            lotRemaining = lotById.mapValues { it.value.remaining },
            allocations = allocations,
            linePending = lineById.mapValues { it.value.pending },
            lineCost = lineById.mapValues { cost(it.value) },
            returnCostReversed = costReversed,
        )
    }

    data class Serial(val status: Map<String, SerialStatus>, val lineCost: Map<String, Long>)

    /**
     * IMEI status from the documents, not from history order: a voided purchase or a take-out
     * wins, else SOLD while an active, unreturned sale line holds the unit (two lines holding one
     * unit = sold on two phones, shown to the owner), else IN_STOCK.
     */
    fun serial(units: List<UnitFact>, lines: List<SerialLineFact>): Serial {
        val held = lines.filter { it.active && it.returnedMilli < 1000 }.map { it.serialUnitId }.toSet()
        val cost = units.associate { it.id to it.costPaisa }
        return Serial(
            status = units.associate { u ->
                u.id to when {
                    u.purchaseVoided -> SerialStatus.VOIDED
                    u.removedStatus != null -> u.removedStatus
                    u.id in held -> SerialStatus.SOLD
                    else -> SerialStatus.IN_STOCK
                }
            },
            // A returned unit's line costs nothing; a voided sale keeps its cost (as before D-75).
            lineCost = lines.associate { l -> l.id to if (l.returnedMilli >= 1000) 0L else cost[l.serialUnitId] ?: l.costPaisa },
        )
    }
}
