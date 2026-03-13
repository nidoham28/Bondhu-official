package com.nidoham.server.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.nidoham.server.manager.ConversationManager
import com.nidoham.server.manager.MessageManager
import com.nidoham.server.manager.ParticipantManager
import com.nidoham.server.manager.TypingManager
import com.nidoham.server.repository.message.MessageRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MessageModule {

    // ─────────────────────────────────────────────────────────────────────────
    // Managers
    // ─────────────────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideParticipantManager(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ): ParticipantManager = ParticipantManager(firestore, auth)

    @Provides
    @Singleton
    fun provideMessageManager(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ): MessageManager = MessageManager(firestore, auth)

    @Provides
    @Singleton
    fun provideConversationManager(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        participantManager: ParticipantManager,
        messageManager: MessageManager
    ): ConversationManager = ConversationManager(
        firestore,
        auth,
        participantManager,
        messageManager
    )

    @Provides
    @Singleton
    fun provideTypingManager(
        database: FirebaseDatabase,
        auth: FirebaseAuth
    ): TypingManager = TypingManager(database, auth)

    // ─────────────────────────────────────────────────────────────────────────
    // Repository
    // ─────────────────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideMessageRepository(
        conversationManager: ConversationManager,
        messageManager: MessageManager,
        participantManager: ParticipantManager,
        typingManager: TypingManager
    ): MessageRepository = MessageRepository(
        conversationManager,
        messageManager,
        participantManager,
        typingManager
    )
}