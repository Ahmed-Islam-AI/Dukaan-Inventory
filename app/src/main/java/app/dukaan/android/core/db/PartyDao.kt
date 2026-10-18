package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

data class PartyRow(val id: String, val name: String, val phone: String?, val balancePaisa: Long)

/**
 * One line of a party's history. [kind] is SALE, RETURN, PURCHASE or PAY (then [payKind] and
 * [direction] are set). [reversed] = a payment cancelled by a REVERSAL (REVERSAL rows themselves
 * are not listed: the struck-through original says it all).
 */
data class StatementRow(
    val kind: String,
    val refId: String,
    val date: Long,
    val amountPaisa: Long,
    val status: DocStatus,
    val payKind: PaymentKind?,
    val direction: PaymentDirection?,
    val invoicePrefix: String?,
    val invoiceSeq: Int?,
    val note: String?,
    val reversed: Boolean,
)

data class ReturnRow(val id: String, val date: Long, val refundPaisa: Long, val status: DocStatus, val reason: String?, val voidReason: String?)

/** Reads for Parties, sale returns and Expenses (M4). Writes go through LedgerService. */
@Dao
interface PartyDao {

    @Query(
        """SELECT p.id, p.name, p.phone, $PARTY_BALANCE AS balancePaisa FROM party p
           WHERE p.deletedAt IS NULL AND p.type = :type
             AND (:query = '' OR p.name LIKE '%' || :query || '%' OR p.phone LIKE '%' || :query || '%')
           ORDER BY p.name COLLATE NOCASE"""
    )
    fun parties(type: PartyType, query: String): Flow<List<PartyRow>>

    @Query("SELECT * FROM party WHERE id = :id")
    fun party(id: String): Flow<PartyEntity?>

    @Query("SELECT $PARTY_BALANCE FROM party p WHERE p.id = :id")
    fun balance(id: String): Flow<Long?>

    @Query(
        """SELECT 'SALE' AS kind, s.id AS refId, s.date, s.totalPaisa AS amountPaisa, s.status, NULL AS payKind,
                  NULL AS direction, s.invoicePrefix, s.invoiceSeq, s.voidReason AS note, 0 AS reversed
           FROM sale s WHERE s.customerId = :id
           UNION ALL
           SELECT 'RETURN', r.id, r.date, r.refundPaisa, r.status, NULL, NULL, s.invoicePrefix, s.invoiceSeq, r.reason, 0
           FROM sale_return r JOIN sale s ON s.id = r.saleId WHERE s.customerId = :id
           UNION ALL
           SELECT 'PURCHASE', pu.id, pu.date, pu.totalPaisa, pu.status, NULL, NULL, NULL, NULL, pu.billNo, 0
           FROM purchase pu WHERE pu.supplierId = :id
           UNION ALL
           SELECT 'PAY', pay.id, pay.date, pay.amountPaisa, 'ACTIVE', pay.kind, pay.direction, NULL, NULL, pay.note,
                  EXISTS (SELECT 1 FROM payment rv WHERE rv.reversesPaymentId = pay.id)
           FROM payment pay WHERE pay.partyId = :id AND pay.kind != 'REVERSAL'
           ORDER BY date DESC, refId"""
    )
    fun statement(id: String): Flow<List<StatementRow>>

    @Query(
        """SELECT id, date, refundPaisa, status, reason, voidReason FROM sale_return
           WHERE saleId = :saleId ORDER BY date DESC"""
    )
    fun returns(saleId: String): Flow<List<ReturnRow>>

    @Query("SELECT status FROM sale WHERE id = :saleId")
    fun saleStatus(saleId: String): Flow<DocStatus?>

    @Query("SELECT * FROM expense WHERE deletedAt IS NULL ORDER BY date DESC, createdAt DESC")
    fun expenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT DISTINCT category FROM expense WHERE deletedAt IS NULL ORDER BY category COLLATE NOCASE")
    fun expenseCategories(): Flow<List<String>>
}
