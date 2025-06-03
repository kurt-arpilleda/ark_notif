package com.example.ark_notif

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {
    @GET("V4/Others/Kurt/RingAPI/kurt_fetchRing.php")
    fun getRingStatus(): Call<RingStatusResponse>
}