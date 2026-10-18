package app.dukaan.android.core.db

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

data class SaleRow(
    val id: String,
    val invoicePrefix: String,
    val invoiceSeq: Int,
    val customerName: String?,
    val date: Long,
    val totalPaisa: Long,
    val paidPaisa: Long,
    val status: DocStatus,
)

/** Reads for the sales list and receipts. Writes go through LedgerService. */
@Dao
interface SaleDao {

    @Query(
        """SELECT s.id, s.invoicePrefix, s.invoiceSeq, p.name AS customerName, s.date, s.totalPaisa,
                  COALESCE((SELECT SUM(CASE WHEN pay.direction = 'IN' THEN pay.amountPaisa ELSE -pay.amountPaisa END)
                            FROM payment pay WHERE pay.saleId = s.id), 0) AS paidPaisa,
                  s.status
           FROM sale s LEFT JOIN party p ON p.id = s.customerId
           ORDER BY s.date DESC, s.createdAt DESC"""
    )
    fun sales(): Flow<List<SaleRow>>

    @Query("SELECT * FROM sale WHERE id = :id")
    suspend fun sale(id: String): SaleEntity?

    @Query("SELECT * FROM sale_line WHERE saleId = :saleId ORDER BY createdAt, id")
    suspend fun lines(saleId: String): List<SaleLineEntity>

    @Query("SELECT * FROM payment WHERE saleId = :saleId ORDER BY createdAt, id")
    suspend fun payments(saleId: String): List<PaymentEntity>

    @Query("SELECT * FROM party WHERE id = :id")
    suspend fun party(id: String): PartyEntity?

    @Query("SELECT serial FROM serial_unit WHERE id = :id")
    suspend fun serialNumber(id: String): String?

    @Query("SELECT p.unit FROM variant v JOIN product p ON p.id = v.productId WHERE v.id = :variantId")
    suspend fun unitOf(variantId: String): String?
}
