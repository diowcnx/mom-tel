package com.elderphone.app.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Call
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.elderphone.app.R
import com.elderphone.app.data.ContactRepository
import com.elderphone.app.databinding.ActivityOngoingCallBinding
import com.elderphone.app.service.CallManager
import java.util.Locale

class OngoingCallActivity : AppCompatActivity(), CallManager.CallStateCallback {

    private lateinit var binding: ActivityOngoingCallBinding
    private var isSpeakerOn = false
    private var isMuted = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private var callDurationSeconds = 0

    private val timerRunnable = object : Runnable {
        override fun run() {
            callDurationSeconds++
            val minutes = callDurationSeconds / 60
            val seconds = callDurationSeconds % 60
            binding.tvCallDuration.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityOngoingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallManager.registerCallback(this)
        displayCallerInfo()
        setupButtons()
        startCallTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.unregisterCallback(this)
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun startCallTimer() {
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    private fun displayCallerInfo() {
        val number = CallManager.getCallerNumber()
        val displayName = CallManager.getCallerDisplayName()

        Thread {
            val contact = if (number.isNotBlank()) {
                ContactRepository.findContactByNumber(this, number)
            } else null

            runOnUiThread {
                if (contact != null) {
                    binding.tvOngoingCallerName.text = contact.name
                    binding.tvOngoingCallerNumber.text = number.ifBlank { contact.phoneNumber }

                    if (!contact.photoUri.isNullOrBlank()) {
                        binding.imgOngoingAvatar.visibility = View.VISIBLE
                        binding.tvOngoingAvatarInitial.visibility = View.GONE
                        Glide.with(this)
                            .load(contact.photoUri)
                            .placeholder(R.drawable.ic_person)
                            .error(R.drawable.ic_person)
                            .into(binding.imgOngoingAvatar)
                    } else {
                        binding.imgOngoingAvatar.visibility = View.GONE
                        binding.tvOngoingAvatarInitial.visibility = View.VISIBLE
                        binding.tvOngoingAvatarInitial.text = contact.initial
                        val colorRes = ContactRepository.getAvatarColorForName(contact.name)
                        binding.viewOngoingAvatarBg.background.setTint(ContextCompat.getColor(this, colorRes))
                    }
                } else {
                    binding.tvOngoingCallerName.text = displayName?.ifBlank { null } ?: number.ifBlank { "กำลังสนทนา" }
                    binding.tvOngoingCallerNumber.text = if (displayName != null && displayName != number) number else ""
                    binding.imgOngoingAvatar.visibility = View.VISIBLE
                    binding.tvOngoingAvatarInitial.visibility = View.GONE
                    binding.imgOngoingAvatar.setImageResource(R.drawable.ic_person)
                }
            }
        }.start()
    }

    private fun setupButtons() {
        // Speakerphone Button
        binding.btnSpeaker.setOnClickListener {
            vibrate()
            isSpeakerOn = !isSpeakerOn
            CallManager.setSpeakerphone(isSpeakerOn, this)
            updateSpeakerUi()
        }

        // Mute Button
        binding.btnMute.setOnClickListener {
            vibrate()
            isMuted = !isMuted
            CallManager.setMuted(isMuted)
            updateMuteUi()
        }

        // Giant Hangup Button
        binding.btnOngoingHangup.setOnClickListener {
            vibrate()
            CallManager.hangup()
            finish()
        }
    }

    private fun updateSpeakerUi() {
        if (isSpeakerOn) {
            binding.btnSpeaker.setIconResource(R.drawable.ic_speaker)
            binding.btnSpeaker.text = getString(R.string.btn_speaker_off)
            binding.btnSpeaker.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        } else {
            binding.btnSpeaker.setIconResource(R.drawable.ic_speaker_off)
            binding.btnSpeaker.text = getString(R.string.btn_speaker_on)
            binding.btnSpeaker.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))
        }
    }

    private fun updateMuteUi() {
        if (isMuted) {
            binding.btnMute.setIconResource(R.drawable.ic_mic_off)
            binding.btnMute.text = getString(R.string.btn_unmute)
            binding.btnMute.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_end_red))
        } else {
            binding.btnMute.setIconResource(R.drawable.ic_mic)
            binding.btnMute.text = getString(R.string.btn_mute)
            binding.btnMute.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))
        }
    }

    override fun onCallStateChanged(state: Int) {
        if (state == Call.STATE_DISCONNECTED) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.call_ended), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCallDisconnected() {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.call_ended), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }
}
