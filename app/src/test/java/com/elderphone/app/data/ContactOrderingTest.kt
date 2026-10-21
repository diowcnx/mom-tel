package com.elderphone.app.data

import com.elderphone.app.model.ElderContact
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactOrderingTest {
    @Test
    fun order_keepsTheCompleteCallSequenceNewestFirstAcrossEveryPage() {
        val contacts = listOf(
            contact("ลำดับ 7", lastCallDate = 400L),
            contact("ลำดับ 2", lastCallDate = 900L),
            contact("ลำดับ 10", lastCallDate = 100L, callCount = 20, isFavorite = true),
            contact("ลำดับ 4", lastCallDate = 700L),
            contact("ลำดับ 1", lastCallDate = 1_000L),
            contact("ลำดับ 9", lastCallDate = 200L),
            contact("ลำดับ 5", lastCallDate = 600L),
            contact("ลำดับ 3", lastCallDate = 800L),
            contact("ลำดับ 8", lastCallDate = 300L),
            contact("ลำดับ 6", lastCallDate = 500L)
        )

        val result = ContactOrdering.order(
            contacts,
            latestNumber = null
        )

        assertEquals(
            (1..10).map { "ลำดับ $it" },
            result.map { it.name }
        )
    }

    @Test
    fun order_treatsThaiLocalAndInternationalNumbersAsTheSameCaller() {
        val newest = contact("สายล่าสุด", lastCallDate = 300L, number = "+66 98 491 4245")
        val noHistory = contact("ยังไม่เคยโทร", lastCallDate = 0L)

        val result = ContactOrdering.order(
            listOf(noHistory, newest),
            latestNumber = "0984914245"
        )

        assertEquals(listOf("สายล่าสุด", "ยังไม่เคยโทร"), result.map { it.name })
    }

    private fun contact(
        name: String,
        lastCallDate: Long,
        callCount: Int = 1,
        isFavorite: Boolean = false,
        number: String = "0984914245"
    ) = ElderContact(
        id = name.hashCode().toLong(),
        name = name,
        phoneNumber = number,
        isFavorite = isFavorite,
        callCount = callCount,
        lastCallDate = lastCallDate
    )
}
