package com.elderphone.app.data

import com.elderphone.app.model.ElderContact

/** Pure ordering policy for the home-screen contact list. */
object ContactOrdering {
    fun order(contacts: List<ElderContact>, latestNumber: String?): List<ElderContact> {
        val result = contacts.sortedWith(
            // Every contact that appears in call history is ordered strictly by recency. This
            // makes the old first item move to second when a newer call arrives instead of being
            // replaced by a one-off promotion.
            compareByDescending<ElderContact> { it.lastCallDate > 0L }
                .thenByDescending { it.lastCallDate }
                // Favorites/frequency only decide the order of contacts with no call history.
                .thenByDescending { it.isFavorite }
                .thenByDescending { it.callCount }
                .thenBy { it.name.startsWith(".") || it.name.startsWith("*") }
                .thenBy { it.name.lowercase() }
        ).toMutableList()

        if (!latestNumber.isNullOrBlank()) {
            val latestIndex = result.indexOfFirst {
                PhoneNumberUtils.areEquivalent(it.phoneNumber, latestNumber)
            }
            if (latestIndex > 0) {
                result.add(0, result.removeAt(latestIndex))
            }
        }

        return result
    }
}
