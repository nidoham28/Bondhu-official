package com.nidoham.server.util

enum class MessageStatus(val value: String) {
    PENDING("pending"),
    SENDING("sending"),
    SENT("sent"),
    DELIVERED("delivered"),
    READ("read"),
    FAILED("failed");

    companion object {
        fun fromString(value: String): MessageStatus =
            entries.firstOrNull { it.value == value.lowercase() } ?: PENDING
    }
}