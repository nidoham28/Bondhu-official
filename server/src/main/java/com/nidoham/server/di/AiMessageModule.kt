package com.nidoham.server.di

import com.nidoham.ai.api.zai.GenerativeAI
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
        ai: GenerativeAI,
        messageRepository: MessageRepository
    ): AiMessageManager {
        return AiMessageManager(
            ai = ai,
            messageRepository = messageRepository
        )
    }
}