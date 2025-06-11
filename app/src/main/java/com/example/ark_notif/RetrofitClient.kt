package com.example.ark_notif

import com.google.gson.GsonBuilder
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

object RetrofitClient {
    const val PRIMARY_URL = "http://192.168.254.163/"
    const val FALLBACK_URL = "http://126.209.7.246/"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 500L
    private const val CONNECTION_TIMEOUT_SECONDS = 2L
    private const val READ_WRITE_TIMEOUT_SECONDS = 10L

    // Track which URL was most recently successful
    private val currentBaseUrl = AtomicReference<String>(PRIMARY_URL)

    // Thread pool for parallel URL checks
    private val executor = Executors.newCachedThreadPool()

    // Configuring the logging interceptor
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    // Create an OkHttpClient with optimized settings
    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(SmartUrlInterceptor()) // Our improved interceptor
        .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    // Retrofit instance with optimized settings
    val instance: ApiService by lazy {
        val gson = GsonBuilder()
            .setLenient()
            .create()
        createRetrofitInstance(currentBaseUrl.get(), gson)
    }

    // Create Retrofit instance based on baseUrl
    private fun createRetrofitInstance(baseUrl: String, gson: com.google.gson.Gson): ApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    // This method will check both URLs in parallel and pick the fastest reachable
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
                val future = completionService.take() // blocks until a task completes
                val (url, reachable) = future.get()
                if (reachable) {
                    currentBaseUrl.set(url)
                    return@submit
                }
            }
            // If neither reachable, do nothing or keep old
        }
    }

    // Check if a URL is reachable via TCP connection
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

    // Call this method on network change or app startup to update URL
    fun onNetworkConnectivityChanged() {
        updateWorkingUrl()
    }

    // Smart interceptor that tries the preferred URL first, then fallback
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

    // Switch to alternate URL on failure and re-check
    fun handleConnectionFailure() {
        val current = currentBaseUrl.get()
        val alternate = if (current == PRIMARY_URL) FALLBACK_URL else PRIMARY_URL
        currentBaseUrl.set(alternate)
        updateWorkingUrl()
    }
}
