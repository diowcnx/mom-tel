package com.elderphone.app.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.elderphone.app.R
import com.elderphone.app.model.ElderContact
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ContactRepository {
    private const val TAG = "ContactRepository"

    private val avatarColors = intArrayOf(
        R.color.avatar_color_1,
        R.color.avatar_color_2,
        R.color.avatar_color_3,
        R.color.avatar_color_4,
        R.color.avatar_color_5,
        R.color.avatar_color_6
    )

    fun getAvatarColorForName(name: String): Int {
        val hash = kotlin.math.abs(name.hashCode())
        return avatarColors[hash % avatarColors.size]
    }

    private fun getCallLogStats(context: Context): Pair<Map<String, Int>, Map<String, Long>> {
        val counts = mutableMapOf<String, Int>()
        val lastDates = mutableMapOf<String, Long>()

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val numberIndex = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIndex = it.getColumnIndex(CallLog.Calls.DATE)

                while (it.moveToNext()) {
                    val rawNum = if (numberIndex != -1) it.getString(numberIndex) ?: "" else ""
                    val date = if (dateIndex != -1) it.getLong(dateIndex) else 0L
                    val norm = normalizePhoneNumber(rawNum)
                    if (norm.isNotBlank()) {
                        counts[norm] = (counts[norm] ?: 0) + 1
                        if (!lastDates.containsKey(norm)) {
                            lastDates[norm] = date
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Call log permission not granted, skipping call log stats", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying call logs", e)
        }

        return Pair(counts, lastDates)
    }

    fun getAllContacts(context: Context): List<ElderContact> {
        val contactsList = mutableListOf<ElderContact>()
        val seenNumbers = mutableSetOf<String>()

        val (callCounts, lastCallDates) = getCallLogStats(context)
        val blockedList = BlockedNumberManager.getBlockedNumbers(context)

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val starredIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)

                while (it.moveToNext()) {
                    val id = if (idIndex != -1) it.getLong(idIndex) else 0L
                    val name = if (nameIndex != -1) it.getString(nameIndex) ?: "" else ""
                    val rawNumber = if (numberIndex != -1) it.getString(numberIndex) ?: "" else ""
                    val photoUri = if (photoIndex != -1) it.getString(photoIndex) else null
                    val isStarred = if (starredIndex != -1) it.getInt(starredIndex) == 1 else false

                    // Ignore USSD codes like *121#, *137#, etc. and ignore blocked numbers
                    val isUssd = rawNumber.startsWith("*") || rawNumber.endsWith("#")
                    val isBlocked = BlockedNumberManager.isBlocked(rawNumber, blockedList)
                    val normalizedNumber = normalizePhoneNumber(rawNumber)

                    if (!isUssd && !isBlocked && normalizedNumber.isNotBlank() && !seenNumbers.contains(normalizedNumber)) {
                        seenNumbers.add(normalizedNumber)

                        // Suffix/prefix match in call log
                        val count = callCounts[normalizedNumber]
                            ?: callCounts[normalizedNumber.takeLast(9)]
                            ?: 0
                        val lastDate = lastCallDates[normalizedNumber]
                            ?: lastCallDates[normalizedNumber.takeLast(9)]
                            ?: 0L

                        val customFile = getCustomPhotoFile(context, id)
                        val customFileByNum = getCustomPhotoFileByNumber(context, normalizedNumber)
                        val (resolvedPhotoUri, photoTime) = when {
                            customFile.exists() -> Pair(Uri.fromFile(customFile).toString(), customFile.lastModified())
                            customFileByNum.exists() -> Pair(Uri.fromFile(customFileByNum).toString(), customFileByNum.lastModified())
                            !photoUri.isNullOrBlank() -> Pair(photoUri, 0L)
                            else -> Pair(null, 0L)
                        }

                        contactsList.add(
                            ElderContact(
                                id = id,
                                name = name.ifBlank { rawNumber },
                                phoneNumber = rawNumber,
                                photoUri = resolvedPhotoUri,
                                isFavorite = isStarred,
                                callCount = count,
                                lastCallDate = lastDate,
                                photoLastModified = photoTime
                            )
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Contacts permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts", e)
        }

        // Sort contacts:
        // 1. Starred/Favorites first
        // 2. Frequently called (highest callCount)
        // 3. Recently called (highest lastCallDate)
        // 4. Demote system carrier contacts starting with "." or punctuation
        // 5. Alphabetical by display name
        contactsList.sortWith(
            compareByDescending<ElderContact> { it.isFavorite }
                .thenByDescending { it.callCount }
                .thenByDescending { it.lastCallDate }
                .thenBy { it.name.startsWith(".") || it.name.startsWith("*") }
                .thenBy { it.name.lowercase() }
        )

        // Put the most recent incoming caller first in the list for quick access/management
        try {
            val recentIncoming = RecentIncomingCallManager.getLatestIncomingCall(context)
            if (recentIncoming != null) {
                val recentRawNumber = recentIncoming.first
                val recentCleaned = BlockedNumberManager.normalizeNumber(recentRawNumber)

                if (recentCleaned.isNotBlank() && !BlockedNumberManager.isBlocked(recentRawNumber, blockedList)) {
                    val matchIndex = contactsList.indexOfFirst { c ->
                        val cCleaned = BlockedNumberManager.normalizeNumber(c.phoneNumber)
                        cCleaned == recentCleaned ||
                                (cCleaned.length >= 8 && recentCleaned.length >= 8 && (cCleaned.endsWith(recentCleaned) || recentCleaned.endsWith(cCleaned)))
                    }

                    if (matchIndex != -1) {
                        val contact = contactsList.removeAt(matchIndex)
                        contactsList.add(0, contact)
                        Log.d(TAG, "Promoted recent incoming caller to #1: ${contact.name}")
                    } else {
                        // Caller is not yet in contacts list: add as first contact item
                        val customFileByNum = getCustomPhotoFileByNumber(context, recentCleaned)
                        val resolvedPhotoUri = if (customFileByNum.exists()) Uri.fromFile(customFileByNum).toString() else null
                        val photoTime = if (customFileByNum.exists()) customFileByNum.lastModified() else 0L

                        val displayName = recentIncoming.second?.ifBlank { null } ?: recentRawNumber
                        val newContact = ElderContact(
                            id = -1L,
                            name = displayName,
                            phoneNumber = recentRawNumber,
                            photoUri = resolvedPhotoUri,
                            isFavorite = false,
                            callCount = 1,
                            lastCallDate = recentIncoming.third,
                            photoLastModified = photoTime
                        )
                        contactsList.add(0, newContact)
                        Log.d(TAG, "Added unsaved recent incoming caller to #1: $displayName ($recentRawNumber)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error promoting recent incoming caller", e)
        }

        return contactsList
    }

    fun findContactByNumber(context: Context, number: String): ElderContact? {
        if (number.isBlank()) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI
        )

        try {
            val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idIndex = it.getColumnIndex(ContactsContract.PhoneLookup._ID)
                    val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val photoIndex = it.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)

                    val id = if (idIndex != -1) it.getLong(idIndex) else 0L
                    val name = if (nameIndex != -1) it.getString(nameIndex) ?: "" else ""
                    val photoUri = if (photoIndex != -1) it.getString(photoIndex) else null

                    val norm = normalizePhoneNumber(number)
                    val customFile = getCustomPhotoFile(context, id)
                    val customFileByNum = getCustomPhotoFileByNumber(context, norm)
                    val (resolvedPhotoUri, photoTime) = when {
                        customFile.exists() -> Pair(Uri.fromFile(customFile).toString(), customFile.lastModified())
                        customFileByNum.exists() -> Pair(Uri.fromFile(customFileByNum).toString(), customFileByNum.lastModified())
                        !photoUri.isNullOrBlank() -> Pair(photoUri, 0L)
                        else -> Pair(null, 0L)
                    }

                    return ElderContact(
                        id = id,
                        name = name.ifBlank { number },
                        phoneNumber = number,
                        photoUri = resolvedPhotoUri,
                        photoLastModified = photoTime
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact by number: $number", e)
        }

        // Fallback: search in getAllContacts by normalized number match
        try {
            val norm = normalizePhoneNumber(number)
            if (norm.isNotBlank()) {
                val all = getAllContacts(context)
                val found = all.find { c ->
                    val cNorm = normalizePhoneNumber(c.phoneNumber)
                    cNorm == norm ||
                    (cNorm.length >= 8 && norm.length >= 8 && (cNorm.endsWith(norm) || norm.endsWith(cNorm)))
                }
                if (found != null) {
                    return found
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fallback contact lookup for: $number", e)
        }

        return null
    }

    fun normalizePhoneNumber(number: String): String {
        return number.replace("[^0-9+]".toRegex(), "")
    }

    fun getCustomPhotoFile(context: Context, contactId: Long): File {
        val dir = File(context.filesDir, "avatars").apply { if (!exists()) mkdirs() }
        return File(dir, "contact_${contactId}.jpg")
    }

    fun getCustomPhotoFileByNumber(context: Context, number: String): File {
        val dir = File(context.filesDir, "avatars").apply { if (!exists()) mkdirs() }
        val safeNum = normalizePhoneNumber(number).ifBlank { "unknown" }
        return File(dir, "num_${safeNum}.jpg")
    }

    fun saveContactPhoto(context: Context, contact: ElderContact, bitmap: Bitmap): Boolean {
        return try {
            val scaled = scaleBitmap(bitmap, 512)
            val now = System.currentTimeMillis()
            val fileById = getCustomPhotoFile(context, contact.id)
            FileOutputStream(fileById).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            fileById.setLastModified(now)

            val norm = normalizePhoneNumber(contact.phoneNumber)
            if (norm.isNotBlank()) {
                val fileByNum = getCustomPhotoFileByNumber(context, norm)
                FileOutputStream(fileByNum).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                fileByNum.setLastModified(now)
            }

            // Also attempt system ContactsContract update
            Thread {
                updateSystemContactPhoto(context, contact.id, scaled)
            }.start()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving contact photo", e)
            false
        }
    }

    fun deleteContactPhoto(context: Context, contact: ElderContact): Boolean {
        return try {
            val fileById = getCustomPhotoFile(context, contact.id)
            if (fileById.exists()) fileById.delete()
            val norm = normalizePhoneNumber(contact.phoneNumber)
            if (norm.isNotBlank()) {
                val fileByNum = getCustomPhotoFileByNumber(context, norm)
                if (fileByNum.exists()) fileByNum.delete()
            }
            Thread {
                deleteSystemContactPhoto(context, contact.id)
            }.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting contact photo", e)
            false
        }
    }

    private fun scaleBitmap(source: Bitmap, maxDim: Int): Bitmap {
        if (source.width <= maxDim && source.height <= maxDim) return source
        val ratio = source.width.toFloat() / source.height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (ratio > 1) {
            targetWidth = maxDim
            targetHeight = (maxDim / ratio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = maxDim
            targetWidth = (maxDim * ratio).toInt().coerceAtLeast(1)
        }
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun updateSystemContactPhoto(context: Context, contactId: Long, bitmap: Bitmap) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val photoBytes = stream.toByteArray()

            var rawContactId: Long = -1
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use {
                if (it.moveToFirst()) {
                    rawContactId = it.getLong(0)
                }
            }

            if (rawContactId == -1L) return

            val ops = ArrayList<ContentProviderOperation>()
            val dataCursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
                null
            )
            val hasExisting = dataCursor?.use { it.moveToFirst() } ?: false

            if (hasExisting) {
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                            arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        )
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                        .build()
                )
            } else {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                        .build()
                )
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d(TAG, "Successfully updated system contact photo for contactId=$contactId")
        } catch (e: Exception) {
            Log.w(TAG, "Could not update system contact photo: ${e.message}")
        }
    }

    private fun deleteSystemContactPhoto(context: Context, contactId: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            var rawContactId: Long = -1
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use {
                if (it.moveToFirst()) {
                    rawContactId = it.getLong(0)
                }
            }
            if (rawContactId != -1L) {
                context.contentResolver.delete(
                    ContactsContract.Data.CONTENT_URI,
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete system contact photo: ${e.message}")
        }
    }

    fun getContactStructuredName(context: Context, contactId: Long): Pair<String, String> {
        var givenName = ""
        var familyName = ""
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val givenIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)
                    val familyIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)
                    val displayIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)

                    if (givenIndex != -1) givenName = cursor.getString(givenIndex).orEmpty()
                    if (familyIndex != -1) familyName = cursor.getString(familyIndex).orEmpty()

                    // If both given and family are empty, split display name
                    if (givenName.isBlank() && familyName.isBlank() && displayIndex != -1) {
                        val display = cursor.getString(displayIndex).orEmpty().trim()
                        val space = display.indexOf(' ')
                        if (space != -1) {
                            givenName = display.substring(0, space).trim()
                            familyName = display.substring(space + 1).trim()
                        } else {
                            givenName = display
                        }
                    } else if (familyName.isBlank() && givenName.contains(' ')) {
                        val space = givenName.indexOf(' ')
                        familyName = givenName.substring(space + 1).trim()
                        givenName = givenName.substring(0, space).trim()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying structured name for contactId=$contactId", e)
        }
        return Pair(givenName, familyName)
    }

    fun updateContactName(context: Context, contactId: Long, firstName: String, lastName: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val rawContactIds = mutableListOf<Long>()
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                if (idIndex != -1) {
                    while (cursor.moveToNext()) {
                        rawContactIds.add(cursor.getLong(idIndex))
                    }
                }
            }

            if (rawContactIds.isEmpty()) return false

            val cleanFirst = firstName.trim()
            val cleanLast = lastName.trim()
            val fullName = listOf(cleanFirst, cleanLast).filter { it.isNotBlank() }.joinToString(" ")

            val ops = ArrayList<ContentProviderOperation>()

            for (rawId in rawContactIds) {
                val dataCursor = context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                    null
                )
                val hasExisting = dataCursor?.use { it.moveToFirst() } ?: false

                if (hasExisting) {
                    ops.add(
                        ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            )
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, fullName)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, cleanFirst)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, cleanLast)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, "")
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, "")
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, "")
                            .build()
                    )
                } else {
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, fullName)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, cleanFirst)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, cleanLast)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, "")
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.PREFIX, "")
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.SUFFIX, "")
                            .build()
                    )
                }
            }

            if (ops.isNotEmpty()) {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                Log.d(TAG, "Successfully updated contact name for contactId=$contactId: first='$cleanFirst', last='$cleanLast', full='$fullName'")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating contact name", e)
            false
        }
    }

    fun insertNewContact(context: Context, firstName: String, lastName: String, phoneNumber: String): Long {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return -1L
        }
        return try {
            val cleanFirst = firstName.trim()
            val cleanLast = lastName.trim()
            val fullName = listOf(cleanFirst, cleanLast).filter { it.isNotBlank() }.joinToString(" ")

            val ops = ArrayList<ContentProviderOperation>()
            val rawContactInsertIndex = ops.size

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, fullName)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, cleanFirst)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, cleanLast)
                    .build()
            )

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber.trim())
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )

            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawContactUri = results[0]?.uri
            val rawContactId = rawContactUri?.lastPathSegment?.toLongOrNull() ?: 1L
            Log.d(TAG, "Successfully inserted new contact: $fullName ($phoneNumber), rawContactId=$rawContactId")
            rawContactId
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting new contact", e)
            -1L
        }
    }
}
