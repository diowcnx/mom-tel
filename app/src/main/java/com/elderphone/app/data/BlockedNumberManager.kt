package com.elderphone.app.data

import android.content.Context
import android.content.SharedPreferences

data class BlockedNumber(
    val number: String,
    val normalizedNumber: String,
    val label: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

object BlockedNumberManager {
    private const val PREF_NAME = "blocked_numbers_prefs"
    private const val KEY_BLOCKED_LIST = "blocked_list"

    fun normalizeNumber(rawNumber: String): String {
        if (rawNumber.isBlank()) return ""
        // Remove spaces, dashes, parentheses
        var cleaned = rawNumber.replace(Regex("[^0-9+]"), "")
        // Convert +66... to 0...
        if (cleaned.startsWith("+66")) {
            cleaned = "0" + cleaned.substring(3)
        } else if (cleaned.startsWith("66") && cleaned.length >= 10) {
            cleaned = "0" + cleaned.substring(2)
        }
        return cleaned
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getBlockedNumbers(context: Context): List<BlockedNumber> {
        val set = getPrefs(context).getStringSet(KEY_BLOCKED_LIST, emptySet()) ?: emptySet()
        return set.mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val num = parts[0]
                val label = if (parts.size > 1) parts[1] else ""
                val ts = if (parts.size > 2) parts[2].toLongOrNull() ?: 0L else 0L
                BlockedNumber(number = num, normalizedNumber = normalizeNumber(num), label = label, timestamp = ts)
            } else null
        }.sortedByDescending { it.timestamp }
    }

    fun isBlocked(context: Context, rawNumber: String): Boolean {
        if (rawNumber.isBlank()) return false
        val normalized = normalizeNumber(rawNumber)
        if (normalized.isBlank()) return false
        val blockedList = getBlockedNumbers(context)
        return blockedList.any { it.normalizedNumber == normalized || (it.normalizedNumber.endsWith(normalized) && normalized.length >= 8) || (normalized.endsWith(it.normalizedNumber) && it.normalizedNumber.length >= 8) }
    }

    fun blockNumber(context: Context, rawNumber: String, label: String = ""): Boolean {
        val normalized = normalizeNumber(rawNumber)
        if (normalized.isBlank()) return false

        val currentList = getBlockedNumbers(context).toMutableList()
        if (currentList.none { it.normalizedNumber == normalized }) {
            currentList.add(BlockedNumber(number = rawNumber.trim(), normalizedNumber = normalized, label = label.trim(), timestamp = System.currentTimeMillis()))
            val set = currentList.map { "${it.number}|${it.label}|${it.timestamp}" }.toSet()
            getPrefs(context).edit().putStringSet(KEY_BLOCKED_LIST, set).apply()
            return true
        }
        return false
    }

    fun unblockNumber(context: Context, rawNumber: String): Boolean {
        val normalized = normalizeNumber(rawNumber)
        val currentList = getBlockedNumbers(context).toMutableList()
        val changed = currentList.removeAll { it.normalizedNumber == normalized }
        if (changed) {
            val set = currentList.map { "${it.number}|${it.label}|${it.timestamp}" }.toSet()
            getPrefs(context).edit().putStringSet(KEY_BLOCKED_LIST, set).apply()
            return true
        }
        return false
    }
}
