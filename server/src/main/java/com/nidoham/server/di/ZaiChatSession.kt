package com.nidoham.server.di

import com.nidoham.ai.api.zai.ZaiChatSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Module for providing AI related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object ZaiModule {

    /**
     * Provides a singleton instance of [ZaiChatSession].
     *
     * Uses the default configuration. If you need dynamic configuration
     * (e.g. user-selected model), you would pass arguments here or
     * use a Factory pattern.
     */
    @Provides
    @Singleton
    fun provideZaiChatSession(): ZaiChatSession {
        return ZaiChatSession.create()
    }
}