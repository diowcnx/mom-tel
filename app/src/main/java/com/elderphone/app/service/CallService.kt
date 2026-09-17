package com.elderphone.app.service

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import com.elderphone.app.data.BlockedNumberManager
import com.elderphone.app.ui.IncomingCallActivity
import com.elderphone.app.ui.OngoingCallActivity

class CallService : InCallService() {

    companion object {
        private const val TAG = "CallService"
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: state = ${call.state}")

        // Check if incoming call is from a blocked number
        val callerNumber = call.details.handle?.schemeSpecificPart ?: ""
        if (call.state == Call.STATE_RINGING && callerNumber.isNotBlank()) {
            if (BlockedNumberManager.isBlocked(this, callerNumber)) {
                Log.i(TAG, "Blocked call from: $callerNumber - Auto-rejecting")
                try {
                    call.reject(false, null)
                } catch (e: Exception) {
                    try {
                        call.disconnect()
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to disconnect blocked call", e2)
                    }
                }
                return
            }
        }

        CallManager.inCallService = this
        CallManager.setCall(call)

        when (call.state) {
            Call.STATE_RINGING -> {
                // Launch incoming call screen
                val intent = Intent(this, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE -> {
                // Launch ongoing call screen
                val intent = Intent(this, OngoingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
            else -> {
                Log.d(TAG, "Unhandled call state: ${call.state}")
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")
        if (CallManager.currentCall == call) {
            CallManager.setCall(null)
        }
        CallManager.inCallService = null
    }
}
