package com.elderphone.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Call
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.elderphone.app.R
import com.elderphone.app.data.ContactRepository
import com.elderphone.app.databinding.ActivityIncomingCallBinding
import com.elderphone.app.service.CallManager

class IncomingCallActivity : AppCompatActivity(), CallManager.CallStateCallback {

    private lateinit var binding: ActivityIncomingCallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAndUnlockScreen()

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallManager.registerCallback(this)
        displayCallerInfo()
        setupButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.unregisterCallback(this)
    }

    private fun wakeAndUnlockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun displayCallerInfo() {
        val number = intent.getStringExtra("phone_number") ?: CallManager.getCallerNumber()
        val displayName = intent.getStringExtra("display_name") ?: CallManager.getCallerDisplayName()

        // Lookup in contacts database
        Thread {
            val contact = if (number.isNotBlank()) {
                ContactRepository.findContactByNumber(this, number)
            } else null

            runOnUiThread {
                if (contact != null) {
                    // Contact found in phonebook
                    binding.tvIncomingCallStatus.text = getString(R.string.incoming_call_title)
                    binding.tvIncomingCallerName.text = contact.name
                    binding.tvIncomingCallerNumber.text = number.ifBlank { contact.phoneNumber }

                    if (!contact.photoUri.isNullOrBlank()) {
                        binding.imgIncomingAvatar.visibility = View.VISIBLE
                        binding.tvIncomingAvatarInitial.visibility = View.GONE
                        Glide.with(this)
                            .load(contact.photoUri)
                            .placeholder(R.drawable.ic_person)
                            .error(R.drawable.ic_person)
                            .into(binding.imgIncomingAvatar)
                    } else {
                        binding.imgIncomingAvatar.visibility = View.GONE
                        binding.tvIncomingAvatarInitial.visibility = View.VISIBLE
                        binding.tvIncomingAvatarInitial.text = contact.initial
                        val colorRes = ContactRepository.getAvatarColorForName(contact.name)
                        binding.viewIncomingAvatarBg.background.setTint(ContextCompat.getColor(this, colorRes))
                    }
                } else {
                    // Unknown / New phone number calling
                    binding.tvIncomingCallStatus.text = getString(R.string.unknown_caller_new)
                    binding.tvIncomingCallerName.text = displayName?.ifBlank { null } ?: number.ifBlank { "ไม่ทราบหมายเลข" }
                    binding.tvIncomingCallerNumber.text = if (displayName != null && displayName != number) number else ""
                    binding.imgIncomingAvatar.visibility = View.VISIBLE
                    binding.tvIncomingAvatarInitial.visibility = View.GONE
                    binding.imgIncomingAvatar.setImageResource(R.drawable.ic_person)
                }
            }
        }.start()
    }

    private fun setupButtons() {
        // Giant Answer Button
        binding.btnAnswer.setOnClickListener {
            vibrate()
            CallManager.answer()
            openOngoingCallScreen()
        }

        // Giant Decline Button
        binding.btnDecline.setOnClickListener {
            vibrate()
            CallManager.hangup()
            finish()
        }
    }

    private fun openOngoingCallScreen() {
        val intent = Intent(this, OngoingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    override fun onCallStateChanged(state: Int) {
        runOnUiThread {
            when (state) {
                Call.STATE_ACTIVE -> {
                    openOngoingCallScreen()
                }
                Call.STATE_DISCONNECTED -> {
                    finish()
                }
            }
        }
    }

    override fun onCallDisconnected() {
        runOnUiThread {
            finish()
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80)
        }
    }
}
