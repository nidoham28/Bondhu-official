package com.nidoham.bondhu.di

import com.google.firebase.firestore.FirebaseFirestore
import com.nidoham.bondhu.data.repository.message.MessageRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.nidoham.server.data.repository.ConversationManager
import org.nidoham.server.data.repository.MessageManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MessageModule {

    @Provides
    @Singleton
    fun provideMessageManager(firestore: FirebaseFirestore): MessageManager =
        MessageManager(firestore)

    @Provides
    @Singleton
    fun provideConversationManager(firestore: FirebaseFirestore): ConversationManager =
        ConversationManager(firestore)

    @Provides
    @Singleton
    fun provideMessageRepository(
        firestore: FirebaseFirestore,
        messageManager: MessageManager,
        conversationManager: ConversationManager
    ): MessageRepository = MessageRepository(firestore, messageManager, conversationManager)
}