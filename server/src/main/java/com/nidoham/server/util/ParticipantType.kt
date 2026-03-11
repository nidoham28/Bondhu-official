package com.nidoham.server.util

import java.util.Locale

enum class ParticipantType(val value: String) {
    PERSONAL("personal"), GROUP("group"), CHANNEL("channel");

    companion object {
        fun fromString(value: String): ParticipantType =
            entries.firstOrNull { it.value == value.lowercase(Locale.ROOT) } ?: PERSONAL
    }
}