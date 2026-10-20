package com.drivercall.di

import android.content.Context
import androidx.room.Room
import com.drivercall.BuildConfig
import com.drivercall.data.local.AppDatabase
import com.drivercall.data.local.dao.CallHistoryDao
import com.drivercall.data.local.dao.FriendDao
import com.drivercall.data.local.dao.UserDao
import com.drivercall.data.network.NetworkMonitor
import com.drivercall.data.preferences.UserPreferences
import com.drivercall.data.remote.AuthApi
import com.drivercall.data.remote.AuthInterceptor
import com.drivercall.data.remote.ConfigApi
import com.drivercall.data.remote.FriendApi
import com.drivercall.data.remote.UserApi
import com.drivercall.data.repository.AuthRepositoryImpl
import com.drivercall.data.repository.CallHistoryRepositoryImpl
import com.drivercall.data.repository.FriendRepositoryImpl
import com.drivercall.data.repository.UserRepositoryImpl
import com.drivercall.data.security.TokenStorage
import com.drivercall.data.security.SessionAuthenticator
import com.drivercall.data.call.DefaultCallCoordinator
import com.drivercall.data.rtc.AndroidWebRtcEngine
import com.drivercall.data.rtc.WebRtcEngine
import com.drivercall.data.signaling.OkHttpSignalingClient
import com.drivercall.data.signaling.SignalingClient
import com.drivercall.domain.call.CallCoordinator
import com.drivercall.domain.repository.AuthRepository
import com.drivercall.domain.repository.CallHistoryRepository
import com.drivercall.domain.repository.FriendRepository
import com.drivercall.domain.repository.UserRepository
import com.drivercall.telecom.AndroidTelecomCallController
import com.drivercall.telecom.TelecomCallController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        sessionAuthenticator: SessionAuthenticator
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // Never log bodies in release: they may contain personal data.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .authenticator(sessionAuthenticator)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)

    @Provides
    @Singleton
    fun provideFriendApi(retrofit: Retrofit): FriendApi = retrofit.create(FriendApi::class.java)

    @Provides
    @Singleton
    fun provideConfigApi(retrofit: Retrofit): ConfigApi = retrofit.create(ConfigApi::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "drivercall.db")
            // Phase 1: destructive migration is acceptable pre-release.
            // Before Play release, add proper Migration objects.
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    @Provides
    fun provideFriendDao(db: AppDatabase): FriendDao = db.friendDao()

    @Provides
    fun provideCallHistoryDao(db: AppDatabase): CallHistoryDao = db.callHistoryDao()

    @Provides
    @Singleton
    fun provideTokenStorage(@ApplicationContext context: Context): TokenStorage =
        TokenStorage(context)

    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor =
        NetworkMonitor(context)

    @Provides
    @Singleton
    fun provideUserPreferences(@ApplicationContext context: Context): UserPreferences =
        UserPreferences(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCallCoordinator(impl: DefaultCallCoordinator): CallCoordinator

    @Binds
    @Singleton
    abstract fun bindWebRtcEngine(impl: AndroidWebRtcEngine): WebRtcEngine

    @Binds
    @Singleton
    abstract fun bindSignalingClient(impl: OkHttpSignalingClient): SignalingClient

    @Binds
    @Singleton
    abstract fun bindTelecomCallController(
        impl: AndroidTelecomCallController
    ): TelecomCallController

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindFriendRepository(impl: FriendRepositoryImpl): FriendRepository

    @Binds
    @Singleton
    abstract fun bindCallHistoryRepository(impl: CallHistoryRepositoryImpl): CallHistoryRepository
}
