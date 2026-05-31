package com.theycallmeboxy.caulker.di

import android.content.Context
import androidx.room.Room
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.theycallmeboxy.caulker.data.api.RommApiService
import com.theycallmeboxy.caulker.data.api.TlsConfig
import com.theycallmeboxy.caulker.data.api.interceptor.AuthInterceptor
import com.theycallmeboxy.caulker.data.api.interceptor.BaseUrlInterceptor
import com.theycallmeboxy.caulker.data.db.CaulkerDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttp(
        baseUrlInterceptor: BaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
        tlsConfig: TlsConfig
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)

        // Trust manager that delegates to the system default unless the user has
        // toggled "trust self-signed certificates" on. Reads the flag on every
        // handshake so toggling at runtime takes effect for the next connection.
        val defaultTrustManager = systemDefaultTrustManager()
        val dynamicTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
                if (!tlsConfig.isInsecure()) defaultTrustManager.checkClientTrusted(chain, authType)
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                if (!tlsConfig.isInsecure()) defaultTrustManager.checkServerTrusted(chain, authType)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                defaultTrustManager.acceptedIssuers
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(dynamicTrustManager), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, dynamicTrustManager)
        val defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        builder.hostnameVerifier { hostname, session ->
            tlsConfig.isInsecure() || defaultHostnameVerifier.verify(hostname, session)
        }

        return builder.build()
    }

    private fun systemDefaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): RommApiService =
        retrofit.create(RommApiService::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CaulkerDatabase =
        Room.databaseBuilder(context, CaulkerDatabase::class.java, "caulker.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun providePlatformDao(db: CaulkerDatabase) = db.platformDao()
    @Provides fun provideRomDao(db: CaulkerDatabase) = db.romDao()
    @Provides fun provideCollectionDao(db: CaulkerDatabase) = db.collectionDao()
    @Provides fun provideSaveDao(db: CaulkerDatabase) = db.saveDao()
}
