package com.elderphone.app.data

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.util.Log

/**
 * Keeps track of the latest visible phone call.
 *
 * The local value is written immediately when this app starts a call so the home screen does
 * not have to wait for Android's call log provider to finish updating. The system call log is
 * still checked as the source of truth for calls made or received outside the app.
 */
object RecentCallManager {
    private const val TAG = "RecentCallManager"
    private const val PREFS_NAME = "recent_call_prefs"
    private const val KEY_NUMBER = "last_call_number"
    private const val KEY_NAME = "last_call_name"
    private const val KEY_TIME = "last_call_timestamp"

    // Read the old incoming-only preference once as a fallback so existing users keep their data.
    private const val LEGACY_PREFS_NAME = "recent_incoming_call_prefs"
    private const val LEGACY_KEY_NUMBER = "last_incoming_number"
    private const val LEGACY_KEY_NAME = "last_incoming_name"
    private const val LEGACY_KEY_TIME = "last_incoming_timestamp"

    fun recordCall(context: Context, number: String, name: String? = null) {
        val cleaned = BlockedNumberManager.normalizeNumber(number)
        if (cleaned.isBlank()) return

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NUMBER, number)
            .putString(KEY_NAME, name?.ifBlank { null })
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Recorded recent call metadata")
    }

    fun getLatestCall(context: Context): Triple<String, String?, Long>? {
        val storedCall = readStoredCall(context, PREFS_NAME, KEY_NUMBER, KEY_NAME, KEY_TIME)
        val legacyIncomingCall = readStoredCall(
            context,
            LEGACY_PREFS_NAME,
            LEGACY_KEY_NUMBER,
            LEGACY_KEY_NAME,
            LEGACY_KEY_TIME
        )
        val systemCall = readLatestSystemCall(context)

        return listOfNotNull(storedCall, legacyIncomingCall, systemCall)
            .maxByOrNull { it.third }
    }

    private fun readStoredCall(
        context: Context,
        prefsName: String,
        numberKey: String,
        nameKey: String,
        timeKey: String
    ): Triple<String, String?, Long>? {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val number = prefs.getString(numberKey, null)?.takeIf { it.isNotBlank() } ?: return null
        return Triple(number, prefs.getString(nameKey, null), prefs.getLong(timeKey, 0L))
    }

    private fun readLatestSystemCall(context: Context): Triple<String, String?, Long>? {
        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} IN (?, ?, ?, ?)",
                arrayOf(
                    CallLog.Calls.INCOMING_TYPE.toString(),
                    CallLog.Calls.OUTGOING_TYPE.toString(),
                    CallLog.Calls.MISSED_TYPE.toString(),
                    CallLog.Calls.REJECTED_TYPE.toString()
                ),
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                    val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)
                    if (numberIndex != -1 && dateIndex != -1) {
                        val number = it.getString(numberIndex)
                        if (!number.isNullOrBlank()) {
                            return Triple(number, null, it.getLong(dateIndex))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query the latest system call: ${e.message}")
        }
        return null
    }
}
