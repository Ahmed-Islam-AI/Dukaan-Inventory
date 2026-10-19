package app.dukaan.android.core.lock

import app.dukaan.android.core.settings.DevicePrefs
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** PIN hashing and wrong-PIN backoff. Pure, so JVM tests cover it. */
object Pin {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6
    private const val ITERATIONS = 50_000

    fun isValid(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }

    /** PBKDF2-SHA256, so a copied prefs file doesn't hand over the PIN directly. */
    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun matches(pin: String, salt: String, expected: String) =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expected.toByteArray())

    /** Wait after [failures] wrong PINs in a row: none for the first 4, then 30 s doubling, max 30 min. */
    fun waitMillis(failures: Int): Long =
        if (failures < 5) 0 else minOf(30_000L shl minOf(failures - 5, 6), 30 * 60_000L)
}

sealed interface Unlock {
    data object Ok : Unlock
    data object Wrong : Unlock
    /** Too many wrong PINs: try again after [untilMillis]. */
    data class Wait(val untilMillis: Long) : Unlock
}

/**
 * App lock (M6): a 4-6 digit PIN, optionally fingerprint / phone screen lock (BiometricPrompt).
 * Locks at start and when the app comes back after [DevicePrefs.lockAfterMinutes] in the
 * background. Settings live in device prefs: never backed up, so a restored phone starts unlocked
 * and sets its own PIN (D-47).
 *
 * A forgotten PIN is recovered with the phone's own screen lock (the biometric/credential prompt).
 */
class AppLock(private val prefs: DevicePrefs, private val clock: () -> Long = System::currentTimeMillis) {
    val enabled get() = prefs.pinHash != null

    private val _locked = MutableStateFlow(enabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundAt = 0L

    fun onBackground() {
        backgroundAt = clock()
    }

    fun onForeground() {
        if (enabled && backgroundAt > 0 && clock() - backgroundAt >= prefs.lockAfterMinutes * 60_000L) _locked.value = true
    }

    fun setPin(pin: String) {
        require(Pin.isValid(pin))
        val salt = Pin.newSalt()
        prefs.pinSalt = salt
        prefs.pinHash = Pin.hash(pin, salt)
        prefs.pinFailures = 0
    }

    fun removePin() {
        prefs.pinHash = null
        prefs.pinSalt = null
        _locked.value = false
    }

    /** Checks a PIN without unlocking (turning the lock off, re-showing cost). Counts failures. */
    fun check(pin: String): Unlock {
        val hash = prefs.pinHash ?: return Unlock.Ok
        val now = clock()
        if (now < prefs.pinWaitUntil) return Unlock.Wait(prefs.pinWaitUntil)
        if (Pin.matches(pin, prefs.pinSalt.orEmpty(), hash)) {
            prefs.pinFailures = 0
            return Unlock.Ok
        }
        val failures = prefs.pinFailures + 1
        prefs.pinFailures = failures
        val wait = Pin.waitMillis(failures)
        if (wait > 0) {
            prefs.pinWaitUntil = now + wait
            return Unlock.Wait(now + wait)
        }
        return Unlock.Wrong
    }

    fun unlock(pin: String): Unlock = check(pin).also { if (it == Unlock.Ok) _locked.value = false }

    /** Fingerprint or phone screen lock succeeded (also the forgotten-PIN path). */
    fun unlockedByDevice() {
        prefs.pinFailures = 0
        prefs.pinWaitUntil = 0
        _locked.value = false
    }
}
