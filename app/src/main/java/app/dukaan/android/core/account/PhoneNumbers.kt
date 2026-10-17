package app.dukaan.android.core.account

import app.dukaan.android.core.money.normalizeNumberInput

/**
 * Phone numbers as account identifiers (docs/ACCOUNTS.md § Phone + password). Stored as
 * international digits without "+": "0300-1234567" → "923001234567". Urdu digits accepted.
 */
object PhoneNumbers {
    /** Null when it can't be a phone number (too short/long after cleaning). */
    fun normalize(input: String, defaultCountryCode: String = "92"): String? {
        var d = normalizeNumberInput(input).filter { it.isDigit() }
        d = when {
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") -> defaultCountryCode + d.drop(1)
            else -> d
        }
        return d.takeIf { it.length in 10..15 }
    }

    /** Login email for a phone account without a real email; never receives mail. */
    fun hiddenEmail(digits: String, projectId: String) = "$digits@phone.$projectId.firebaseapp.com"

    fun isHiddenEmail(email: String) = email.contains("@phone.") && email.endsWith(".firebaseapp.com")
}

/** Staff invite key (phase 2): lower-case email, or phone digits as [PhoneNumbers.normalize]. Null if neither. */
fun inviteKeyOf(phoneOrEmail: String): String? =
    if (phoneOrEmail.contains('@')) phoneOrEmail.trim().lowercase().takeIf { it.length > 3 && it.indexOf('@') in 1 until it.length - 1 }
    else PhoneNumbers.normalize(phoneOrEmail)
