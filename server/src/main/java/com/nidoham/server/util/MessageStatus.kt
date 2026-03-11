package com.nidoham.server.util

enum class MessageStatus {
    PENDING, SENDING, SENT, DELIVERED, READ, FAILED;

    companion object {
        fun fromString(value: String): MessageStatus =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}