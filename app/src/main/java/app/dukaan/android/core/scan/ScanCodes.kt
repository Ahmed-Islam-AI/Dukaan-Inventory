package app.dukaan.android.core.scan

/**
 * Normalises a scanned barcode before it is used as a search, IMEI or barcode value.
 *
 * Phone boxes print IMEIs as Code 128 barcodes, sometimes with a label baked in ("IMEI:3567…",
 * "IMEI1 3567…") and sometimes with spaces. A 15-digit run that passes the Luhn check is taken as
 * the IMEI; anything else (EAN-13 product barcodes, QR text) is returned trimmed, unchanged.
 */
object ScanCodes {
    fun clean(raw: String): String {
        val text = raw.trim()
        val digits = text.filter { it.isDigit() }
        val looksLikeImei = text.contains("IMEI", ignoreCase = true) || text.all { it.isDigit() || it.isWhitespace() }
        if (looksLikeImei) {
            // "IMEI1:" style labels put a stray digit in front; take the last 15 digits.
            val candidate = if (digits.length >= 15) digits.takeLast(15) else digits
            if (candidate.length == 15 && isValidImei(candidate)) return candidate
        }
        return text
    }

    /** IMEIs end with a Luhn check digit; a misread barcode almost always fails it. */
    fun isValidImei(imei: String): Boolean {
        if (imei.length != 15 || !imei.all { it.isDigit() }) return false
        var sum = 0
        imei.forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}
