package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp

data class Following(
    val uid: String = "",
    val followedAt: Timestamp? = null
)