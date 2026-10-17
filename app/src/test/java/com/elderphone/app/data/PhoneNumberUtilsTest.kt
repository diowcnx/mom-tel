package com.elderphone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberUtilsTest {
    @Test
    fun normalizeForComparison_removesFormatting() {
        assertEquals("0984914245", PhoneNumberUtils.normalizeForComparison("098-491-4245"))
    }

    @Test
    fun normalizeForComparison_convertsThaiCountryCode() {
        assertEquals("0984914245", PhoneNumberUtils.normalizeForComparison("+66 98 491 4245"))
        assertEquals("0984914245", PhoneNumberUtils.normalizeForComparison("66 98 491 4245"))
    }

    @Test
    fun areEquivalent_matchesLocalAndInternationalFormats() {
        assertTrue(PhoneNumberUtils.areEquivalent("0984914245", "+66 98 491 4245"))
    }

    @Test
    fun areEquivalent_rejectsBlankAndDifferentNumbers() {
        assertFalse(PhoneNumberUtils.areEquivalent("", "0984914245"))
        assertFalse(PhoneNumberUtils.areEquivalent("0984914245", "0812345678"))
    }
}
