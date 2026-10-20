package app.dukaan.android.core.sync

import android.util.Log
import app.dukaan.android.AppContainer
import app.dukaan.android.core.account.AccountError
import app.dukaan.android.core.account.AccountRepository
import app.dukaan.android.core.db.DukaanDatabase
import com.google.firebase.firestore.ListenerRegistration
import java.io.IOException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live sync with the shop's other phones (M7 phase 3, D-77): this phone's changes go up as they
 * happen; everyone else's come down through a Firestore listener while the app runs. The data
 * work is in [SyncLocal]; this class only moves bytes and keeps the order.
 *
 *   outbox count ──debounce──▶ push batches ──▶ shops/{id}/changes/{auto}
 *   listener(at > pulledUpTo, oldest first) ──▶ apply (not our own) ──▶ pulledUpTo = at
 */
@OptIn(FlowPreview::class)
class CloudSync(private val container: AppContainer) {
    private val local by lazy { SyncLocal(container.database) }
    private val pushLock = Mutex()
    private val pullLock = Mutex()
    private val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var job: Job? = null
    private var listener: ListenerRegistration? = null

    private val _needsUpdate = MutableStateFlow(false)
    /** A phone with a newer app wrote changes this app can't read: sync waits for an update. */
    val needsUpdate: StateFlow<Boolean> = _needsUpdate.asStateFlow()

    /** Rows changed on this phone that the cloud doesn't have yet. */
    val waiting get() = container.database.syncDao().waiting()

    /** Idempotent: called whenever the account becomes READY. */
    @Synchronized
    fun start() {
        if (job != null) return
        val shopId = container.prefs.accountShopId ?: return
        job = container.appScope.launch {
            local.seedIfNeeded()
            listen(shopId)
            merge(waiting.filter { it > 0 }.map { }.debounce(PUSH_DEBOUNCE_MILLIS), kicks).conflate().collect { pushAll(shopId) }
        }
    }

    /** Back in the foreground: retry anything a failed push left behind. */
    fun kick() {
        kicks.tryEmit(Unit)
    }

    /** Log out: everything must reach the cloud first. Throws [AccountError.Offline] if it can't. */
    suspend fun pushNow() {
        val shopId = container.prefs.accountShopId ?: return
        withTimeoutOrNull(LOGOUT_TIMEOUT_MILLIS) { pushAll(shopId) }
        if (container.database.syncDao().waitingNow() > 0) throw AccountError.Offline()
    }

    private suspend fun pushAll(shopId: String) = pushLock.withLock {
        while (true) {
            val batch = local.nextBatch() ?: return@withLock
            try {
                container.account.repo.pushChange(shopId, container.prefs.deviceId, batch.rows, batch.bytes, DukaanDatabase.VERSION)
            } catch (e: AccountError) {
                Log.w(TAG, "push failed; retried on the next change or return to the app", e)
                return@withLock
            }
            local.pushed(batch.upToSeq)
        }
    }

    private suspend fun listen(shopId: String) {
        val from = local.pulledUpTo()
        listener?.remove()
        listener = container.account.repo.listenChanges(shopId, from, PAGE,
            onChanges = { changes, full -> container.appScope.launch { apply(shopId, changes, full) } },
            onError = { Log.w(TAG, "change listener stopped; restarts with the app", it) },
        )
    }

    private suspend fun apply(shopId: String, changes: List<AccountRepository.Change>, full: Boolean) {
        pullLock.withLock {
            for (c in changes.sortedBy { it.atMicros }) {
                if (c.atMicros <= local.pulledUpTo()) continue
                if (c.schemaVersion > DukaanDatabase.VERSION) {
                    _needsUpdate.value = true
                    listener?.remove()
                    return@withLock
                }
                if (c.by != container.prefs.deviceId) {
                    try {
                        local.apply(c.data)
                    } catch (e: IOException) {
                        Log.e(TAG, "unreadable change skipped", e) // ponytail: report to Crashlytics once it carries non-fatals
                    }
                }
                local.setPulledUpTo(c.atMicros)
            }
            if (full) listen(shopId) // next page
        }
    }

    private companion object {
        const val TAG = "Dukaan"
        const val PAGE = 100
        const val PUSH_DEBOUNCE_MILLIS = 1_500L
        const val LOGOUT_TIMEOUT_MILLIS = 20_000L
    }
}
