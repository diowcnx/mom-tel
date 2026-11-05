package com.elderphone.app.data

/** Pure phone-number helpers shared by call history, contacts, and blocking logic. */
object PhoneNumberUtils {
    private const val BANGKOK_LANDLINE_PREFIX = "02"

    fun normalizeForComparison(rawNumber: String): String {
        if (rawNumber.isBlank()) return ""

        var cleaned = rawNumber.replace(Regex("[^0-9+]"), "")
        cleaned = when {
            cleaned.startsWith("+66") -> "0" + cleaned.substring(3)
            cleaned.startsWith("66") && cleaned.length >= 10 -> "0" + cleaned.substring(2)
            else -> cleaned
        }
        return cleaned
    }

    fun areEquivalent(first: String, second: String): Boolean {
        val firstNormalized = normalizeForComparison(first)
        val secondNormalized = normalizeForComparison(second)
        if (firstNormalized.isBlank() || secondNormalized.isBlank()) return false

        return firstNormalized == secondNormalized ||
                (firstNormalized.length >= 8 &&
                        secondNormalized.length >= 8 &&
                        (firstNormalized.endsWith(secondNormalized) ||
                                secondNormalized.endsWith(firstNormalized)))
    }

    /** Returns true for normalized Thai Bangkok-area landline numbers. */
    fun isBangkokLandline(rawNumber: String): Boolean {
        return normalizeForComparison(rawNumber).startsWith(BANGKOK_LANDLINE_PREFIX)
    }
}
