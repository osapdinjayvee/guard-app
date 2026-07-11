package com.minsu.guardapp.core.di

import android.content.Context
import com.minsu.guardapp.BuildConfig
import com.minsu.guardapp.core.network.mock.MockApiInterceptor
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
 *
 * With `USE_MOCK_API` off — the default — this contributes a pass-through, so a debug build talks
 * to the real backend at [BuildConfig.API_BASE_URL]. Turning it on serves canned responses from
 * assets, which is how the app is developed when no backend is running. The mock code lives only
 * in this source set, so a release build cannot serve fake data even by accident.
 */
@Module
@InstallIn(SingletonComponent::class)
object MockApiModule {

    @Provides
    @Singleton
    @IntoSet
    fun mockApiInterceptor(@ApplicationContext context: Context): Interceptor =
        if (BuildConfig.USE_MOCK_API) {
            MockApiInterceptor(
                loadAsset = { path ->
                    runCatching {
                        context.assets.open(path).bufferedReader().use { it.readText() }
                    }.getOrNull()
                },
            )
        } else {
            Interceptor { chain -> chain.proceed(chain.request()) }
        }
}
