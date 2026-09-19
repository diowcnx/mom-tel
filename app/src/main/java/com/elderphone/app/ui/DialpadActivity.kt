package com.elderphone.app.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.elderphone.app.R
import com.elderphone.app.data.ContactRepository
import com.elderphone.app.databinding.ActivityDialpadBinding

class DialpadActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DialpadActivity"
    }

    private lateinit var binding: ActivityDialpadBinding
    private val currentNumber = StringBuilder()
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.isNavigatingInternally = true
        binding = ActivityDialpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 80)
        } catch (e: Exception) {
            toneGenerator = null
        }

        setupButtons()
        applyLockModeIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        MainActivity.isNavigatingInternally = true
        applyLockModeIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
    }

    private fun applyLockModeIfNeeded() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isLocked = prefs.getBoolean(MainActivity.KEY_FOREGROUND_LOCKED, false)
        applyImmersiveMode(isLocked)
    }

    private fun applyImmersiveMode(locked: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary)
        if (locked) {
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupButtons() {
        binding.btnDialpadBack.setOnClickListener {
            vibrate()
            finish()
        }

        val keyButtons = listOf(
            binding.btnKey0 to "0",
            binding.btnKey1 to "1",
            binding.btnKey2 to "2",
            binding.btnKey3 to "3",
            binding.btnKey4 to "4",
            binding.btnKey5 to "5",
            binding.btnKey6 to "6",
            binding.btnKey7 to "7",
            binding.btnKey8 to "8",
            binding.btnKey9 to "9",
            binding.btnKeyStar to "*",
            binding.btnKeyHash to "#"
        )

        for ((button, char) in keyButtons) {
            button.setOnClickListener {
                appendDigit(char)
            }
        }

        binding.btnKey0.setOnLongClickListener {
            appendDigit("+")
            true
        }

        binding.btnBackspace.setOnClickListener {
            if (currentNumber.isNotEmpty()) {
                vibrate()
                currentNumber.deleteCharAt(currentNumber.length - 1)
                updateDisplay()
            }
        }

        binding.btnBackspace.setOnLongClickListener {
            if (currentNumber.isNotEmpty()) {
                vibrate()
                currentNumber.clear()
                updateDisplay()
            }
            true
        }

        binding.btnDialpadCall.setOnClickListener {
            val number = currentNumber.toString()
            if (number.isNotBlank()) {
                vibrate()
                callNumber(number)
            } else {
                Toast.makeText(this, "กรุณากดเบอร์โทรศัพท์", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun appendDigit(char: String) {
        vibrate()
        playTone(char)
        currentNumber.append(char)
        updateDisplay()
    }

    private fun updateDisplay() {
        binding.tvDialedNumber.text = currentNumber.toString()
    }

    private fun playTone(char: String) {
        val tone = when (char) {
            "0" -> ToneGenerator.TONE_DTMF_0
            "1" -> ToneGenerator.TONE_DTMF_1
            "2" -> ToneGenerator.TONE_DTMF_2
            "3" -> ToneGenerator.TONE_DTMF_3
            "4" -> ToneGenerator.TONE_DTMF_4
            "5" -> ToneGenerator.TONE_DTMF_5
            "6" -> ToneGenerator.TONE_DTMF_6
            "7" -> ToneGenerator.TONE_DTMF_7
            "8" -> ToneGenerator.TONE_DTMF_8
            "9" -> ToneGenerator.TONE_DTMF_9
            "*" -> ToneGenerator.TONE_DTMF_S
            "#" -> ToneGenerator.TONE_DTMF_P
            else -> -1
        }
        if (tone != -1) {
            toneGenerator?.startTone(tone, 120)
        }
    }

    private fun isDefaultDialer(): Boolean {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        return telecomManager.defaultDialerPackage == packageName
    }

    private fun callNumber(number: String) {
        val rawNumber = number.ifBlank { return }
        val cleanNumber = ContactRepository.normalizePhoneNumber(rawNumber).ifBlank {
            rawNumber.replace("[^0-9+*#]".toRegex(), "")
        }
        if (cleanNumber.isBlank()) return

        MainActivity.isNavigatingInternally = true
        Log.d(TAG, "callNumber: Calling $cleanNumber")

        try {
            val uri = if (cleanNumber.contains("#")) {
                Uri.parse("tel:" + cleanNumber.replace("#", "%23"))
            } else {
                Uri.fromParts("tel", cleanNumber, null)
            }
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null && isDefaultDialer()) {
                Log.d(TAG, "Placing call via TelecomManager to $cleanNumber")
                telecomManager.placeCall(uri, Bundle())
                finish()
            } else {
                Log.d(TAG, "Placing call via ACTION_CALL to $cleanNumber")
                val intent = Intent(Intent.ACTION_CALL, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                finish()
            }
        } catch (e: SecurityException) {
            MainActivity.isNavigatingInternally = false
            Toast.makeText(this, "กรุณาอนุญาตสิทธิ์การโทรในระบบ", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try {
                val uri = if (cleanNumber.contains("#")) {
                    Uri.parse("tel:" + cleanNumber.replace("#", "%23"))
                } else {
                    Uri.fromParts("tel", cleanNumber, null)
                }
                val intent = Intent(Intent.ACTION_CALL, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                finish()
            } catch (e2: Exception) {
                MainActivity.isNavigatingInternally = false
                Toast.makeText(this, "ไม่สามารถโทรออกได้: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(35)
        }
    }
}
