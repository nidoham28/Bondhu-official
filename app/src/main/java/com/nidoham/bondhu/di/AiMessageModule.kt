package com.nidoham.bondhu.di

import com.nidoham.server.api.API
import com.nidoham.server.manager.AiMessageManager
import com.nidoham.server.repository.message.MessageRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object AiMessageModule {

    @Provides
    fun provideAiMessageManager(
        messageRepository: MessageRepository
    ): AiMessageManager {
        return AiMessageManager(
            apiKey = API.apiKey,
            messageRepository = messageRepository
        )
    }
}