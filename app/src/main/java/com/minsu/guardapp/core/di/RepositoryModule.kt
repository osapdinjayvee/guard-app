package com.minsu.guardapp.core.di

import com.minsu.guardapp.core.connectivity.ConnectivityNetworkMonitor
import com.minsu.guardapp.core.connectivity.NetworkMonitor
import com.minsu.guardapp.core.onboarding.DataStoreOnboardingPreferences
import com.minsu.guardapp.core.onboarding.OnboardingPreferences
import com.minsu.guardapp.core.location.FusedLocationProvider
import com.minsu.guardapp.core.security.AppLock
import com.minsu.guardapp.core.security.AppLockManager
import com.minsu.guardapp.core.security.DataStoreLockPreferences
import com.minsu.guardapp.core.security.LockPreferences
import com.minsu.guardapp.core.location.LocationProvider
import com.minsu.guardapp.core.sync.AttendanceUploader
import com.minsu.guardapp.core.sync.DefaultAttendanceUploader
import com.minsu.guardapp.core.sync.SyncScheduler
import com.minsu.guardapp.core.sync.WorkManagerSyncScheduler
import com.minsu.guardapp.data.DataStoreSettingsCache
import com.minsu.guardapp.data.DefaultAuthRepository
import com.minsu.guardapp.data.DefaultAnnouncementRepository
import com.minsu.guardapp.core.security.DataStoreRosterPreferences
import com.minsu.guardapp.core.security.RosterPreferences
import com.minsu.guardapp.data.DefaultAttendanceRepository
import com.minsu.guardapp.data.DefaultEvaluationRepository
import com.minsu.guardapp.data.DefaultScheduleRepository
import com.minsu.guardapp.data.DefaultCheckpointRepository
import com.minsu.guardapp.data.DefaultDutyRepository
import com.minsu.guardapp.data.DefaultProfileRepository
import com.minsu.guardapp.data.DefaultSettingsRepository
import com.minsu.guardapp.data.SettingsCache
import com.minsu.guardapp.domain.repository.AnnouncementRepository
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.AuthRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.EvaluationRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
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

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: FusedLocationProvider): LocationProvider

    @Binds
    @Singleton
    abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds
    abstract fun bindAttendanceUploader(impl: DefaultAttendanceUploader): AttendanceUploader

    @Binds
    @Singleton
    abstract fun bindAppLock(impl: AppLockManager): AppLock

    @Binds
    @Singleton
    abstract fun bindLockPreferences(impl: DataStoreLockPreferences): LockPreferences

    @Binds
    @Singleton
    abstract fun bindOnboardingPreferences(impl: DataStoreOnboardingPreferences): OnboardingPreferences

    @Binds
    @Singleton
    abstract fun bindScheduleRepository(impl: DefaultScheduleRepository): ScheduleRepository

    @Binds
    @Singleton
    abstract fun bindEvaluationRepository(impl: DefaultEvaluationRepository): EvaluationRepository

    @Binds
    @Singleton
    abstract fun bindRosterPreferences(impl: DataStoreRosterPreferences): RosterPreferences
}
