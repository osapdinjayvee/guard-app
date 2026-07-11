package com.minsu.guardapp.core.di

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.common.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
