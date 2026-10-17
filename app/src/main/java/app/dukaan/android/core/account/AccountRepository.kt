package app.dukaan.android.core.account

import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.FirebaseFirestoreSettings
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** What sign-up asks about the shop (and what the local shop_profile is made from). */
data class ShopInfo(val name: String, val type: String, val address: String?, val phone: String?, val currency: String)

data class AccountProfile(val uid: String, val name: String, val phone: String?, val email: String?, val shopId: String, val role: String)

/** Named account failures; the UI maps each to one sentence (no catch-all). */
sealed class AccountError(message: String) : Exception(message) {
    class Offline : AccountError("offline")
    class WrongPassword : AccountError("wrong password")
    class NoAccount : AccountError("no account")
    class AlreadyUsed : AccountError("already registered")
    class WeakPassword : AccountError("weak password")
    class BadPhone : AccountError("bad phone")
    class BadEmail : AccountError("bad email")
    class Blocked : AccountError("blocked")
    /** Phone account without a real email: only the app owner can reset it (tools/reset-password). */
    class NoResetEmail : AccountError("no reset email")
    /** Google sign-in on a phone with no Google account added. */
    class NoGoogleAccount : AccountError("no google account")
    class Other(cause: Exception) : AccountError(cause.message ?: "error")
}

/** Result of re-checking the signed-in account with the server (docs/ACCOUNTS.md § Account states). */
enum class Verification { OK, BLOCKED, REMOVED, OFFLINE }

data class Member(val uid: String, val name: String, val phone: String?, val email: String?, val role: String)

/** A pending staff invite; [key] is the invited phone digits or lower-case email. */
data class Invite(val key: String, val shopId: String, val shopName: String, val name: String)

/**
 * Firebase Auth + Firestore for accounts (M7 phase 1). Every call is a suspend function that throws
 * [AccountError]; nothing here touches the local database.
 */
class AccountRepository(private val context: Context) {

    /** False without google-services.json: the app then runs local-only, as before M7. */
    val configured: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    private val auth get() = FirebaseAuth.getInstance()
    /**
     * Memory-only cache: Firestore's default disk cache would keep the downloaded cloud copy on the
     * phone after log out. We read from the server on purpose anyway (Source.SERVER).
     */
    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        }
    }
    private val projectId get() = FirebaseApp.getInstance().options.projectId.orEmpty()

    val currentUid: String? get() = if (configured) auth.currentUser?.uid else null
    val currentEmail: String? get() = auth.currentUser?.email?.takeUnless { PhoneNumbers.isHiddenEmail(it) }
    val currentName: String? get() = auth.currentUser?.displayName

    // ---------------------------------------------------------------- sign up / sign in

    suspend fun signUpEmail(email: String, password: String) = call {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
    }

    /**
     * Phone + password (no SMS on the free plan). Login email is the real email if given, else a
     * hidden one; `phoneLogins/{digits}` lets the login screen find it.
     */
    suspend fun signUpPhone(phoneInput: String, password: String, realEmail: String?) = call {
        val digits = PhoneNumbers.normalize(phoneInput) ?: throw AccountError.BadPhone()
        if (db.collection(PHONES).document(digits).get().await().exists()) throw AccountError.AlreadyUsed()
        val email = realEmail?.trim()?.takeIf { it.isNotEmpty() } ?: PhoneNumbers.hiddenEmail(digits, projectId)
        val user = auth.createUserWithEmailAndPassword(email, password).await().user!!
        db.collection(PHONES).document(digits).set(mapOf("email" to email, "uid" to user.uid)).await()
    }

    suspend fun signInEmail(email: String, password: String) = call {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun signInPhone(phoneInput: String, password: String) = call {
        val email = emailForPhone(phoneInput)
        auth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun signInGoogle(idToken: String) = call {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    /** Email or phone: sends Firebase's reset email to the real address behind the account. */
    suspend fun sendPasswordReset(emailOrPhone: String) = call {
        val email = if (emailOrPhone.contains('@')) emailOrPhone.trim() else emailForPhone(emailOrPhone)
        if (PhoneNumbers.isHiddenEmail(email)) throw AccountError.NoResetEmail()
        auth.sendPasswordResetEmail(email).await()
    }

    private suspend fun emailForPhone(phoneInput: String): String {
        val digits = PhoneNumbers.normalize(phoneInput) ?: throw AccountError.BadPhone()
        val doc = db.collection(PHONES).document(digits).get().await()
        return doc.getString("email") ?: throw AccountError.NoAccount()
    }

    fun signOut() = auth.signOut()

    // ---------------------------------------------------------------- profile + shop

    /** Null = signed in but no shop yet (finish sign-up). Server read: a stale cache must not decide. */
    suspend fun loadProfile(): AccountProfile? = call {
        val uid = auth.currentUser?.uid ?: return@call null
        val doc = db.collection(USERS).document(uid).get(Source.SERVER).await()
        val shopId = doc.getString("shopId") ?: return@call null
        AccountProfile(uid, doc.getString("name").orEmpty(), doc.getString("phone"), doc.getString("email"), shopId, doc.getString("role") ?: "owner")
    }

    suspend fun loadShop(shopId: String): ShopInfo = call {
        val d = db.collection(SHOPS).document(shopId).get(Source.SERVER).await()
        ShopInfo(d.getString("name").orEmpty(), d.getString("type") ?: "other", d.getString("address"), d.getString("phone"), d.getString("currency") ?: "PKR")
    }

    /** Owner sign-up: the shop and the owner's user document, written together. */
    suspend fun createShop(ownerName: String, ownerPhone: String?, shop: ShopInfo): AccountProfile = call {
        val user = auth.currentUser ?: throw AccountError.NoAccount()
        val shopRef = db.collection(SHOPS).document()
        val now = Timestamp.now()
        val email = user.email?.takeUnless { PhoneNumbers.isHiddenEmail(it) }
        val phone = ownerPhone?.let { PhoneNumbers.normalize(it) }
        db.runBatch { b ->
            b.set(shopRef, mapOf(
                "name" to shop.name, "type" to shop.type, "address" to shop.address, "phone" to shop.phone,
                "currency" to shop.currency, "ownerUid" to user.uid, "status" to "active", "createdAt" to now,
            ))
            b.set(db.collection(USERS).document(user.uid), mapOf(
                "name" to ownerName, "phone" to phone, "email" to email, "shopId" to shopRef.id,
                "role" to "owner", "createdAt" to now, "updatedAt" to now,
            ))
        }.await()
        // Shown on the Account screen; phone accounts have no name otherwise.
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(ownerName).build()).await()
        AccountProfile(user.uid, ownerName, phone, email, shopRef.id, "owner")
    }

    /**
     * Re-check with the server. BLOCKED/REMOVED only on an explicit signal, because they wipe the
     * phone (D-72): the user disabled/deleted in the console, the shop's `status` reads "blocked",
     * or the user's own record no longer points at this shop (owner removed the staff member).
     * Everything else (network, permission hiccup, token not ready) is OFFLINE: offline grace.
     */
    suspend fun verify(shopId: String): Verification {
        val user = auth.currentUser ?: return Verification.OFFLINE
        return try {
            user.reload().await()
            val mine = db.collection(USERS).document(user.uid).get(Source.SERVER).await()
            if (!mine.exists() || mine.getString("shopId") != shopId) return Verification.REMOVED
            val status = db.collection(SHOPS).document(shopId).get(Source.SERVER).await().getString("status")
            if (status == "blocked") Verification.BLOCKED else Verification.OK
        } catch (e: FirebaseAuthInvalidUserException) {
            Log.w(TAG, "account check: ${e.errorCode}", e)
            if (e.errorCode == "ERROR_USER_DISABLED" || e.errorCode == "ERROR_USER_NOT_FOUND") Verification.BLOCKED else Verification.OFFLINE
        } catch (e: Exception) {
            Log.w(TAG, "account check could not reach a verdict", e)
            Verification.OFFLINE
        }
    }

    // ---------------------------------------------------------------- staff (phase 2)

    /** Who a staff invite is for: phone digits or a lower-case email. Null if neither. */
    fun inviteKey(phoneOrEmail: String): String? = inviteKeyOf(phoneOrEmail)

    /** Owner: invite someone; they join when they sign up with that phone or email. */
    suspend fun invite(shopId: String, shopName: String, phoneOrEmail: String, name: String) = call {
        val key = inviteKey(phoneOrEmail) ?: throw AccountError.BadPhone()
        val ref = db.collection(INVITES).document(key)
        // No "does it exist?" read first: the rules don't let anyone see an invite that isn't theirs.
        // Writing over an existing invite is an update, which the rules refuse: that means "already invited".
        try {
            ref.set(mapOf(
            "shopId" to shopId, "shopName" to shopName, "role" to "staff", "name" to name.trim(),
                "invitedBy" to auth.currentUser?.uid, "createdAt" to Timestamp.now(),
            )).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) throw AccountError.AlreadyUsed() else throw e
        }
    }

    suspend fun members(shopId: String): List<Member> = call {
        db.collection(USERS).whereEqualTo("shopId", shopId).get(Source.SERVER).await().documents.map {
            Member(it.id, it.getString("name").orEmpty(), it.getString("phone"), it.getString("email"), it.getString("role") ?: "staff")
        }.sortedWith(compareBy({ it.role != "owner" }, { it.name.lowercase() }))
    }

    suspend fun invites(shopId: String): List<Invite> = call {
        db.collection(INVITES).whereEqualTo("shopId", shopId).get(Source.SERVER).await().documents.map {
            Invite(it.id, it.getString("shopId").orEmpty(), it.getString("shopName").orEmpty(), it.getString("name").orEmpty())
        }
    }

    suspend fun cancelInvite(key: String) = call { db.collection(INVITES).document(key).delete().await() }

    /** Owner removes a staff member; their phone is wiped at its next check (verify → REMOVED). */
    suspend fun removeMember(uid: String) = call { db.collection(USERS).document(uid).delete().await() }

    /** After sign-up with no shop: an invite addressed to this person's email or phone, if any. */
    suspend fun findInvite(phoneHint: String?): Invite? = call {
        val user = auth.currentUser ?: return@call null
        val keys = listOfNotNull(
            user.email?.takeUnless { PhoneNumbers.isHiddenEmail(it) }?.lowercase(),
            user.email?.takeIf { PhoneNumbers.isHiddenEmail(it) }?.substringBefore('@'),
            phoneHint?.let { PhoneNumbers.normalize(it) },
        ).distinct()
        keys.firstNotNullOfOrNull { key ->
            // A key that isn't this person (e.g. a typed phone hint) is refused by the rules: skip it.
            val d = try { db.collection(INVITES).document(key).get(Source.SERVER).await() } catch (e: FirebaseFirestoreException) { null }
            if (d != null && d.exists()) Invite(key, d.getString("shopId").orEmpty(), d.getString("shopName").orEmpty(), d.getString("name").orEmpty()) else null
        }
    }

    /** Join a shop as staff: own user record (rules check the invite), then the invite is used up. */
    suspend fun acceptInvite(invite: Invite, name: String, phone: String?): AccountProfile = call {
        val user = auth.currentUser ?: throw AccountError.NoAccount()
        val now = Timestamp.now()
        val email = user.email?.takeUnless { PhoneNumbers.isHiddenEmail(it) }
        val digits = phone?.let { PhoneNumbers.normalize(it) }
        db.runBatch { b ->
            b.set(db.collection(USERS).document(user.uid), mapOf(
                "name" to name, "phone" to digits, "email" to email, "shopId" to invite.shopId, "role" to "staff",
                "inviteKey" to invite.key, "createdAt" to now, "updatedAt" to now,
            ))
            b.delete(db.collection(INVITES).document(invite.key))
        }.await()
        if (user.displayName.isNullOrBlank()) user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build()).await()
        AccountProfile(user.uid, name, digits, email, invite.shopId, "staff")
    }

    // ---------------------------------------------------------------- cloud copy

    /** The shop's cloud copy: the owner writes it, every member starts from it (D-71, D-79). */
    private fun slot(shopId: String): Pair<DocumentReference, CollectionReference> {
        val shop = db.collection(SHOPS).document(shopId)
        return shop.collection(BACKUP).document(META) to shop.collection(CHUNKS)
    }

    suspend fun uploadCopy(shopId: String, snapshot: ByteArray, deviceId: String, schemaVersion: Int) = call {
        val packed = CloudCopy.pack(snapshot)
        val (meta, chunks) = slot(shopId)
        // Chunks first, meta last: a reader never sees meta pointing at chunks that aren't there.
        db.runBatch { b ->
            packed.chunks.forEachIndexed { i, bytes -> b.set(chunks.document(i.toString()), mapOf("data" to Blob.fromBytes(bytes))) }
        }.await()
        meta.set(mapOf(
            "chunks" to packed.chunks.size, "bytes" to packed.bytes, "sha256" to packed.sha256,
            "schemaVersion" to schemaVersion, "deviceId" to deviceId, "uploadedAt" to Timestamp.now(),
        )).await()
    }

    /** The cloud copy's raw snapshot bytes, or null if the shop never uploaded one. */
    suspend fun downloadCopy(shopId: String): ByteArray? = call {
        val (metaRef, chunks) = slot(shopId)
        val meta = metaRef.get(Source.SERVER).await()
        if (!meta.exists()) return@call null
        val n = (meta.getLong("chunks") ?: 0L).toInt()
        val parts = (0 until n).map { i ->
            chunks.document(i.toString()).get(Source.SERVER).await().getBlob("data")?.toBytes() ?: throw AccountError.Other(IllegalStateException("missing chunk $i"))
        }
        try {
            CloudCopy.unpack(parts, meta.getString("sha256").orEmpty())
        } catch (e: IllegalStateException) {
            throw AccountError.Other(e)
        }
    }

    // ---------------------------------------------------------------- live sync (phase 3, D-77)

    /**
     * One change = the rows one phone queued, gzipped (core/sync/RowCodec). [at] is the server's
     * time, so every phone reads the log in the same order. Suspends until the server has it
     * (offline: until back online; the write waits in memory).
     */
    suspend fun pushChange(shopId: String, deviceId: String, rows: Int, bytes: ByteArray, schemaVersion: Int) = call {
        db.collection(SHOPS).document(shopId).collection(CHANGES).document().set(mapOf(
            "by" to deviceId, "uid" to auth.currentUser?.uid, "at" to FieldValue.serverTimestamp(),
            "n" to rows, "v" to schemaVersion, "data" to Blob.fromBytes(bytes),
        )).await()
        Unit
    }

    class Change(val by: String, val atMicros: Long, val schemaVersion: Int, val data: ByteArray)

    /**
     * Live: changes after [afterMicros], oldest first, at most [limit] per snapshot. [onChanges]
     * gets the committed ones and whether the page was full (then listen again from the last one).
     */
    fun listenChanges(shopId: String, afterMicros: Long, limit: Int, onChanges: (List<Change>, Boolean) -> Unit, onError: (Exception) -> Unit): ListenerRegistration =
        db.collection(SHOPS).document(shopId).collection(CHANGES)
            .whereGreaterThan("at", Timestamp(afterMicros / 1_000_000, ((afterMicros % 1_000_000) * 1_000).toInt()))
            .orderBy("at").limit(limit.toLong())
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    onError(e ?: IllegalStateException("no snapshot"))
                    return@addSnapshotListener
                }
                val changes = snap.documents.mapNotNull { d ->
                    if (d.metadata.hasPendingWrites()) return@mapNotNull null
                    val at = d.getTimestamp("at") ?: return@mapNotNull null
                    Change(
                        by = d.getString("by").orEmpty(),
                        atMicros = at.seconds * 1_000_000 + at.nanoseconds / 1_000,
                        schemaVersion = (d.getLong("v") ?: 0L).toInt(),
                        data = d.getBlob("data")?.toBytes() ?: return@mapNotNull null,
                    )
                }
                onChanges(changes, snap.size() >= limit)
            }

    /** Maps Firebase exceptions to [AccountError] so screens never see Firebase types. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: AccountError) {
        throw e
    } catch (e: FirebaseNetworkException) {
        throw AccountError.Offline()
    } catch (e: FirebaseAuthWeakPasswordException) {
        throw AccountError.WeakPassword()
    } catch (e: FirebaseAuthUserCollisionException) {
        throw AccountError.AlreadyUsed()
    } catch (e: FirebaseAuthInvalidUserException) {
        if (e.errorCode == "ERROR_USER_DISABLED") throw AccountError.Blocked() else throw AccountError.NoAccount()
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        if (e.errorCode == "ERROR_INVALID_EMAIL") throw AccountError.BadEmail() else throw AccountError.WrongPassword()
    } catch (e: FirebaseFirestoreException) {
        if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) throw AccountError.Offline() else throw AccountError.Other(e)
    }

    private companion object {
        const val TAG = "Dukaan"
        const val USERS = "users"
        const val SHOPS = "shops"
        const val PHONES = "phoneLogins"
        const val BACKUP = "backup"
        const val META = "meta"
        const val CHUNKS = "backupChunks"
        const val CHANGES = "changes"
        const val INVITES = "invites"
    }
}
