package app.dukaan.android.core.account

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The shop's cloud copy, packed for Firestore (docs/ACCOUNTS.md § Cloud copy): the WAL-safe
 * snapshot is gzipped and cut into chunks small enough for one document each (limit 1 MiB).
 * Pure, so the round trip is unit-tested.
 */
object CloudCopy {
    const val CHUNK_BYTES = 700_000

    class Packed(val chunks: List<ByteArray>, val bytes: Int, val sha256: String)

    fun pack(snapshot: ByteArray): Packed {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(snapshot) } }.toByteArray()
        val chunks = (gz.indices step CHUNK_BYTES).map { gz.copyOfRange(it, minOf(it + CHUNK_BYTES, gz.size)) }
        return Packed(chunks, gz.size, sha256(gz))
    }

    /** Throws [IllegalStateException] when chunks are missing or corrupt, so a bad copy is never restored. */
    fun unpack(chunks: List<ByteArray>, expectedSha256: String): ByteArray {
        val gz = ByteArrayOutputStream().also { out -> chunks.forEach { out.write(it) } }.toByteArray()
        check(sha256(gz) == expectedSha256) { "cloud copy is damaged" }
        return GZIPInputStream(gz.inputStream()).use { it.readBytes() }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
