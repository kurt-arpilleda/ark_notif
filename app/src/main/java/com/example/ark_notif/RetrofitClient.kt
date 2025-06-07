package com.example.ark_notif

import com.google.gson.GsonBuilder
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

object RetrofitClient {
    const val PRIMARY_URL = "https://192.168.254.163/"
    const val FALLBACK_URL = "https://126.209.7.246/"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 500L
    private const val CONNECTION_TIMEOUT_SECONDS = 2L
    private const val READ_WRITE_TIMEOUT_SECONDS = 10L

    private val currentBaseUrl = AtomicReference<String>(PRIMARY_URL)
    private val executor = Executors.newCachedThreadPool()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.NONE
    }

    // Create an unsafe OkHttpClient that trusts all certificates (use carefully!)
    private val unsafeOkHttpClient: OkHttpClient by lazy {
        try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                @Throws(CertificateException::class)
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                }

                @Throws(CertificateException::class)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return arrayOf()
                }
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true } // Bypass hostname verification
                .addInterceptor(loggingInterceptor)
                .addInterceptor(SmartUrlInterceptor())
                .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    // Retrofit instance with unsafe client
    val instance: ApiService by lazy {
        val gson = GsonBuilder()
            .setLenient()
            .create()
        createRetrofitInstance(currentBaseUrl.get(), gson)
    }

    private fun createRetrofitInstance(baseUrl: String, gson: com.google.gson.Gson): ApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(unsafeOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    fun updateWorkingUrl() {
        executor.submit {
            val completionService = ExecutorCompletionService<Pair<String, Boolean>>(executor)

            val urls = listOf(PRIMARY_URL, FALLBACK_URL)
            urls.forEach { url ->
                completionService.submit(Callable {
                    val reachable = isUrlReachable(url)
                    url to reachable
                })
            }

            repeat(urls.size) {
                val future = completionService.take()
                val (url, reachable) = future.get()
                if (reachable) {
                    currentBaseUrl.set(url)
                    return@submit
                }
            }
        }
    }

    private fun isUrlReachable(url: String): Boolean {
        return try {
            val host = url.substringAfter("://").substringBefore("/")
            val port = if (url.startsWith("https")) 443 else 80
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun onNetworkConnectivityChanged() {
        updateWorkingUrl()
    }

    class SmartUrlInterceptor : Interceptor {
        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val originalUrl = originalRequest.url

            val currentUrl = currentBaseUrl.get()
            val urls = if (currentUrl == PRIMARY_URL) {
                listOf(PRIMARY_URL, FALLBACK_URL)
            } else {
                listOf(FALLBACK_URL, PRIMARY_URL)
            }

            var lastException: IOException? = null

            for (baseUrl in urls) {
                for (attempt in 1..MAX_RETRIES) {
                    try {
                        val newUrl = originalUrl.newBuilder()
                            .scheme(baseUrl.substringBefore("://"))
                            .host(baseUrl.substringAfter("://").substringBefore("/"))
                            .build()

                        val newRequest = originalRequest.newBuilder()
                            .url(newUrl)
                            .build()

                        val response = chain.proceed(newRequest)
                        if (response.isSuccessful) {
                            currentBaseUrl.set(baseUrl)
                            return response
                        }
                        response.close()
                    } catch (e: IOException) {
                        lastException = e
                        if (attempt < MAX_RETRIES) {
                            Thread.sleep(RETRY_DELAY_MS)
                        }
                        if (attempt == MAX_RETRIES) {
                            handleConnectionFailure()
                        }
                    }
                }
            }

            throw lastException ?: IOException("Failed to connect to any URL after multiple attempts")
        }
    }

    fun handleConnectionFailure() {
        val current = currentBaseUrl.get()
        val alternate = if (current == PRIMARY_URL) FALLBACK_URL else PRIMARY_URL
        currentBaseUrl.set(alternate)
        updateWorkingUrl()
    }
}