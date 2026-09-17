package com.elderphone.app.data

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.util.Log

object RecentIncomingCallManager {
    private const val TAG = "RecentIncomingCallMgr"
    private const val PREFS_NAME = "recent_incoming_call_prefs"
    private const val KEY_NUMBER = "last_incoming_number"
    private const val KEY_NAME = "last_incoming_name"
    private const val KEY_TIME = "last_incoming_timestamp"

    fun recordIncomingCall(context: Context, number: String, name: String? = null) {
        val cleaned = BlockedNumberManager.normalizeNumber(number)
        if (cleaned.isBlank()) return

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NUMBER, number)
            .putString(KEY_NAME, name?.ifBlank { null })
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Recorded recent incoming call: number=$number, name=$name")
    }

    fun getLatestIncomingCall(context: Context): Triple<String, String?, Long>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefNumber = prefs.getString(KEY_NUMBER, null)
        val prefName = prefs.getString(KEY_NAME, null)
        val prefTime = prefs.getLong(KEY_TIME, 0L)

        // Also check system CallLog for the latest incoming or missed call
        var logNumber: String? = null
        var logTime = 0L

        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE),
                "${CallLog.Calls.TYPE} IN (?, ?, ?)",
                arrayOf(
                    CallLog.Calls.INCOMING_TYPE.toString(),
                    CallLog.Calls.MISSED_TYPE.toString(),
                    CallLog.Calls.REJECTED_TYPE.toString()
                ),
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                    val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                    if (numIdx != -1 && dateIdx != -1) {
                        logNumber = it.getString(numIdx)
                        logTime = it.getLong(dateIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query CallLog for recent incoming call: ${e.message}")
        }

        return when {
            prefNumber != null && prefTime >= logTime -> Triple(prefNumber, prefName, prefTime)
            logNumber != null && logNumber!!.isNotBlank() -> Triple(logNumber!!, null, logTime)
            prefNumber != null -> Triple(prefNumber, prefName, prefTime)
            else -> null
        }
    }
}
