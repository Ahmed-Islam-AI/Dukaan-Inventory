package app.dukaan.android.core.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One database row as column → value. Values are Long, String or null (the schema has nothing else). */
data class SyncRow(val table: String, val values: Map<String, Any?>) {
    val id: String get() = values["id"] as String
}

/**
 * Rows ⇄ gzip bytes for one change document (D-77). A plain binary format: stdlib only, testable
 * on the JVM, and a Firestore Blob so the rows are never indexed field by field.
 */
object RowCodec {
    private const val FORMAT = 1
    private const val NULL: Int = 0
    private const val LONG: Int = 1
    private const val TEXT: Int = 2

    fun encode(rows: List<SyncRow>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(rows.size)
            for (row in rows) {
                out.writeText(row.table)
                out.writeInt(row.values.size)
                for ((column, value) in row.values) {
                    out.writeText(column)
                    when (value) {
                        null -> out.writeByte(NULL)
                        is Long -> { out.writeByte(LONG); out.writeLong(value) }
                        is String -> { out.writeByte(TEXT); out.writeText(value) }
                        else -> throw IllegalArgumentException("${row.table}.$column: unsupported ${value::class}")
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): List<SyncRow> =
        DataInputStream(GZIPInputStream(ByteArrayInputStream(data))).use { input ->
            check(input.readInt() == FORMAT) { "unknown change format" }
            List(input.readInt()) {
                val table = input.readText()
                val values = LinkedHashMap<String, Any?>()
                repeat(input.readInt()) {
                    val column = input.readText()
                    values[column] = when (val tag = input.readByte().toInt()) {
                        NULL -> null
                        LONG -> input.readLong()
                        TEXT -> input.readText()
                        else -> error("bad value tag $tag")
                    }
                }
                SyncRow(table, values)
            }
        }

    // writeUTF stops at 64 KB; a long note must not break a push.
    private fun DataOutputStream.writeText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.readText(): String = String(ByteArray(readInt()).also { readFully(it) }, Charsets.UTF_8)
}
