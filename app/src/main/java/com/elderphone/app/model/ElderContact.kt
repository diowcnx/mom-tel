package com.elderphone.app.model

data class ElderContact(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null,
    val isFavorite: Boolean = false,
    val callCount: Int = 0,
    val lastCallDate: Long = 0L
) {
    val initial: String
        get() = if (name.isNotBlank()) name.trim().take(1).uppercase() else "?"
}
