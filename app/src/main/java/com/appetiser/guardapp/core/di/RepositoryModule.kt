package com.appetiser.guardapp.core.di

import com.appetiser.guardapp.core.connectivity.ConnectivityNetworkMonitor
import com.appetiser.guardapp.core.connectivity.NetworkMonitor
import com.appetiser.guardapp.data.DataStoreSettingsCache
import com.appetiser.guardapp.data.DefaultAnnouncementRepository
import com.appetiser.guardapp.data.DefaultAttendanceRepository
import com.appetiser.guardapp.data.DefaultCheckpointRepository
import com.appetiser.guardapp.data.DefaultDutyRepository
import com.appetiser.guardapp.data.DefaultProfileRepository
import com.appetiser.guardapp.data.DefaultSettingsRepository
import com.appetiser.guardapp.data.SettingsCache
import com.appetiser.guardapp.domain.repository.AnnouncementRepository
import com.appetiser.guardapp.domain.repository.AttendanceRepository
import com.appetiser.guardapp.domain.repository.CheckpointRepository
import com.appetiser.guardapp.domain.repository.DutyRepository
import com.appetiser.guardapp.domain.repository.ProfileRepository
import com.appetiser.guardapp.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCheckpointRepository(impl: DefaultCheckpointRepository): CheckpointRepository

    @Binds
    @Singleton
    abstract fun bindDutyRepository(impl: DefaultDutyRepository): DutyRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DefaultSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindAttendanceRepository(impl: DefaultAttendanceRepository): AttendanceRepository

    @Binds
    @Singleton
    abstract fun bindSettingsCache(impl: DataStoreSettingsCache): SettingsCache

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: DefaultProfileRepository): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindAnnouncementRepository(impl: DefaultAnnouncementRepository): AnnouncementRepository

    @Binds
    @Singleton
    abstract fun bindNetworkMonitor(impl: ConnectivityNetworkMonitor): NetworkMonitor
}
