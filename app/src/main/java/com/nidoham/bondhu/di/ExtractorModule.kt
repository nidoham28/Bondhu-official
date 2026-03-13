package com.nidoham.bondhu.di

import com.nidoham.extractor.stream.PagingConfig
import com.nidoham.extractor.stream.StreamExtractor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides extractor-layer dependencies for the application graph.
 *
 * [StreamExtractor] is bound as a singleton so the same instance (and its
 * internal retry state) is shared across all injection sites — primarily
 * [com.nidoham.bondhu.PlayerService].
 */
@Module
@InstallIn(SingletonComponent::class)
object ExtractorModule {

    @Provides
    @Singleton
    fun provideStreamExtractor(): StreamExtractor = StreamExtractor(
        config = PagingConfig(
            pageSize        = 10,
            maxRetries      = 3,
            retryDelayMillis = 1_000L,
        )
    )
}