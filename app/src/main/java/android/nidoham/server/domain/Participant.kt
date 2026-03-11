package android.nidoham.server.domain

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Locale

data class Participant(
    @field:DocumentId
    var id: String = "",

    @get:PropertyName("uid") @set:PropertyName("uid")
    var uid: String = "",

    @get:PropertyName("role") @set:PropertyName("role")
    var role: String = ParticipantRole.MEMBER.value,

    @get:PropertyName("type") @set:PropertyName("type")
    var type: String = ParticipantType.PERSONAL.value,

    @get:PropertyName("joined_at") @set:PropertyName("joined_at")
    @field:ServerTimestamp                          // ← field-level, not get-level
    var joinedAt: Timestamp? = null
) {
    val participantRole: ParticipantRole
        get() = ParticipantRole.fromString(role)

    val participantType: ParticipantType            // ← was missing entirely
        get() = ParticipantType.fromString(type)

    fun hasAdminPrivileges(): Boolean =
        participantRole == ParticipantRole.OWNER || participantRole == ParticipantRole.ADMIN
}

enum class ParticipantRole(val value: String) {
    OWNER("owner"), ADMIN("admin"), MEMBER("member");

    companion object {
        fun fromString(value: String): ParticipantRole =
            entries.firstOrNull { it.value == value.lowercase(Locale.ROOT) } ?: MEMBER
    }
}

enum class ParticipantType(val value: String) {
    PERSONAL("personal"), GROUP("group"), CHANNEL("channel");

    companion object {
        fun fromString(value: String): ParticipantType =
            entries.firstOrNull { it.value == value.lowercase(Locale.ROOT) } ?: PERSONAL
    }
}