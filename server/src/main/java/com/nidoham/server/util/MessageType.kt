package com.nidoham.server.util

enum class MessageType {
    TEXT, IMAGE;

    companion object {
        fun fromString(value: String): MessageType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: TEXT
    }
}