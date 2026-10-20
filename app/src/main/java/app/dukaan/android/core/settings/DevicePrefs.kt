package app.dukaan.android.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

/**
 * Device-local settings (D-47): never synced, never backed up (backup rules include only the DB
 * snapshot), so a restore onto a new phone automatically gets a fresh deviceId and is asked for
 * its own receipt prefix (D-18).
 */
class DevicePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("device", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    var invoicePrefix: String?
        get() = prefs.getString(KEY_PREFIX, null)
        set(value) = prefs.edit { putString(KEY_PREFIX, value) }

    /** D-45: hides cost, profit and stock value. PIN-protected from M6. */
    var hideCost: Boolean
        get() = prefs.getBoolean(KEY_HIDE_COST, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_COST, value) }

    /** Paired Bluetooth receipt printer (M3). Device-local: a restored backup must pick its own. */
    var printerAddress: String?
        get() = prefs.getString(KEY_PRINTER, null)
        set(value) = prefs.edit { putString(KEY_PRINTER, value) }

    var printerName: String?
        get() = prefs.getString(KEY_PRINTER_NAME, null)
        set(value) = prefs.edit { putString(KEY_PRINTER_NAME, value) }

    /** 58 or 80 (mm). */
    var paperMm: Int
        get() = prefs.getInt(KEY_PAPER, 58)
        set(value) = prefs.edit { putInt(KEY_PAPER, value) }

    /** D-46: print automatically after Charge when a printer is set. */
    var autoPrint: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PRINT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PRINT, value) }

    /** Last successful backup-file export (epoch ms, 0 = never). Drives the Home reminder (M5). */
    var lastExportAt: Long
        get() = prefs.getLong(KEY_LAST_EXPORT, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_EXPORT, value) }

    // App lock (M6, core/lock/AppLock). Null hash = no lock.
    var pinHash: String?
        get() = prefs.getString(KEY_PIN_HASH, null)
        set(value) = prefs.edit { putString(KEY_PIN_HASH, value) }

    var pinSalt: String?
        get() = prefs.getString(KEY_PIN_SALT, null)
        set(value) = prefs.edit { putString(KEY_PIN_SALT, value) }

    var pinFailures: Int
        get() = prefs.getInt(KEY_PIN_FAILURES, 0)
        set(value) = prefs.edit { putInt(KEY_PIN_FAILURES, value) }

    var pinWaitUntil: Long
        get() = prefs.getLong(KEY_PIN_WAIT, 0)
        set(value) = prefs.edit { putLong(KEY_PIN_WAIT, value) }

    /** Fingerprint / phone screen lock may unlock instead of the PIN. */
    var deviceUnlock: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_UNLOCK, true)
        set(value) = prefs.edit { putBoolean(KEY_DEVICE_UNLOCK, value) }

    /** Lock again after this long in the background. 5 min default: sharing a receipt on WhatsApp must not lock. */
    var lockAfterMinutes: Int
        get() = prefs.getInt(KEY_LOCK_AFTER, 5)
        set(value) = prefs.edit { putInt(KEY_LOCK_AFTER, value) }

    /** "" = follow the phone, "en", "ur" (M6). Applied at process start (DukaanApp/MainActivity). */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "").orEmpty()
        set(value) = prefs.edit(commit = true) { putString(KEY_LANGUAGE, value) }

    // Account link (M7, docs/ACCOUNTS.md). Null shopId = this phone isn't linked to an account yet.
    var accountShopId: String?
        get() = prefs.getString(KEY_ACCOUNT_SHOP, null)
        set(value) = prefs.edit(commit = true) { putString(KEY_ACCOUNT_SHOP, value) }

    var accountRole: String
        get() = prefs.getString(KEY_ACCOUNT_ROLE, "owner").orEmpty()
        set(value) = prefs.edit { putString(KEY_ACCOUNT_ROLE, value) }

    /** Last successful server check of the account; the offline grace counts from here. */
    var accountVerifiedAt: Long
        get() = prefs.getLong(KEY_ACCOUNT_VERIFIED, 0)
        set(value) = prefs.edit { putLong(KEY_ACCOUNT_VERIFIED, value) }

    /** Cloud copy bookkeeping: when, and which snapshot (its lastModified) was uploaded. */
    var cloudUploadedAt: Long
        get() = prefs.getLong(KEY_CLOUD_AT, 0)
        set(value) = prefs.edit { putLong(KEY_CLOUD_AT, value) }

    var cloudUploadedSnapshot: Long
        get() = prefs.getLong(KEY_CLOUD_SNAPSHOT, 0)
        set(value) = prefs.edit { putLong(KEY_CLOUD_SNAPSHOT, value) }

    /**
     * Why this phone was wiped, shown once after the restart (M7): "blocked" (console) or
     * "removed:<shop name>" (the owner removed this staff member). Null = nothing to show.
     */
    var accountNotice: String?
        get() = prefs.getString(KEY_NOTICE, null)
        set(value) = prefs.edit(commit = true) { putString(KEY_NOTICE, value) }

    /** Log out / blocked: forget everything about this shop on this phone, keep only the language. */
    fun clearForSignOut() {
        val lang = language
        prefs.edit(commit = true) { clear(); putString(KEY_LANGUAGE, lang); putBoolean(KEY_SIGN_OUT, true) }
    }

    /**
     * Set by log out / block, cleared when an account is linked again. Firebase saves its own
     * sign-out lazily and the app restarts right after a wipe, so the next start signs out again
     * (found on the emulator: a logged-out staff phone came back signed in).
     */
    var signOutPending: Boolean
        get() = prefs.getBoolean(KEY_SIGN_OUT, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_SIGN_OUT, value) }

    /** After importing a backup: new device identity, and ask for a receipt prefix again (D-18). */
    fun resetAfterRestore() = prefs.edit {
        putString(KEY_DEVICE_ID, UUID.randomUUID().toString())
        remove(KEY_PREFIX)
    }

    private companion object {
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_PREFIX = "invoicePrefix"
        const val KEY_HIDE_COST = "hideCost"
        const val KEY_PRINTER = "printerAddress"
        const val KEY_PRINTER_NAME = "printerName"
        const val KEY_PAPER = "paperMm"
        const val KEY_AUTO_PRINT = "autoPrint"
        const val KEY_LAST_EXPORT = "lastExportAt"
        const val KEY_PIN_HASH = "pinHash"
        const val KEY_PIN_SALT = "pinSalt"
        const val KEY_PIN_FAILURES = "pinFailures"
        const val KEY_PIN_WAIT = "pinWaitUntil"
        const val KEY_DEVICE_UNLOCK = "deviceUnlock"
        const val KEY_LOCK_AFTER = "lockAfterMinutes"
        const val KEY_LANGUAGE = "language"
        const val KEY_ACCOUNT_SHOP = "accountShopId"
        const val KEY_ACCOUNT_ROLE = "accountRole"
        const val KEY_ACCOUNT_VERIFIED = "accountVerifiedAt"
        const val KEY_CLOUD_AT = "cloudUploadedAt"
        const val KEY_CLOUD_SNAPSHOT = "cloudUploadedSnapshot"
        const val KEY_NOTICE = "accountNotice"
        const val KEY_SIGN_OUT = "signOutPending"
    }
}
