package com.elderphone.app.service

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import com.elderphone.app.data.BlockedNumberManager
import com.elderphone.app.data.RecentCallManager
import com.elderphone.app.ui.IncomingCallActivity
import com.elderphone.app.ui.OngoingCallActivity

class CallService : InCallService() {

    companion object {
        private const val TAG = "CallService"
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val initialState = call.compatState()
        Log.d(TAG, "onCallAdded: state = $initialState")

        // Check if incoming call is from a blocked number
        val callerNumber = call.details.handle?.schemeSpecificPart ?: ""
        if (initialState == Call.STATE_RINGING && callerNumber.isNotBlank()) {
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

        CallManager.attachService(this)
        CallManager.setCall(call)

        when (initialState) {
            Call.STATE_RINGING -> {
                if (callerNumber.isNotBlank()) {
                    RecentCallManager.recordCall(this, callerNumber, call.details.callerDisplayName)
                }

                // Launch incoming call screen
                val intent = Intent(this, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("phone_number", callerNumber)
                    putExtra("display_name", call.details.callerDisplayName)
                }
                startActivity(intent)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE -> {
                if (callerNumber.isNotBlank()) {
                    RecentCallManager.recordCall(this, callerNumber, call.details.callerDisplayName)
                }
                try {
                    routeToSpeaker()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set speaker route in CallService", e)
                }

                // Launch ongoing call screen
                val intent = Intent(this, OngoingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("phone_number", callerNumber)
                    putExtra("display_name", call.details.callerDisplayName)
                }
                startActivity(intent)
            }
            else -> {
                Log.d(TAG, "Unhandled call state: $initialState")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun routeToSpeaker() {
        setAudioRoute(android.telecom.CallAudioState.ROUTE_SPEAKER)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")
        if (CallManager.currentCall == call) {
            CallManager.setCall(null)
        }
        CallManager.detachService(this)
    }

    override fun onDestroy() {
        CallManager.detachService(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Call.compatState(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) details.state else state
    }
}
