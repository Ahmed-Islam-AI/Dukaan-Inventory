package app.dukaan.android.core.backup

import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.dukaan.android.core.db.DukaanDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Why an import was refused. Each maps to its own user message (PLAN.md § Error handling). */
sealed class ImportError(message: String) : Exception(message) {
    class NotABackup : ImportError("not a Dukaan backup")
    class NewerVersion(val version: Int) : ImportError("backup schema $version is newer than this app")
    class Damaged : ImportError("backup database failed integrity check")
}

/**
 * Data safety (D-11, D-28):
 *
 *   snapshot():  VACUUM INTO backup/dukaan.db.tmp ──▶ PRAGMA integrity_check == ok ──▶ rename to dukaan.db
 *                A kill at any step leaves the previous good dukaan.db. Android Auto Backup uploads
 *                only that file (backup rules in res/xml), never the live WAL database.
 *   export():    fresh snapshot + manifest.properties, zipped into the user's chosen file (.dukaan)
 *   validate():  unzip ──▶ manifest says Dukaan, schema ≤ ours ──▶ integrity_check ──▶ has a shop
 *                The caller swaps it in only after this passes (AppContainer.replaceDatabase).
 *
 * Uses the bundled SQLite driver throughout (D-32), so it behaves the same on Android 8 and in tests.
 */
class BackupManager(
    private val db: DukaanDatabase,
    private val backupDir: File,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val snapshotFile get() = File(backupDir, SNAPSHOT)

    /** Takes a snapshot if the last one is older than [minIntervalMillis]. Returns true if taken. */
    suspend fun snapshotIfDue(minIntervalMillis: Long = 60 * 60 * 1000L): Boolean {
        val last = snapshotFile.takeIf { it.exists() }?.lastModified() ?: 0L
        if (clock() - last < minIntervalMillis) return false
        snapshot()
        return true
    }

    /** Writes a consistent copy of the live database to [snapshotFile], atomically. */
    suspend fun snapshot() {
        backupDir.mkdirs()
        val tmp = File(backupDir, "$SNAPSHOT.tmp")
        tmp.delete()
        try {
            vacuumInto(tmp)
            if (!isIntact(tmp)) throw IOException("snapshot failed integrity check")
            // Atomic replace: readers (and Auto Backup) see the old file or the new one, never half.
            Files.move(tmp.toPath(), snapshotFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    /** Zips a fresh snapshot and its manifest into [out]. */
    suspend fun export(out: OutputStream) {
        snapshot()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            Properties().apply {
                setProperty("app", APP_ID)
                setProperty("schemaVersion", SCHEMA_VERSION.toString())
                setProperty("appVersion", appVersion)
                setProperty("createdAt", clock().toString())
            }.store(zip, null)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            snapshotFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /**
     * Unzips [input] into [workDir] and checks it. Returns the validated database file, ready to
     * be swapped in. Throws [ImportError] and leaves the live database untouched otherwise.
     */
    fun validate(input: InputStream, workDir: File): File {
        workDir.deleteRecursively()
        workDir.mkdirs()
        var manifest: Properties? = null
        val dbFile = File(workDir, DB_ENTRY)
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        MANIFEST -> manifest = Properties().apply { load(zip) }
                        DB_ENTRY -> dbFile.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        } catch (e: IOException) {
            throw ImportError.NotABackup()
        }
        val m = manifest ?: throw ImportError.NotABackup()
        if (m.getProperty("app") != APP_ID || !dbFile.exists()) throw ImportError.NotABackup()
        val version = m.getProperty("schemaVersion")?.toIntOrNull() ?: throw ImportError.NotABackup()
        if (version > SCHEMA_VERSION) throw ImportError.NewerVersion(version)
        if (!isIntact(dbFile)) throw ImportError.Damaged()
        if (!hasShop(dbFile)) throw ImportError.NotABackup()
        return dbFile
    }

    private suspend fun vacuumInto(target: File) {
        db.useWriterConnection { connection ->
            connection.usePrepared("VACUUM INTO ?") { stmt ->
                stmt.bindText(1, target.absolutePath)
                stmt.step()
            }
        }
    }

    companion object {
        const val SNAPSHOT = "dukaan.db"
        const val APP_ID = "app.dukaan"
        const val MANIFEST = "manifest.properties"
        const val DB_ENTRY = "dukaan.db"
        const val SCHEMA_VERSION = 1 // keep equal to DukaanDatabase version

        /** PRAGMA integrity_check on a closed database file; false for garbage or a damaged file. */
        fun isIntact(file: File): Boolean = try {
            BundledSQLiteDriver().open(file.absolutePath).use { c ->
                c.prepare("PRAGMA integrity_check").use { s -> s.step() && s.getText(0) == "ok" }
            }
        } catch (e: Exception) { // SQLiteException is platform-specific; any open/read failure = not intact
            false
        }

        private fun hasShop(file: File): Boolean = try {
            BundledSQLiteDriver().open(file.absolutePath).use { c ->
                c.prepare("SELECT COUNT(*) FROM shop_profile").use { s -> s.step() && s.getLong(0) > 0 }
            }
        } catch (e: Exception) {
            false
        }

        /**
         * After a reinstall, Android Auto Backup restores only backup/dukaan.db. If there is no live
         * database yet, start from that snapshot. Returns true if it restored.
         */
        fun restoreSnapshotIfFresh(liveDb: File, snapshot: File): Boolean {
            if (liveDb.exists() || !snapshot.exists() || !isIntact(snapshot)) return false
            liveDb.parentFile?.mkdirs()
            snapshot.copyTo(liveDb)
            return true
        }
    }
}
