package app.dukaan.android.core.account

import android.util.Log
import app.dukaan.android.AppContainer
import app.dukaan.android.core.backup.BackupManager
import app.dukaan.android.core.db.DukaanDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which part of the app the user may see (docs/ACCOUNTS.md § Account states). */
enum class AccountState {
    /** No google-services.json: the app works as before M7, no accounts. */
    LOCAL,
    CHECKING,
    SIGNED_OUT,
    /** Signed in, but no shop yet: finish sign-up. */
    NEEDS_SHOP,
    READY,
    /** Disabled in the console, shop blocked, or removed by the owner; the phone has been wiped. */
    BLOCKED,
    /** Not verified for [AccountManager.GRACE_MILLIS]; data kept, connect to continue. */
    NEEDS_INTERNET,
}

/**
 * Links this phone to an account and keeps that link honest (M7 phase 1). Owns the account part of
 * app state; screens read [state] and call the suspend functions, which throw [AccountError].
 */
class AccountManager(
    private val container: AppContainer,
    val repo: AccountRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs get() = container.prefs
    private val _state = MutableStateFlow(AccountState.CHECKING)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    val isOwner get() = prefs.accountRole == "owner"

    /** The wipe reason for the BLOCKED screen: null/"blocked", or the shop name when removed. */
    val removedFromShop: String? get() = prefs.accountNotice?.takeIf { it.startsWith("removed:") }?.removePrefix("removed:")

    /** Called once at process start (MainActivity). Never blocks on the network. */
    fun start() {
        // Live sync runs whenever this phone is linked and allowed in (phase 3).
        container.appScope.launch { state.collect { if (it == AccountState.READY) container.sync.start() } }
        if (repo.configured && prefs.signOutPending) repo.signOut() // a sign-out the last process may not have saved
        _state.value = when {
            !repo.configured -> AccountState.LOCAL
            prefs.accountNotice != null -> AccountState.BLOCKED
            repo.currentUid == null -> AccountState.SIGNED_OUT
            prefs.accountShopId != null -> {
                checkInBackground(force = true)
                if (graceOver()) AccountState.NEEDS_INTERNET else AccountState.READY
            }
            else -> {
                // Signed in but not linked (e.g. killed mid sign-up): finish the link when possible.
                container.appScope.launch {
                    _state.value = try {
                        afterSignIn(); state.value
                    } catch (e: AccountError) {
                        AccountState.NEEDS_INTERNET
                    }
                }
                AccountState.CHECKING
            }
        }
    }

    /**
     * After any successful sign-in: no shop yet → NEEDS_SHOP; otherwise link this phone. A cloud
     * copy is restored (process restarts); a shop without one is created locally.
     */
    suspend fun afterSignIn() {
        val profile = repo.loadProfile()
        if (profile == null) {
            _state.value = AccountState.NEEDS_SHOP
            return
        }
        link(profile.shopId, profile.role)
        // Everyone starts from the shop's copy; live sync then brings the changes made since (D-79).
        val copy = repo.downloadCopy(profile.shopId)
        when {
            copy != null -> restore(copy) // restarts the app
            container.database.shopDao().get() == null -> {
                insertLocalShop(container, repo.loadShop(profile.shopId), prefix = null)
                _state.value = AccountState.READY
            }
            else -> {
                // This phone already had a shop and the account had no copy yet: it becomes the copy.
                uploadNow()
                _state.value = AccountState.READY
            }
        }
    }

    /** Staff: accept an owner's invite, then start from the shop's cloud copy. */
    suspend fun joinShop(invite: Invite, name: String, phone: String?) {
        val profile = repo.acceptInvite(invite, name, phone)
        link(profile.shopId, profile.role)
        val copy = repo.downloadCopy(profile.shopId)
        if (copy != null) {
            restore(copy) // restarts the app
        } else {
            insertLocalShop(container, repo.loadShop(profile.shopId), prefix = null)
            _state.value = AccountState.READY
        }
    }

    /**
     * Last step of an owner's sign-up: a new account is a new, empty shop (D-82). Whatever shop data
     * is still on the phone is removed, only after the account exists, so a failed sign-up loses nothing.
     */
    suspend fun finishSignUp(ownerName: String, ownerPhone: String?, shop: ShopInfo, prefix: String) {
        val profile = repo.createShop(ownerName, ownerPhone, shop)
        link(profile.shopId, profile.role)
        container.clearShopData()
        insertLocalShop(container, shop, prefix)
        uploadNow()
        _state.value = AccountState.READY
    }

    /** Back from the background: re-check at most every [CHECK_EVERY_MILLIS]. */
    fun onForeground() {
        if (_state.value == AccountState.READY || _state.value == AccountState.NEEDS_INTERNET) checkInBackground(force = false)
        if (_state.value == AccountState.READY) container.sync.kick()
    }

    /** "Try again" on the connect screen. */
    suspend fun retry() {
        val shopId = prefs.accountShopId ?: return
        when (repo.verify(shopId)) {
            Verification.OK -> { prefs.accountVerifiedAt = clock(); _state.value = AccountState.READY }
            Verification.BLOCKED -> block("blocked")
            Verification.REMOVED -> block(removedNotice())
            Verification.OFFLINE -> throw AccountError.Offline()
        }
    }

    private fun checkInBackground(force: Boolean) {
        val shopId = prefs.accountShopId ?: return
        if (!force && clock() - prefs.accountVerifiedAt < CHECK_EVERY_MILLIS) return
        container.appScope.launch {
            when (repo.verify(shopId)) {
                Verification.OK -> {
                    prefs.accountVerifiedAt = clock()
                    if (_state.value == AccountState.NEEDS_INTERNET) _state.value = AccountState.READY
                }
                Verification.BLOCKED -> block("blocked")
                Verification.REMOVED -> block(removedNotice())
                Verification.OFFLINE -> if (graceOver()) _state.value = AccountState.NEEDS_INTERNET
            }
        }
    }

    private fun graceOver() = clock() - prefs.accountVerifiedAt > GRACE_MILLIS

    /**
     * Leaving the app: the owner refreshes the shop's cloud copy if the snapshot changed (at most
     * every 15 min). It is only the starting point for new phones; live sync carries every change.
     */
    fun uploadIfDue() {
        if (_state.value != AccountState.READY || !isOwner) return
        val snapshot = container.backup.snapshotFile
        if (!snapshot.exists() || snapshot.lastModified() == prefs.cloudUploadedSnapshot) return
        if (clock() - prefs.cloudUploadedAt < UPLOAD_EVERY_MILLIS) return
        container.appScope.launch {
            try {
                upload(snapshot)
            } catch (e: AccountError) {
                Log.w(TAG, "cloud copy not uploaded; will retry next time", e)
            }
        }
    }

    /** Fresh snapshot, then upload. Throws [AccountError.Offline] when it can't reach the server. */
    suspend fun uploadNow() {
        container.backup.snapshot()
        upload(container.backup.snapshotFile)
    }

    private suspend fun upload(snapshot: File) {
        val shopId = prefs.accountShopId ?: return
        val bytes = try { snapshot.readBytes() } catch (e: IOException) { throw AccountError.Other(e) }
        repo.uploadCopy(shopId, bytes, prefs.deviceId, DukaanDatabase.VERSION)
        prefs.cloudUploadedAt = clock()
        prefs.cloudUploadedSnapshot = snapshot.lastModified()
    }

    /** Log out: every change reaches the cloud first (refused offline), then wipe this phone. */
    suspend fun logOut() {
        container.sync.pushNow()
        if (isOwner) uploadNow()
        repo.signOut()
        prefs.clearForSignOut()
        container.wipeLocalDataAndRestart()
    }

    /**
     * Blocked by the app owner or removed by the shop owner: sign out, wipe this phone, remember
     * why so it is said after the restart.
     */
    private suspend fun block(notice: String) {
        repo.signOut()
        prefs.clearForSignOut()
        prefs.accountNotice = notice
        container.wipeLocalDataAndRestart()
    }

    private suspend fun removedNotice() = "removed:" + container.database.shopDao().get()?.name.orEmpty()

    /** "OK" on the blocked / removed screen. */
    fun acknowledgeBlocked() {
        prefs.accountNotice = null
        _state.value = AccountState.SIGNED_OUT
    }

    private fun link(shopId: String, role: String) {
        prefs.signOutPending = false
        prefs.accountShopId = shopId
        prefs.accountRole = role
        prefs.accountVerifiedAt = clock()
    }

    /** Cloud copy → this phone, through the same safe path as importing a backup file (D-53). */
    private fun restore(snapshot: ByteArray) {
        val tmp = File(container.appContext.cacheDir, "cloud-copy.db")
        tmp.writeBytes(snapshot)
        if (!BackupManager.isIntact(tmp)) throw AccountError.Other(IOException("cloud copy failed its integrity check"))
        val shopId = prefs.accountShopId
        val role = prefs.accountRole
        container.replaceDatabase(tmp)
        prefs.accountShopId = shopId
        prefs.accountRole = role
        container.restartApp()
    }

    companion object {
        private const val TAG = "Dukaan"
        const val GRACE_MILLIS = 14L * 24 * 60 * 60 * 1000
        const val CHECK_EVERY_MILLIS = 6L * 60 * 60 * 1000
        const val UPLOAD_EVERY_MILLIS = 15L * 60 * 1000
    }
}
