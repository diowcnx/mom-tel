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
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.elderphone.app.databinding.ActivityDialpadBinding

class DialpadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialpadBinding
    private val currentNumber = StringBuilder()
    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 80)
        } catch (e: Exception) {
            toneGenerator = null
        }

        setupButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
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

    private fun callNumber(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
            }
            startActivity(intent)
        } catch (e: SecurityException) {
            Toast.makeText(this, "กรุณาอนุญาตสิทธิ์การโทรในระบบ", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ไม่สามารถโทรออกได้: ${e.message}", Toast.LENGTH_SHORT).show()
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
