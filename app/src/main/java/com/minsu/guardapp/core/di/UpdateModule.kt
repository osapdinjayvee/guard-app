package com.minsu.guardapp.core.di

import android.content.Context
import com.minsu.guardapp.BuildConfig
import com.minsu.guardapp.core.update.DataStoreUpdatePreferences
import com.minsu.guardapp.core.update.DefaultUpdateRepository
import com.minsu.guardapp.core.update.UpdatePreferences
import com.minsu.guardapp.core.update.UpdateRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The updater's own HTTP client. Not the API's — see [UpdateNetworkModule.updateClient]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateManifestUrl

/** The running build's versionCode, injected so tests can pretend to be an older one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InstalledVersionCode

/** Where downloaded APKs land. Must match the `updates` path declared in res/xml/file_paths.xml. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateCacheDir

@Module
@InstallIn(SingletonComponent::class)
object UpdateNetworkModule {

    /**
     * A second OkHttpClient, built from scratch rather than shared with the API.
     *
     * This is not duplication for its own sake. The singleton client in [NetworkModule] installs
     * [com.minsu.guardapp.core.network.AuthInterceptor], which attaches the guard's Sanctum
     * bearer token to every request that is not `/login`. The update manifest and the APK live on
     * a *different host* — pointing the API's client at them would hand that session token to
     * whatever server answers, which is a credential leak, not a style problem.
     *
     * The timeouts differ for the same practical reason: the API's 30-second read budget is
     * generous for JSON and hopeless for a multi-megabyte APK over campus wifi.
     */
    @Provides
    @Singleton
    @UpdateClient
    fun updateClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @UpdateManifestUrl
    fun updateManifestUrl(): String = BuildConfig.UPDATE_MANIFEST_URL

    @Provides
    @InstalledVersionCode
    fun installedVersionCode(): Int = BuildConfig.VERSION_CODE

    /**
     * Cache, not files: a downloaded APK is worth nothing once installed, and the system is
     * welcome to reclaim tens of megabytes the guard cannot clear themselves. The `updates`
     * segment is load-bearing — it is what res/xml/file_paths.xml exposes to the installer.
     */
    @Provides
    @UpdateCacheDir
    fun updateCacheDir(@ApplicationContext context: Context): File =
        File(context.cacheDir, "updates")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    abstract fun updateRepository(impl: DefaultUpdateRepository): UpdateRepository

    @Binds
    abstract fun updatePreferences(impl: DataStoreUpdatePreferences): UpdatePreferences
}
