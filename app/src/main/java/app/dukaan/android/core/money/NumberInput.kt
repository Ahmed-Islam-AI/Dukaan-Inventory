package app.dukaan.android.core.money

/**
 * Normalizes what a shopkeeper types before strict parsing: trims, drops thousands separators
 * ("," and Arabic "٬"), maps the Arabic decimal separator "٫" to ".", and converts any Unicode
 * decimal digit (Urdu ۰-۹, Arabic-Indic ٠-٩) to ASCII. JVM Regex \d only matches ASCII (D-30).
 */
internal fun normalizeNumberInput(input: String): String =
    input.trim().replace(",", "").replace("٬", "").replace("٫", ".")
        .map { c -> if (c.isDigit()) Character.forDigit(Character.digit(c, 10), 10) else c }
        .joinToString("")
