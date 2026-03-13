package com.nidoham.server.util

enum class AuthProvider(val value: String) {
    PASSWORD("password"),
    GOOGLE("google"),
    PHONE("phone"),
    ANONYMOUS("anonymous");

    companion object {
        fun fromString(value: String): AuthProvider =
            entries.firstOrNull { it.value == value.lowercase() } ?: PASSWORD
    }
}