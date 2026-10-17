package app.dukaan.android

import app.dukaan.android.core.account.AccountRepository
import app.dukaan.android.core.account.AccountManager
import android.content.Intent
import android.content.Context
import android.util.Log
import androidx.room3.Room
import androidx.sqlite.SQLiteException
import app.dukaan.android.core.sync.CloudSync
import app.dukaan.android.core.sync.SyncTables
import app.dukaan.android.core.backup.BackupManager
import app.dukaan.android.core.db.DukaanDatabase
import app.dukaan.android.core.ledger.InvariantChecker
import app.dukaan.android.core.ledger.LedgerService
import app.dukaan.android.core.lock.AppLock
import app.dukaan.android.core.printer.ThermalPrinter
import app.dukaan.android.core.receipt.ReceiptRepository
import app.dukaan.android.core.settings.DevicePrefs
import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container, replacing Hilt (DECISIONS.md D-23).
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /** Work that must outlive a screen (background snapshot). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dbFile: File get() = appContext.getDatabasePath(DukaanDatabase.NAME)
    private val backupDir: File get() = File(appContext.filesDir, "backup")

    val database: DukaanDatabase by lazy {
        // After a reinstall, Auto Backup brought back only the snapshot (D-11): start from it.
        if (BackupManager.restoreSnapshotIfFresh(dbFile, File(backupDir, BackupManager.SNAPSHOT))) {
            Log.i(TAG, "restored database from Auto Backup snapshot")
        }
        // Bundled SQLite, migrations, sync triggers (SyncTables). Never fallbackToDestructiveMigration:
        // a missing migration must fail loudly, not wipe a shop's sales history.
        SyncTables.configure(Room.databaseBuilder<DukaanDatabase>(context = appContext, name = dbFile.absolutePath)).build()
    }

    val prefs: DevicePrefs by lazy { DevicePrefs(appContext) }

    val lock: AppLock by lazy { AppLock(prefs) }

    val account: AccountManager by lazy { AccountManager(this, AccountRepository(appContext)) }

    /** Live sync with the shop's other phones (M7 phase 3). Started by [account] when READY. */
    val sync: CloudSync by lazy { CloudSync(this) }

    /**
     * The shop's time zone for businessDay (D-36). Set from shop_profile at startup and after
     * onboarding; the device zone until then.
     */
    @Volatile
    var shopZone: ZoneId = ZoneId.systemDefault()

    val ledger: LedgerService by lazy {
        LedgerService(database, deviceId = { prefs.deviceId }, zone = { shopZone })
    }

    val invariants: InvariantChecker by lazy { InvariantChecker(database.auditDao()) }

    val receipts: ReceiptRepository by lazy { ReceiptRepository(database) }

    val printer: ThermalPrinter by lazy { ThermalPrinter(appContext) }

    val backup: BackupManager by lazy {
        val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        BackupManager(database, backupDir, version)
    }

    /** Called when the app goes to the background: at most one snapshot an hour (D-28). */
    fun snapshotInBackground() {
        appScope.launch {
            try {
                backup.snapshotIfDue()
                account.uploadIfDue() // cloud copy follows the fresh snapshot (M7)
            } catch (e: IOException) {
                Log.w(TAG, "snapshot failed; previous snapshot kept", e)
            } catch (e: SQLiteException) {
                Log.w(TAG, "snapshot failed; previous snapshot kept", e)
            }
        }
    }

    /**
     * Swaps in a validated backup (BackupManager.validate). The current database is kept as
     * dukaan.db.before-import. The caller must restart the process right after: every open
     * screen still points at the old database.
     */
    fun replaceDatabase(validated: File) {
        database.close()
        val live = dbFile
        if (live.exists()) live.copyTo(File(live.path + ".before-import"), overwrite = true)
        File(live.path + "-wal").delete()
        File(live.path + "-shm").delete()
        validated.copyTo(live, overwrite = true)
        // A restored copy must not impersonate this device or reuse its receipt numbers (D-18).
        prefs.resetAfterRestore()
    }

    /** Fresh process on the main screen: after a restore every screen must open the new data. */
    fun restartApp() {
        val restart = Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        appContext.startActivity(restart)
        Runtime.getRuntime().exit(0)
    }

    /**
     * A new account starts empty (D-82): every table is emptied, and the snapshot, cached receipt
     * PDFs and cloud-copy temp file go too, so nothing of the old shop is left. No restart needed.
     */
    suspend fun clearShopData() {
        database.clearAllTables()
        backupDir.deleteRecursively()
        File(appContext.cacheDir, "receipts").deleteRecursively()
        File(appContext.cacheDir, "cloud-copy.db").delete()
    }

    /**
     * Log out / blocked (M7): the shop's data leaves this phone (it is safe in the cloud copy).
     * Database, snapshots and the pre-import copy are deleted; settings are cleared by the caller.
     */
    fun wipeLocalDataAndRestart() {
        database.close()
        listOf("", "-wal", "-shm", ".before-import").forEach { File(dbFile.path + it).delete() }
        backupDir.deleteRecursively()
        File(appContext.cacheDir, "cloud-copy.db").delete()
        // Firestore's own cache files (pre-memory-cache builds wrote cloud copies here).
        dbFile.parentFile?.listFiles { f -> f.name.startsWith("firestore.") }?.forEach { it.delete() }
        restartApp()
    }

    private companion object {
        const val TAG = "Dukaan"
    }
}
