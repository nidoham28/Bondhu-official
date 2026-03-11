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

    @Provides
    @Singleton
    fun provideConversationManager(
        firestore: FirebaseFirestore
    ): ConversationManager = ConversationManager(firestore)

    @Provides
    @Singleton
    fun provideMessageManager(
        firestore: FirebaseFirestore
    ): MessageManager = MessageManager(firestore)

    // FIX: ParticipantManager was never provided. The repository was silently
    //      constructing its own unmanaged instance via the Kotlin default parameter,
    //      bypassing the Hilt graph and the shared FirebaseFirestore singleton.
    @Provides
    @Singleton
    fun provideParticipantManager(
        firestore: FirebaseFirestore
    ): ParticipantManager = ParticipantManager(firestore)

    // FIX: TypingManager was never provided, for the same reason as above.
    @Provides
    @Singleton
    fun provideTypingManager(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ): TypingManager = TypingManager(firestore, auth)

    // FIX: Only two of four required managers were passed, leaving ParticipantManager
    //      and TypingManager to be constructed outside the DI graph. All four are now
    //      injected explicitly so every dependency is a true Hilt-managed singleton.
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