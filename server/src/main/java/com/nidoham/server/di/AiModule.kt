package com.nidoham.server.di

import com.nidoham.ai.api.zai.GenerativeAI
import com.nidoham.server.api.API
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideGenerativeAI(): GenerativeAI {
        return GenerativeAI.create(
            apiKey = API.apiKey
        )
    }
}