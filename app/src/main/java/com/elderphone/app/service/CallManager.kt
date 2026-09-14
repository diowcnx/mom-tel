package com.elderphone.app.service

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log

object CallManager {
    private const val TAG = "CallManager"

    var currentCall: Call? = null
        private set

    var inCallService: InCallService? = null

    interface CallStateCallback {
        fun onCallStateChanged(state: Int)
        fun onCallDisconnected()
    }

    private val callbacks = mutableListOf<CallStateCallback>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "Call state changed: $state")
            callbacks.forEach { it.onCallStateChanged(state) }
            if (state == Call.STATE_DISCONNECTED) {
                callbacks.forEach { it.onCallDisconnected() }
                currentCall = null
            }
        }
    }

    fun registerCallback(callback: CallStateCallback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback)
        }
    }

    fun unregisterCallback(callback: CallStateCallback) {
        callbacks.remove(callback)
    }

    fun setCall(call: Call?) {
        currentCall?.unregisterCallback(callCallback)
        currentCall = call
        currentCall?.registerCallback(callCallback)
        if (call != null) {
            callbacks.forEach { it.onCallStateChanged(call.state) }
        }
    }

    fun answer() {
        try {
            currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
            Log.d(TAG, "Call answered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call", e)
        }
    }

    fun hangup() {
        try {
            if (currentCall?.state == Call.STATE_RINGING) {
                currentCall?.reject(false, null)
            } else {
                currentCall?.disconnect()
            }
            Log.d(TAG, "Call disconnected / rejected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting call", e)
        }
    }

    fun setSpeakerphone(enable: Boolean, context: Context) {
        try {
            if (inCallService != null) {
                val route = if (enable) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
                inCallService?.setAudioRoute(route)
            } else {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.isSpeakerphoneOn = enable
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling speakerphone", e)
        }
    }

    fun setMuted(muted: Boolean) {
        try {
            inCallService?.setMuted(muted)
        } catch (e: Exception) {
            Log.e(TAG, "Error muting microphone", e)
        }
    }

    fun getCallerNumber(): String {
        val uri: Uri? = currentCall?.details?.handle
        if (uri != null) {
            val schemeSpecific = uri.schemeSpecificPart
            if (!schemeSpecific.isNullOrBlank()) {
                return schemeSpecific
            }
        }
        return ""
    }

    fun getCallerDisplayName(): String? {
        return currentCall?.details?.callerDisplayName
    }
}
