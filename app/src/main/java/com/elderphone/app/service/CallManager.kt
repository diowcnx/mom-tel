package com.elderphone.app.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import android.widget.Toast
import com.elderphone.app.R
import com.elderphone.app.ui.OngoingCallActivity
import java.lang.ref.WeakReference

object CallManager {
    private const val TAG = "CallManager"

    /** The call the UI is focused on (usually the most recently added one). */
    var currentCall: Call? = null
        private set

    /** Every call Telecom has handed us that has not been removed yet. */
    private val trackedCalls = mutableListOf<Call>()

    private var inCallServiceRef = WeakReference<InCallService>(null)

    interface CallStateCallback {
        fun onCallStateChanged(state: Int)
        fun onCallDisconnected()
    }

    private val callbacks = mutableListOf<CallStateCallback>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "Call state changed: $state")
            if (state == Call.STATE_HOLDING) {
                scheduleResumeHeldCalls()
            }
            if (state == Call.STATE_DISCONNECTED) {
                onTrackedCallEnded(call)
            } else if (call === currentCall) {
                callbacks.toList().forEach { it.onCallStateChanged(state) }
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

    /** Called by [CallService.onCallAdded]: track the call and focus the UI on it. */
    fun addCall(call: Call) {
        if (trackedCalls.none { it === call }) {
            trackedCalls.add(call)
            call.registerCallback(callCallback)
        }
        currentCall = call
        callbacks.toList().forEach { it.onCallStateChanged(call.compatState()) }
    }

    /** Called by [CallService.onCallRemoved]. */
    fun removeCall(call: Call) {
        call.unregisterCallback(callCallback)
        val wasTracked = trackedCalls.removeAll { it === call }
        if (wasTracked) {
            onTrackedCallEnded(call)
        }
    }

    /**
     * A call ended. If it was the focused one, move focus to another call that is still alive
     * (e.g. the first call during call-waiting) instead of dropping to null, otherwise the
     * hang-up button would have nothing to end while the other call keeps going.
     */
    private fun onTrackedCallEnded(call: Call) {
        if (call !== currentCall) return
        val next = liveCalls().lastOrNull()
        currentCall = next
        if (next != null) {
            callbacks.toList().forEach { it.onCallStateChanged(next.compatState()) }
        } else {
            callbacks.toList().forEach { it.onCallStateChanged(Call.STATE_DISCONNECTED) }
            callbacks.toList().forEach { it.onCallDisconnected() }
        }
    }

    /** Calls that are not yet disconnected, from our list plus Telecom's own list. */
    private fun liveCalls(): List<Call> {
        val all = LinkedHashSet<Call>()
        all.addAll(trackedCalls)
        try {
            inCallServiceRef.get()?.calls?.let { all.addAll(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read calls from InCallService", e)
        }
        return all.filter {
            val state = it.compatState()
            state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING
        }
    }

    fun hasLiveCall(): Boolean = liveCalls().isNotEmpty()

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val RESUME_HELD_DELAY_MS = 700L

    private val resumeHeldRunnable = Runnable { resumeHeldCalls() }

    /**
     * No call-hold for this user. The modem can put a call on hold *after* the call that caused
     * it was already rejected (seen on the Infinix test phone), and then nothing un-holds it.
     * So whenever a call is on hold and no other call is going on, take it off hold again.
     */
    fun scheduleResumeHeldCalls() {
        mainHandler.removeCallbacks(resumeHeldRunnable)
        mainHandler.postDelayed(resumeHeldRunnable, RESUME_HELD_DELAY_MS)
    }

    private fun resumeHeldCalls() {
        val live = liveCalls()
        val held = live.filter { it.compatState() == Call.STATE_HOLDING }
        if (held.isEmpty() || held.size != live.size) return
        Log.i(TAG, "Resuming held call (call-hold is not used in this app)")
        try {
            held.last().unhold()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume held call", e)
        }
    }

    /** True when a call other than [call] is still connected, dialing, ringing or on hold. */
    fun hasOtherLiveCall(call: Call): Boolean = liveCalls().any { it !== call }

    /**
     * No call-hold for this user: while a call is in progress, a new outgoing call must not be
     * placed (it would silently put the current call on hold). Instead bring the ongoing-call
     * screen back. Returns true when a call is in progress and the caller should not dial.
     */
    fun returnToOngoingCall(context: Context): Boolean {
        if (!hasLiveCall()) return false
        Toast.makeText(context, R.string.already_in_call, Toast.LENGTH_LONG).show()
        val intent = Intent(context, OngoingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
        return true
    }

    fun attachService(service: InCallService) {
        inCallServiceRef = WeakReference(service)
    }

    fun detachService(service: InCallService) {
        if (inCallServiceRef.get() === service) {
            inCallServiceRef.clear()
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

    /** Incoming-call screen: decline only the ringing call(s), never an ongoing one. */
    fun rejectRinging() {
        val ringing = liveCalls().filter { it.compatState() == Call.STATE_RINGING }
        val targets = ringing.ifEmpty { listOfNotNull(currentCall) }
        targets.forEach { endCall(it) }
    }

    /** End every non-ringing call; if only ringing calls remain, reject all of them. */
    fun hangupAll(): Boolean {
        val live = liveCalls()
        val nonRinging = live.filter { it.compatState() != Call.STATE_RINGING }
        val targets = nonRinging.ifEmpty { live }
        targets.forEach { endCall(it) }
        Log.d(TAG, "hangupAll: asked ${targets.size} call(s) to end")
        return targets.isNotEmpty()
    }

    private fun endCall(call: Call) {
        try {
            if (call.compatState() == Call.STATE_RINGING) {
                call.reject(false, null)
            } else {
                call.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call", e)
            try {
                call.disconnect()
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback disconnect failed", e2)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun setSpeakerphone(enable: Boolean, context: Context) {
        try {
            val inCallService = inCallServiceRef.get()
            if (inCallService != null) {
                val route = if (enable) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
                inCallService.setAudioRoute(route)
            }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = enable
            Log.d(TAG, "setSpeakerphone: enable=$enable (inCallService=${inCallService != null})")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling speakerphone", e)
        }
    }

    fun setMuted(muted: Boolean) {
        try {
            inCallServiceRef.get()?.setMuted(muted)
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

    @Suppress("DEPRECATION")
    private fun Call.compatState(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) details.state else state
    }
}
