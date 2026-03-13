package com.nidoham.server.util

enum class MessageType(val value: String) {
    TEXT("text"), IMAGE("image");

    companion object {
        fun fromString(value: String): MessageType =
            entries.firstOrNull { it.value == value.lowercase() } ?: TEXT
    }
}