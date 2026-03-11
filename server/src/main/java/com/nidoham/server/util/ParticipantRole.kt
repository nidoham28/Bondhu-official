package com.nidoham.server.util

import java.util.Locale

enum class ParticipantRole(val value: String) {
    OWNER("owner"), ADMIN("admin"), MEMBER("member");

    companion object {
        fun fromString(value: String): ParticipantRole =
            entries.firstOrNull { it.value == value.lowercase(Locale.ROOT) } ?: MEMBER
    }
}