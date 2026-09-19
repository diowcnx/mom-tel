package com.elderphone.app.ui

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.PopupWindow
import com.elderphone.app.R
import com.elderphone.app.data.BlockedNumberManager
import com.elderphone.app.data.ContactRepository
import com.elderphone.app.databinding.ActivityMainBinding
import com.elderphone.app.databinding.DialogContactActionBinding
import com.elderphone.app.databinding.DialogEditNameBinding
import com.elderphone.app.databinding.DialogEditPhotoBinding
import com.elderphone.app.databinding.DialogManageBlockedNumbersBinding
import com.elderphone.app.databinding.ItemElderContactBinding
import com.elderphone.app.databinding.PopoverLockMenuBinding
import androidx.activity.OnBackPressedCallback
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.elderphone.app.model.ElderContact
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val PREFS_NAME = "elder_phone_prefs"
        const val KEY_FOREGROUND_LOCKED = "is_foreground_locked"
        private const val CHANNEL_LOCK_GUARD = "elder_lock_guard_channel"
        var isNavigatingInternally = false
    }

    private var isForegroundLocked = false

    private lateinit var binding: ActivityMainBinding
    private val contacts = mutableListOf<ElderContact>()
    private var currentPage = 0
    private val contactsPerPage = 6

    private lateinit var searchAdapter: SearchContactAdapter
    private var currentEditingContact: ElderContact? = null
    private var tempCameraUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleSelectedImageUri(it) }
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && tempCameraUri != null) {
            handleSelectedImageUri(tempCameraUri!!)
        }
    }

    private val requestRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkDefaultDialer()
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            loadContacts()
        } else {
            Toast.makeText(
                this,
                getString(R.string.permission_required_message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createLockGuardNotificationChannel()
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isForegroundLocked = prefs.getBoolean(KEY_FOREGROUND_LOCKED, false)
        updateLockUi()
        applyImmersiveMode(isForegroundLocked)

        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (isForegroundLocked && (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0)) {
                window.decorView.postDelayed({
                    if (isForegroundLocked) {
                        applyImmersiveMode(true)
                    }
                }, 1000)
            }
        }

        setupButtons()
        setupSearch()
        setupBackNavigation()
        checkAndRequestPermissions()
        checkDefaultDialer()
    }

    override fun onResume() {
        super.onResume()
        if (com.elderphone.app.service.CallManager.currentCall == null) {
            isNavigatingInternally = false
        }
        checkDefaultDialer()
        if (hasPermissions()) {
            loadContacts()
        }
        applyImmersiveMode(isForegroundLocked)
        if (isForegroundLocked && !isInLockTaskMode()) {
            try {
                startLockTask()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume lock task: ${e.message}")
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isForegroundLocked && !isNavigatingInternally && com.elderphone.app.service.CallManager.currentCall == null) {
            vibrate()
            triggerFullScreenRelock()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.removeCallbacks(null)
            if (isForegroundLocked) {
                applyImmersiveMode(true)
            }
        } else if (isForegroundLocked && !isNavigatingInternally && com.elderphone.app.service.CallManager.currentCall == null) {
            window.decorView.postDelayed({
                if (isForegroundLocked && !isNavigatingInternally && com.elderphone.app.service.CallManager.currentCall == null) {
                    triggerFullScreenRelock()
                }
            }, 300)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isForegroundLocked && !isNavigatingInternally && com.elderphone.app.service.CallManager.currentCall == null) {
            triggerFullScreenRelock()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val isSearching = binding.rvSearchResults.visibility == View.VISIBLE ||
                        binding.tvNoSearchResults.visibility == View.VISIBLE ||
                        !binding.etSearchContact.text.isNullOrEmpty() ||
                        binding.etSearchContact.hasFocus()
                if (isSearching || currentPage > 0) {
                    returnToFirstPage()
                } else {
                    if (isForegroundLocked) {
                        vibrate()
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.msg_app_is_locked),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun returnToFirstPage() {
        vibrate()
        // 1. Clear search query text if any
        if (!binding.etSearchContact.text.isNullOrEmpty()) {
            binding.etSearchContact.text?.clear()
        }
        // 2. Hide keyboard and clear focus
        binding.etSearchContact.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchContact.windowToken, 0)

        // 3. Reset search visibility state
        binding.btnClearSearch.visibility = View.GONE
        binding.rvSearchResults.visibility = View.GONE
        binding.tvNoSearchResults.visibility = View.GONE
        binding.gridContacts.visibility = View.VISIBLE
        binding.btnPrevPage.visibility = View.VISIBLE
        binding.btnNextPage.visibility = View.VISIBLE

        // 4. Navigate to first page
        currentPage = 0
        displayPage(0)
    }

    private fun setupButtons() {
        binding.btnToggleLock.setOnClickListener {
            vibrate()
            showLockMenuPopover(it)
        }

        binding.tvAppTitle.setOnClickListener {
            returnToFirstPage()
        }

        binding.btnDialpad.setOnClickListener {
            vibrate()
            isNavigatingInternally = true
            val intent = Intent(this, DialpadActivity::class.java)
            startActivity(intent)
        }

        binding.btnSetDefaultDialer.setOnClickListener {
            vibrate()
            isNavigatingInternally = true
            requestDefaultDialerRole()
        }

        binding.btnPrevPage.setOnClickListener {
            if (currentPage > 0) {
                vibrate()
                currentPage--
                displayPage(currentPage)
            }
        }

        binding.btnNextPage.setOnClickListener {
            val totalPages = getTotalPages()
            if (currentPage < totalPages - 1) {
                vibrate()
                currentPage++
                displayPage(currentPage)
            }
        }
    }

    private fun showLockMenuPopover(anchorView: View) {
        isNavigatingInternally = true
        val popoverBinding = PopoverLockMenuBinding.inflate(layoutInflater)
        val popupWindow = PopupWindow(
            popoverBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                isNavigatingInternally = false
            }
        }

        if (isForegroundLocked) {
            popoverBinding.ivLockIcon.setImageResource(R.drawable.ic_lock_open)
            popoverBinding.tvLockTitle.text = getString(R.string.menu_unlock_app)
        } else {
            popoverBinding.ivLockIcon.setImageResource(R.drawable.ic_lock)
            popoverBinding.tvLockTitle.text = getString(R.string.menu_lock_app)
        }

        popoverBinding.itemLock.setOnClickListener {
            vibrate()
            popupWindow.dismiss()
            toggleForegroundLock()
        }

        popoverBinding.itemBlockedNumbers.setOnClickListener {
            vibrate()
            popupWindow.dismiss()
            showBlockedNumbersDialog()
        }

        popoverBinding.itemExit.setOnClickListener {
            vibrate()
            popupWindow.dismiss()
            exitApp()
        }

        popupWindow.showAsDropDown(anchorView, 0, 10)
    }

    private fun showBlockedNumbersDialog() {
        isNavigatingInternally = true
        val dialogBinding = DialogManageBlockedNumbersBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            isNavigatingInternally = false
            loadContacts()
        }

        fun refreshList() {
            val list = BlockedNumberManager.getBlockedNumbers(this)
            if (list.isEmpty()) {
                dialogBinding.tvEmptyBlockedList.visibility = View.VISIBLE
                dialogBinding.rvBlockedNumbers.visibility = View.GONE
            } else {
                dialogBinding.tvEmptyBlockedList.visibility = View.GONE
                dialogBinding.rvBlockedNumbers.visibility = View.VISIBLE
                val adapter = BlockedNumbersAdapter(list) { item ->
                    vibrate()
                    BlockedNumberManager.unblockNumber(this, item.number)
                    Toast.makeText(this, getString(R.string.unblock_success), Toast.LENGTH_SHORT).show()
                    refreshList()
                    loadContacts()
                }
                dialogBinding.rvBlockedNumbers.layoutManager = LinearLayoutManager(this)
                dialogBinding.rvBlockedNumbers.adapter = adapter
            }
        }

        refreshList()

        dialogBinding.btnAddBlockedNumber.setOnClickListener {
            val input = dialogBinding.etBlockedNumberInput.text?.toString()?.trim() ?: ""
            if (input.isNotBlank()) {
                vibrate()
                val added = BlockedNumberManager.blockNumber(this, input)
                if (added) {
                    Toast.makeText(this, getString(R.string.block_success), Toast.LENGTH_SHORT).show()
                    dialogBinding.etBlockedNumberInput.text?.clear()
                    refreshList()
                    loadContacts()
                } else {
                    Toast.makeText(this, "เบอร์นี้อยู่ในรายการบล็อกแล้ว", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "กรุณากรอกเบอร์โทรศัพท์", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnCloseBlockedDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun exitApp() {
        isNavigatingInternally = true
        unlockAppBeforeExit()
        window.decorView.removeCallbacks(null)
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start home intent: ${e.message}")
        }
        finishAffinity()
    }

    private fun unlockAppBeforeExit() {
        isNavigatingInternally = true
        isForegroundLocked = false
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FOREGROUND_LOCKED, false)
            .apply()
        updateLockUi()
        applyImmersiveMode(false)
        updateGestureExclusion()
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stopLockTask on exit: ${e.message}")
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(9999)
        window.decorView.removeCallbacks(null)
    }

    private fun toggleForegroundLock() {
        val newState = !isForegroundLocked
        isForegroundLocked = newState
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FOREGROUND_LOCKED, newState)
            .apply()

        updateLockUi()
        applyImmersiveMode(newState)

        if (newState) {
            try {
                startLockTask()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to startLockTask: ${e.message}")
            }
            Toast.makeText(this, getString(R.string.msg_lock_enabled), Toast.LENGTH_SHORT).show()
        } else {
            try {
                stopLockTask()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stopLockTask: ${e.message}")
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(9999)
            Toast.makeText(this, getString(R.string.msg_lock_disabled), Toast.LENGTH_SHORT).show()
        }
    }

    private fun createLockGuardNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_LOCK_GUARD,
                "Elder Lock Guard",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps app locked in foreground"
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun triggerFullScreenRelock() {
        if (!isForegroundLocked || isNavigatingInternally || com.elderphone.app.service.CallManager.currentCall != null) return
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_LOCK_GUARD)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_app_is_locked))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(9999, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed fullScreenIntent: ${e.message}")
        }
        bringAppToFront()
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.root.post {
                if (isForegroundLocked) {
                    val h = binding.root.height
                    val w = binding.root.width
                    if (h > 0 && w > 0) {
                        binding.root.systemGestureExclusionRects = listOf(
                            Rect(0, h - 250, w, h)
                        )
                    }
                } else {
                    binding.root.systemGestureExclusionRects = emptyList()
                }
            }
        }
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
        updateGestureExclusion()
    }

    private fun bringAppToFront() {
        if (!isForegroundLocked || isNavigatingInternally || com.elderphone.app.service.CallManager.currentCall != null) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
        } catch (e: Exception) {
            Log.e(TAG, "Failed moveTaskToFront: ${e.message}")
        }
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed startActivity reorder: ${e.message}")
        }
    }

    private fun updateLockUi() {
        if (isForegroundLocked) {
            binding.btnToggleLock.setImageResource(R.drawable.ic_lock)
            binding.btnToggleLock.setColorFilter(Color.parseColor("#FFD54F"))
            binding.btnToggleLock.contentDescription = getString(R.string.msg_app_is_locked)
        } else {
            binding.btnToggleLock.setImageResource(R.drawable.ic_lock_open)
            binding.btnToggleLock.setColorFilter(Color.parseColor("#B3FFFFFF"))
            binding.btnToggleLock.contentDescription = getString(R.string.cd_toggle_lock)
        }
    }

    private fun isInLockTaskMode(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }
    }


    private fun setupSearch() {
        searchAdapter = SearchContactAdapter(
            this,
            emptyList(),
            onPhotoClicked = { contact ->
                vibrate()
                callContact(contact)
            },
            onContactClicked = { contact ->
                showContactActionDialog(contact)
            }
        )
        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
        }

        binding.etSearchContact.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterContacts(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            returnToFirstPage()
        }

        binding.etSearchContact.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etSearchContact.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun filterContacts(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            binding.btnClearSearch.visibility = View.GONE
            binding.rvSearchResults.visibility = View.GONE
            binding.tvNoSearchResults.visibility = View.GONE
            binding.gridContacts.visibility = View.VISIBLE
            binding.btnPrevPage.visibility = View.VISIBLE
            binding.btnNextPage.visibility = View.VISIBLE
            currentPage = 0
            displayPage(0)
        } else {
            binding.btnClearSearch.visibility = View.VISIBLE
            binding.gridContacts.visibility = View.GONE
            binding.btnPrevPage.visibility = View.GONE
            binding.btnNextPage.visibility = View.GONE

            val cleanQ = q.replace("-", "").replace(" ", "")
            val filtered = contacts.filter { contact ->
                contact.name.contains(q, ignoreCase = true) ||
                contact.phoneNumber.replace("-", "").replace(" ", "").contains(cleanQ, ignoreCase = true)
            }

            if (filtered.isEmpty()) {
                binding.rvSearchResults.visibility = View.GONE
                binding.tvNoSearchResults.visibility = View.VISIBLE
            } else {
                binding.tvNoSearchResults.visibility = View.GONE
                binding.rvSearchResults.visibility = View.VISIBLE
                searchAdapter.updateData(filtered)
            }
        }
    }

    private fun isDefaultDialer(): Boolean {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        return telecomManager.defaultDialerPackage == packageName
    }

    private fun checkDefaultDialer() {
        if (isDefaultDialer()) {
            binding.cardDefaultDialerPrompt.visibility = View.GONE
        } else {
            binding.cardDefaultDialerPrompt.visibility = View.VISIBLE
        }
    }

    private fun requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                requestRoleLauncher.launch(intent)
            }
        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivity(intent)
        }
    }

    private fun hasPermissions(): Boolean {
        val callPhoneGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val readContactsGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val readPhoneStateGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        return callPhoneGranted && readContactsGranted && readPhoneStateGranted
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun loadContacts() {
        Thread {
            val loaded = ContactRepository.getAllContacts(this)
            runOnUiThread {
                contacts.clear()
                contacts.addAll(loaded)
                val currentSearch = binding.etSearchContact.text?.toString().orEmpty()
                if (currentSearch.isNotBlank()) {
                    filterContacts(currentSearch)
                } else {
                    if (currentPage >= getTotalPages()) {
                        currentPage = max(0, getTotalPages() - 1)
                    }
                    displayPage(currentPage)
                }
            }
        }.start()
    }

    private fun getTotalPages(): Int {
        return max(1, ceil(contacts.size.toDouble() / contactsPerPage).toInt())
    }

    private fun displayPage(page: Int) {
        val totalPages = getTotalPages()

        // Update Prev / Next button states
        binding.btnPrevPage.isEnabled = page > 0
        binding.btnPrevPage.alpha = if (page > 0) 1.0f else 0.4f

        binding.btnNextPage.isEnabled = page < totalPages - 1
        binding.btnNextPage.alpha = if (page < totalPages - 1) 1.0f else 0.4f

        binding.gridContacts.removeAllViews()

        if (contacts.isEmpty()) {
            binding.tvEmptyContacts.visibility = View.VISIBLE
            binding.gridContacts.visibility = View.GONE
            return
        }

        binding.tvEmptyContacts.visibility = View.GONE
        binding.gridContacts.visibility = View.VISIBLE

        val startIndex = page * contactsPerPage
        val endIndex = (startIndex + contactsPerPage).coerceAtMost(contacts.size)
        val pageContacts = contacts.subList(startIndex, endIndex)

        val inflater = LayoutInflater.from(this)

        for (contact in pageContacts) {
            val itemBinding = ItemElderContactBinding.inflate(inflater, binding.gridContacts, false)

            // Layout params for 2 columns x 3 rows grid with optimal spacing (larger blocks)
            val marginH = (5 * resources.displayMetrics.density).toInt()
            val marginV = (5 * resources.displayMetrics.density).toInt()
            val param = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(marginH, marginV, marginH, marginV)
            }
            itemBinding.root.layoutParams = param

            // Populate Contact Info
            itemBinding.tvContactName.text = contact.name

            if (!contact.photoUri.isNullOrBlank()) {
                itemBinding.imgAvatar.visibility = View.VISIBLE
                itemBinding.tvAvatarInitial.visibility = View.GONE
                Glide.with(this)
                    .load(contact.photoUri)
                    .signature(ObjectKey(contact.photoLastModified.toString()))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(itemBinding.imgAvatar)
            } else {
                itemBinding.imgAvatar.visibility = View.GONE
                itemBinding.tvAvatarInitial.visibility = View.VISIBLE
                itemBinding.tvAvatarInitial.text = contact.initial
                val colorRes = ContactRepository.getAvatarColorForName(contact.name)
                itemBinding.viewAvatarBg.background.setTint(ContextCompat.getColor(this, colorRes))
            }

            // Tap photo or card -> Call immediately
            val onPhotoClick = View.OnClickListener {
                callContact(contact)
            }
            val onPhotoLongClick = View.OnLongClickListener {
                vibrate()
                showContactActionDialog(contact)
                true
            }

            itemBinding.layoutContactPhoto.setOnClickListener(onPhotoClick)
            itemBinding.layoutContactPhoto.setOnLongClickListener(onPhotoLongClick)
            itemBinding.cardContact.setOnClickListener(onPhotoClick)
            itemBinding.cardContact.setOnLongClickListener(onPhotoLongClick)

            // Tap name -> Show action dialog (Edit name, change photo, block, etc.)
            itemBinding.layoutContactName.setOnClickListener {
                vibrate()
                showContactActionDialog(contact)
            }
            itemBinding.layoutContactName.setOnLongClickListener {
                vibrate()
                showContactActionDialog(contact)
                true
            }

            binding.gridContacts.addView(itemBinding.root)
        }
    }

    private fun showContactActionDialog(contact: ElderContact) {
        vibrate()

        val dialogBinding = DialogContactActionBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.actionContactName.text = contact.name
        dialogBinding.actionContactNumber.text = contact.phoneNumber

        if (!contact.photoUri.isNullOrBlank()) {
            dialogBinding.actionAvatarImg.visibility = View.VISIBLE
            dialogBinding.actionAvatarInitial.visibility = View.GONE
            Glide.with(this)
                .load(contact.photoUri)
                .signature(ObjectKey(contact.photoLastModified.toString()))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(dialogBinding.actionAvatarImg)
        } else {
            dialogBinding.actionAvatarImg.visibility = View.GONE
            dialogBinding.actionAvatarInitial.visibility = View.VISIBLE
            dialogBinding.actionAvatarInitial.text = contact.initial
            val colorRes = ContactRepository.getAvatarColorForName(contact.name)
            dialogBinding.actionAvatarBg.background.setTint(ContextCompat.getColor(this, colorRes))
        }

        // Tap avatar/photo in dialog -> Call immediately
        val onCallFromAvatar = View.OnClickListener {
            vibrate()
            dialog.dismiss()
            callContact(contact)
        }
        dialogBinding.layoutActionAvatar.setOnClickListener(onCallFromAvatar)
        dialogBinding.actionAvatarImg.setOnClickListener(onCallFromAvatar)
        dialogBinding.actionAvatarBg.setOnClickListener(onCallFromAvatar)
        dialogBinding.actionAvatarInitial.setOnClickListener(onCallFromAvatar)

        // Call button
        dialogBinding.btnActionCall.setOnClickListener {
            vibrate()
            dialog.dismiss()
            callContact(contact)
        }

        // Edit Name button
        dialogBinding.btnActionEditName.setOnClickListener {
            dialog.dismiss()
            showEditNameDialog(contact)
        }

        // Change Photo button
        dialogBinding.btnActionChangePhoto.setOnClickListener {
            dialog.dismiss()
            showEditPhotoDialog(contact)
        }

        // Block / Unblock Contact button
        val isBlocked = BlockedNumberManager.isBlocked(this, contact.phoneNumber)
        if (isBlocked) {
            dialogBinding.btnActionToggleBlock.text = "ปลดบล็อกเบอร์นี้"
            dialogBinding.btnActionToggleBlock.setTextColor(ContextCompat.getColor(this, R.color.primary))
            dialogBinding.btnActionToggleBlock.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        } else {
            dialogBinding.btnActionToggleBlock.text = getString(R.string.btn_block_number)
            dialogBinding.btnActionToggleBlock.setTextColor(Color.parseColor("#E53935"))
            dialogBinding.btnActionToggleBlock.iconTint = ColorStateList.valueOf(Color.parseColor("#E53935"))
        }

        dialogBinding.btnActionToggleBlock.setOnClickListener {
            vibrate()
            dialog.dismiss()
            if (isBlocked) {
                BlockedNumberManager.unblockNumber(this, contact.phoneNumber)
                Toast.makeText(this, getString(R.string.unblock_success), Toast.LENGTH_SHORT).show()
                loadContacts()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.block_confirm_title)
                    .setMessage(getString(R.string.block_confirm_msg, "${contact.name} (${contact.phoneNumber})"))
                    .setPositiveButton(R.string.btn_confirm_block) { _, _ ->
                        BlockedNumberManager.blockNumber(this, contact.phoneNumber, contact.name)
                        Toast.makeText(this, getString(R.string.block_success), Toast.LENGTH_SHORT).show()
                        loadContacts()
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }

        // Cancel button
        dialogBinding.btnActionCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showEditNameDialog(contact: ElderContact) {
        vibrate()

        val dialogBinding = DialogEditNameBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.tvEditNamePhoneNumber.text = "เบอร์โทร: ${contact.phoneNumber}"

        // Query structured name from database (First name & Surname)
        val (initialGiven, initialFamily) = ContactRepository.getContactStructuredName(this, contact.id)
        val (givenToDisplay, familyToDisplay) = if (initialGiven.isNotBlank() || initialFamily.isNotBlank()) {
            Pair(initialGiven, initialFamily)
        } else {
            val trimmedName = contact.name.trim()
            val space = trimmedName.indexOf(' ')
            if (space != -1) {
                Pair(trimmedName.substring(0, space).trim(), trimmedName.substring(space + 1).trim())
            } else {
                Pair(trimmedName, "")
            }
        }

        dialogBinding.etEditContactFirstName.setText(givenToDisplay)
        dialogBinding.etEditContactLastName.setText(familyToDisplay)

        dialogBinding.btnSaveName.setOnClickListener {
            val newFirst = dialogBinding.etEditContactFirstName.text?.toString()?.trim().orEmpty()
            val newLast = dialogBinding.etEditContactLastName.text?.toString()?.trim().orEmpty()

            if (newFirst.isBlank() && newLast.isBlank()) {
                Toast.makeText(this, "กรุณากรอกชื่อหรือนามสกุลอย่างน้อย 1 ช่อง", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fullName = listOf(newFirst, newLast).filter { it.isNotBlank() }.joinToString(" ")

            vibrate()
            dialog.dismiss()

            Thread {
                val success = if (contact.id > 0) {
                    ContactRepository.updateContactName(this, contact.id, newFirst, newLast)
                } else {
                    ContactRepository.insertNewContact(this, newFirst, newLast, contact.phoneNumber) > 0
                }
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "บันทึกชื่อเป็น \"$fullName\" สำเร็จ", Toast.LENGTH_SHORT).show()
                        loadContacts()
                    } else {
                        Toast.makeText(this, "ไม่สามารถบันทึกชื่อได้ กรุณาตรวจสอบสิทธิ์", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        dialogBinding.btnCancelEditName.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        // Focus on first name and place cursor at end
        dialogBinding.etEditContactFirstName.requestFocus()
        dialogBinding.etEditContactFirstName.setSelection(dialogBinding.etEditContactFirstName.text?.length ?: 0)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        dialogBinding.etEditContactFirstName.postDelayed({
            imm?.showSoftInput(dialogBinding.etEditContactFirstName, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun showEditPhotoDialog(contact: ElderContact) {
        vibrate()
        currentEditingContact = contact

        val dialogBinding = DialogEditPhotoBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.dialogContactName.text = contact.name
        dialogBinding.dialogContactNumber.text = contact.phoneNumber

        if (!contact.photoUri.isNullOrBlank()) {
            dialogBinding.dialogAvatarImg.visibility = View.VISIBLE
            dialogBinding.dialogAvatarInitial.visibility = View.GONE
            dialogBinding.btnOptionRemovePhoto.visibility = View.VISIBLE
            Glide.with(this)
                .load(contact.photoUri)
                .signature(ObjectKey(contact.photoLastModified.toString()))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(dialogBinding.dialogAvatarImg)
        } else {
            dialogBinding.dialogAvatarImg.visibility = View.GONE
            dialogBinding.dialogAvatarInitial.visibility = View.VISIBLE
            dialogBinding.btnOptionRemovePhoto.visibility = View.GONE
            dialogBinding.dialogAvatarInitial.text = contact.initial
            val colorRes = ContactRepository.getAvatarColorForName(contact.name)
            dialogBinding.dialogAvatarBg.background.setTint(ContextCompat.getColor(this, colorRes))
        }

        // 1. Camera
        dialogBinding.btnOptionCamera.setOnClickListener {
            dialog.dismiss()
            launchCamera(contact)
        }

        // 2. Gallery
        dialogBinding.btnOptionGallery.setOnClickListener {
            dialog.dismiss()
            launchGallery(contact)
        }

        // 3. System Contact Editor
        dialogBinding.btnOptionSystemEdit.setOnClickListener {
            dialog.dismiss()
            launchSystemContactEdit(contact)
        }

        // 4. Remove custom photo
        dialogBinding.btnOptionRemovePhoto.setOnClickListener {
            dialog.dismiss()
            removeContactPhoto(contact)
        }

        // Cancel
        dialogBinding.btnCancelDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun launchCamera(contact: ElderContact) {
        currentEditingContact = contact
        isNavigatingInternally = true
        try {
            val cacheFile = File(cacheDir, "camera_temp.jpg")
            tempCameraUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                cacheFile
            )
            takePhotoLauncher.launch(tempCameraUri)
        } catch (e: Exception) {
            isNavigatingInternally = false
            Log.e("MainActivity", "Error launching camera", e)
            Toast.makeText(this, "ไม่สามารถเปิดกล้องได้: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchGallery(contact: ElderContact) {
        currentEditingContact = contact
        isNavigatingInternally = true
        try {
            pickImageLauncher.launch("image/*")
        } catch (e: Exception) {
            isNavigatingInternally = false
            Log.e("MainActivity", "Error launching gallery picker", e)
            Toast.makeText(this, "ไม่สามารถเปิดคลังภาพได้", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchSystemContactEdit(contact: ElderContact) {
        isNavigatingInternally = true
        try {
            val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contact.id)
            val intent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(contactUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            }
            startActivity(intent)
        } catch (e: Exception) {
            isNavigatingInternally = false
            Log.e("MainActivity", "Error launching system contact editor", e)
            Toast.makeText(this, "ไม่สามารถเปิดสมุดโทรศัพท์ได้", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedImageUri(uri: Uri) {
        val contact = currentEditingContact ?: return
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                var bitmap: Bitmap? = if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null

                if (bitmap != null && bytes != null) {
                    // Check and fix EXIF rotation if needed
                    try {
                        java.io.ByteArrayInputStream(bytes).use { exifStream ->
                            val exif = android.media.ExifInterface(exifStream)
                            val orientation = exif.getAttributeInt(
                                android.media.ExifInterface.TAG_ORIENTATION,
                                android.media.ExifInterface.ORIENTATION_NORMAL
                            )
                            val matrix = android.graphics.Matrix()
                            when (orientation) {
                                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            }
                            if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                                orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180 ||
                                orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270
                            ) {
                                bitmap = Bitmap.createBitmap(
                                    bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, matrix, true
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Failed to check EXIF orientation", e)
                    }

                    val saved = ContactRepository.saveContactPhoto(this, contact, bitmap!!)
                    runOnUiThread {
                        if (saved) {
                            try {
                                Glide.get(applicationContext).clearMemory()
                            } catch (e: Exception) {}
                            Thread {
                                try {
                                    Glide.get(applicationContext).clearDiskCache()
                                } catch (e: Exception) {}
                            }.start()
                            Toast.makeText(this, getString(R.string.photo_updated_success), Toast.LENGTH_SHORT).show()
                            loadContacts()
                        } else {
                            Toast.makeText(this, "ไม่สามารถบันทึกรูปภาพได้", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error processing photo", e)
                runOnUiThread {
                    Toast.makeText(this, "เกิดข้อผิดพลาดในการประมวลผลรูปภาพ", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun removeContactPhoto(contact: ElderContact) {
        Thread {
            val deleted = ContactRepository.deleteContactPhoto(this, contact)
            runOnUiThread {
                if (deleted) {
                    try {
                        Glide.get(applicationContext).clearMemory()
                    } catch (e: Exception) {}
                    Thread {
                        try {
                            Glide.get(applicationContext).clearDiskCache()
                        } catch (e: Exception) {}
                    }.start()
                    Toast.makeText(this, getString(R.string.photo_removed_success), Toast.LENGTH_SHORT).show()
                    loadContacts()
                }
            }
        }.start()
    }

    private fun callContact(contact: ElderContact) {
        val rawNumber = contact.phoneNumber.ifBlank { return }
        val cleanNumber = ContactRepository.normalizePhoneNumber(rawNumber).ifBlank {
            rawNumber.replace("[^0-9+]".toRegex(), "")
        }
        if (cleanNumber.isBlank()) return

        vibrate()
        isNavigatingInternally = true
        Log.d(TAG, "callContact: Calling $cleanNumber (display: ${contact.name})")

        try {
            val uri = Uri.fromParts("tel", cleanNumber, null)
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (telecomManager != null && isDefaultDialer()) {
                Log.d(TAG, "Placing call via TelecomManager to $cleanNumber")
                telecomManager.placeCall(uri, Bundle())
            } else {
                Log.d(TAG, "Placing call via ACTION_CALL to $cleanNumber")
                val intent = Intent(Intent.ACTION_CALL, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        } catch (e: SecurityException) {
            isNavigatingInternally = false
            Toast.makeText(this, "กรุณาอนุญาตสิทธิ์การโทรในระบบ", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try {
                val uri = Uri.fromParts("tel", cleanNumber, null)
                val intent = Intent(Intent.ACTION_CALL, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e2: Exception) {
                isNavigatingInternally = false
                Toast.makeText(this, "ไม่สามารถโทรออกได้: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
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
