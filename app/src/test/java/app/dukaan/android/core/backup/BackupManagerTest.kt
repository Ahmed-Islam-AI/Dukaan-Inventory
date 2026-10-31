package app.dukaan.android.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.dukaan.android.core.ledger.LedgerFixture
import app.dukaan.android.core.ledger.LedgerFixture.Companion.rs
import app.dukaan.android.core.ledger.LedgerFixture.Companion.units
import app.dukaan.android.core.ledger.OpeningStock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real SQLite files on the JVM: snapshot, export → validate round trip, every refusal. */
class BackupManagerTest {

    private lateinit var f: LedgerFixture
    private lateinit var dir: File
    private lateinit var backup: BackupManager
    private var clock = 10_000_000L

    @Before
    fun setUp() = runTest {
        f = LedgerFixture()
        f.shop()
        f.quantityItem("Charger 25W", rs(1_200), OpeningStock.Quantity(units(7), rs(800)))
        dir = Files.createTempDirectory("dukaan-backup").toFile()
        backup = BackupManager(f.db, File(dir, "backup"), "0.3.0", clock = { clock })
    }

    @After
    fun tearDown() {
        f.close()
        dir.deleteRecursively()
    }

    private fun count(file: File, table: String): Long =
        BundledSQLiteDriver().open(file.absolutePath).use { c ->
            c.prepare("SELECT COUNT(*) FROM $table").use { it.step(); it.getLong(0) }
        }

    @Test
    fun `snapshot is a complete, intact copy and leaves no temp file`() = runTest {
        backup.snapshot()
        assertTrue(BackupManager.isIntact(backup.snapshotFile))
        assertEquals(1, count(backup.snapshotFile, "shop_profile"))
        assertEquals(1, count(backup.snapshotFile, "stock_lot"))
        assertEquals(listOf("dukaan.db"), backup.snapshotFile.parentFile!!.list()!!.toList())
    }

    @Test
    fun `snapshotIfDue is throttled to once an hour`() = runTest {
        assertTrue(backup.snapshotIfDue())
        backup.snapshotFile.setLastModified(clock)
        clock += 10 * 60 * 1000L
        assertFalse(backup.snapshotIfDue())
        clock += 60 * 60 * 1000L
        assertTrue(backup.snapshotIfDue())
    }

    @Test
    fun `export then validate round-trips`() = runTest {
        val out = ByteArrayOutputStream()
        backup.export(out)
        val file = backup.validate(ByteArrayInputStream(out.toByteArray()), File(dir, "import"))
        assertEquals(1, count(file, "stock_lot"))
    }

    @Test(expected = ImportError.NotABackup::class)
    fun `random bytes are not a backup`() {
        backup.validate(ByteArrayInputStream(ByteArray(2_000) { it.toByte() }), File(dir, "import"))
    }

    @Test(expected = ImportError.NewerVersion::class)
    fun `backup from a newer app version is refused`() = runTest {
        backup.snapshot()
        val zip = zipOf(manifest(schemaVersion = 99), backup.snapshotFile.readBytes())
        backup.validate(ByteArrayInputStream(zip), File(dir, "import"))
    }

    @Test(expected = ImportError.Damaged::class)
    fun `damaged database inside a valid zip is refused`() {
        val zip = zipOf(manifest(), ByteArray(8_192) { 7 })
        backup.validate(ByteArrayInputStream(zip), File(dir, "import"))
    }

    @Test(expected = ImportError.NotABackup::class)
    fun `another app's zip is refused`() = runTest {
        backup.snapshot()
        val zip = zipOf(manifest(app = "com.other"), backup.snapshotFile.readBytes())
        backup.validate(ByteArrayInputStream(zip), File(dir, "import"))
    }

    @Test
    fun `fresh install restores from the auto-backup snapshot only when there is no live database`() = runTest {
        backup.snapshot()
        val live = File(dir, "databases/dukaan.db")
        assertTrue(BackupManager.restoreSnapshotIfFresh(live, backup.snapshotFile))
        assertEquals(1, count(live, "shop_profile"))
        // A live database is never overwritten.
        assertFalse(BackupManager.restoreSnapshotIfFresh(live, backup.snapshotFile))
        // A damaged snapshot is ignored.
        val other = File(dir, "databases2/dukaan.db")
        backup.snapshotFile.writeBytes(ByteArray(100))
        assertFalse(BackupManager.restoreSnapshotIfFresh(other, backup.snapshotFile))
        assertFalse(other.exists())
    }

    private fun manifest(schemaVersion: Int = 1, app: String = BackupManager.APP_ID) = Properties().apply {
        setProperty("app", app)
        setProperty("schemaVersion", schemaVersion.toString())
    }

    private fun zipOf(manifest: Properties, db: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupManager.MANIFEST)); manifest.store(zip, null); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupManager.DB_ENTRY)); zip.write(db); zip.closeEntry()
        }
        return out.toByteArray()
    }
}
