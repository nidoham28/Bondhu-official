package com.nidoham.bondhu.presentation.component.profile

import com.nidoham.server.domain.participant.User

data class ProfileUiState(
    val user             : User?   = null,
    val isOwner          : Boolean = false,
    val isLoading        : Boolean = false,
    val error            : String? = null,
    val isFollowing      : Boolean = false,
    val isFollowLoading  : Boolean = false,
    val isMessageLoading : Boolean = false,
    val postsCount       : Long     = 0,
    val followersCount   : Long     = 0,
    val followingCount   : Long     = 0,
)