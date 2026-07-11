package com.minsu.guardapp.core.di

import com.minsu.guardapp.BuildConfig
import com.minsu.guardapp.core.network.AuthInterceptor
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.OkHttpCustomizer
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun moshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun okHttpClient(
        authInterceptor: AuthInterceptor,
        // Empty in release. The debug source set contributes the mock API interceptor.
        interceptors: Set<@JvmSuppressWildcards Interceptor>,
        // Empty in release. The debug source set contributes the local backend's DNS and cert.
        customizers: Set<@JvmSuppressWildcards OkHttpCustomizer>,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // BODY would print the Sanctum token on every authenticated call.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }

        return OkHttpClient.Builder()
            // Auth first: the mock interceptor short-circuits the chain, so a later
            // AuthInterceptor would never see a mocked request and the header would go
            // untested until the real backend arrived.
            .addInterceptor(authInterceptor)
            .apply { interceptors.forEach(::addInterceptor) }
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Selfie uploads are large and the network is assumed to be poor.
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply { customizers.forEach { it.customize(this) } }
            .build()
    }

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides
    @Singleton
    fun guardApi(retrofit: Retrofit): GuardApi = retrofit.create(GuardApi::class.java)
}
