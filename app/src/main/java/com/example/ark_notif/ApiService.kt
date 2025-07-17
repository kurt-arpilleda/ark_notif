package com.example.ark_notif

import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    @GET("V4/Others/Kurt/LatestVersionAPK/ArkNotif/output-metadata.json")
    fun getAppUpdateDetails(): Call<AppUpdateResponse>

    @GET("V4/Others/Kurt/RingAPI/kurt_fetchRing.php")
    fun getRingStatus(@Query("deviceId") deviceId: String): Call<NotificationStatusResponse>

    @GET("V4/Others/Kurt/RingAPI/kurt_fetchPaging.php")
    fun getPagingStatus(@Query("deviceId") deviceId: String): Call<NotificationStatusResponse>

    @GET("V4/Others/Kurt/RingAPI/kurt_fetchProfile.php")
    fun getProfile(@Query("deviceId") deviceId: String): Call<ProfileResponse>

    @GET("V4/Others/Kurt/RingAPI/kurt_fetchPagingPosts.php")
    fun getPagingPosts(@Query("deviceId") deviceId: String): Call<PagingPostsResponse>

    @FormUrlEncoded
    @POST("V4/Others/Kurt/RingAPI/kurt_updateLanguageFlag.php")
    fun updateLanguageFlag(
        @Field("idNumber") idNumber: String,
        @Field("languageFlag") languageFlag: String
    ): Call<BasicResponse>

    @FormUrlEncoded
    @POST("V4/Others/Kurt/RingAPI/kurt_updatePagingStatus.php")
    fun updatePagingStatus(
        @Field("pagingId") pagingId: Int,
        @Field("idNumber") idNumber: String,
        @Field("notifReply") notifReply: Int,
        @Field("userIndex") userIndex: Int // Add this parameter
    ): Call<BasicResponse>
}
