package com.minsu.guardapp.core.di

import com.minsu.guardapp.core.network.OkHttpCustomizer
import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor

/**
 * Declares an (possibly empty) set of application interceptors.
 *
 * Release builds contribute nothing. The debug source set contributes the mock API
 * interceptor, so no mock code exists in a release APK at all — the switch to the real
 * backend is the deletion of one `@IntoSet` provider, not a runtime flag.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InterceptorModule {

    @Multibinds
    abstract fun interceptors(): Set<Interceptor>

    /** Empty in release. Debug contributes the local backend's DNS and certificate. */
    @Multibinds
    abstract fun okHttpCustomizers(): Set<OkHttpCustomizer>
}
