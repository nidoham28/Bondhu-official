package com.nidoham.server.domain.participant

import com.google.firebase.Timestamp

data class Follower(
    val uid: String = "",
    val followedAt: Timestamp? = null,
    val isFollowedByMe: Boolean = false
)
