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
                Log.i(TAG, "Auto-rejecting a blocked call")
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

        // No call-hold (the user cannot manage held calls): an incoming call-waiting call is
        // rejected straight away, so the caller hears "busy" and the current call is untouched.
        if (initialState == Call.STATE_RINGING && CallManager.hasOtherLiveCall(call)) {
            Log.i(TAG, "Rejecting a call-waiting call while another call is in progress")
            try {
                call.reject(false, null)
            } catch (e: Exception) {
                try {
                    call.disconnect()
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to reject call-waiting call", e2)
                }
            }
            CallManager.scheduleResumeHeldCalls()
            return
        }

        // Same rule for outgoing: a new call started while another call is in progress would
        // put that call on hold. End the new call and keep the old one.
        if (initialState != Call.STATE_RINGING && CallManager.hasOtherLiveCall(call)) {
            Log.i(TAG, "Rejecting a second outgoing call while another call is in progress")
            try {
                call.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect second outgoing call", e)
            }
            CallManager.returnToOngoingCall(this)
            CallManager.scheduleResumeHeldCalls()
            return
        }

        CallManager.addCall(call)

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
        // Do not detach the service here: during call-waiting another call is still alive and
        // still needs the service for hang-up, speaker and mute. onDestroy detaches it.
        CallManager.removeCall(call)
        CallManager.scheduleResumeHeldCalls()
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
