package com.nidoham.server.di

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.nidoham.server.manager.FollowerManager
import com.nidoham.server.manager.FollowingManager
import com.nidoham.server.manager.PresenceManager
import com.nidoham.server.manager.UserManager
import com.nidoham.server.repository.participant.UserRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object UserModule {

    // FIX: UserManager takes (FirebaseFirestore, pageSize: Int), not FirebaseAuth.
    @Provides
    @Singleton
    fun provideUserManager(
        firestore: FirebaseFirestore
    ): UserManager = UserManager(firestore, pageSize = 20)

    // FIX: FollowerManager takes (FirebaseFirestore, pageSize: Int) — confirmed
    //      by its direct construction in FriendshipRepository.
    @Provides
    @Singleton
    fun provideFollowerManager(
        firestore: FirebaseFirestore
    ): FollowerManager = FollowerManager(firestore, pageSize = 20)

    // FIX: FollowingManager takes (FirebaseFirestore, pageSize: Int) — same pattern.
    @Provides
    @Singleton
    fun provideFollowingManager(
        firestore: FirebaseFirestore
    ): FollowingManager = FollowingManager(firestore, pageSize = 20)

    // FIX: PresenceManager takes Context, not FirebaseDatabase.
    @Provides
    @Singleton
    fun providePresenceManager(
        @ApplicationContext context: Context
    ): PresenceManager = PresenceManager(context)

    @Provides
    @Singleton
    fun provideUserRepository(
        userManager: UserManager,
        followerManager: FollowerManager,
        followingManager: FollowingManager,
        presenceManager: PresenceManager
    ): UserRepository = UserRepository(
        userManager,
        followerManager,
        followingManager,
        presenceManager
    )
}