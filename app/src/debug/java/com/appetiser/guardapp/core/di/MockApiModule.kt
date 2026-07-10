package com.appetiser.guardapp.core.di

import android.content.Context
import com.appetiser.guardapp.core.network.mock.MockApiInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.Interceptor
import javax.inject.Singleton

/**
 * Debug source set only. Contributes the mock API to the interceptor set declared by
 * [InterceptorModule]; release builds contribute nothing and reach the real network.
 */
@Module
@InstallIn(SingletonComponent::class)
object MockApiModule {

    @Provides
    @Singleton
    @IntoSet
    fun mockApiInterceptor(@ApplicationContext context: Context): Interceptor =
        MockApiInterceptor(
            loadAsset = { path ->
                runCatching {
                    context.assets.open(path).bufferedReader().use { it.readText() }
                }.getOrNull()
            },
        )
}
