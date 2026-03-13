package com.nidoham.server.di

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.nidoham.server.manager.FriendshipManager
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

    @Provides
    @Singleton
    fun provideUserManager(
        firestore: FirebaseFirestore
    ): UserManager = UserManager(firestore)

    @Provides
    @Singleton
    fun provideFriendshipManager(
        firestore: FirebaseFirestore
    ): FriendshipManager = FriendshipManager(firestore)

    @Provides
    @Singleton
    fun providePresenceManager(
        @ApplicationContext context: Context
    ): PresenceManager = PresenceManager(context)

    @Provides
    @Singleton
    fun provideUserRepository(
        userManager: UserManager,
        friendshipManager: FriendshipManager,
        presenceManager: PresenceManager
    ): UserRepository = UserRepository(userManager, friendshipManager, presenceManager)
}