package com.example.network

import com.example.model.PoolCouponDto
import com.example.model.PoolCouponUpload
import com.example.model.PoolColumnRank
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class PoolAuthCredentials(val email: String, val password: String)

@JsonClass(generateAdapter = true)
data class PoolSignUpRequest(
    val email: String,
    val password: String,
    val data: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class PoolRefreshRequest(@Json(name = "refresh_token") val refreshToken: String)

@JsonClass(generateAdapter = true)
data class PoolPkceExchangeRequest(
    @Json(name = "auth_code") val authCode: String,
    @Json(name = "code_verifier") val codeVerifier: String
)

@JsonClass(generateAdapter = true)
data class PoolRecoverRequest(val email: String)

@JsonClass(generateAdapter = true)
data class PoolUpdateUserRequest(val data: Map<String, String>)

@JsonClass(generateAdapter = true)
data class PoolAppMetadata(
    val provider: String? = null,
    val providers: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class PoolUserMetadata(
    val username: String? = null,
    @Json(name = "full_name") val fullName: String? = null,
    val name: String? = null,
    @Json(name = "favorite_team") val favoriteTeam: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class PoolAuthUser(
    val id: String = "",
    val email: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "email_confirmed_at") val emailConfirmedAt: String? = null,
    @Json(name = "app_metadata") val appMetadata: PoolAppMetadata? = null,
    @Json(name = "user_metadata") val userMetadata: PoolUserMetadata? = null
)

@JsonClass(generateAdapter = true)
data class PoolAuthResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    val user: PoolAuthUser? = null
)

@JsonClass(generateAdapter = true)
data class PoolRankRequest(
    @Json(name = "p_week_key") val weekKey: String,
    @Json(name = "p_match_labels") val matchLabels: List<String>,
    @Json(name = "p_final") val finalCodes: String,
    @Json(name = "p_live") val liveCodes: String,
    @Json(name = "p_limit") val limit: Int = 40
)

@JsonClass(generateAdapter = true)
data class PoolRankResponse(
    @Json(name = "coupon_id") val couponId: String,
    @Json(name = "display_name") val displayName: String,
    @Json(name = "column_number") val columnNumber: Long,
    val predictions: String,
    @Json(name = "finished_correct") val finishedCorrect: Int,
    @Json(name = "finished_wrong") val finishedWrong: Int,
    @Json(name = "live_correct") val liveCorrect: Int,
    @Json(name = "live_wrong") val liveWrong: Int,
    @Json(name = "maximum_possible") val maximumPossible: Int,
    @Json(name = "available_results") val availableResults: Int
) {
    fun asDomain(): PoolColumnRank = PoolColumnRank(
        couponId, displayName, columnNumber.toInt(), predictions,
        finishedCorrect, finishedWrong, liveCorrect, liveWrong,
        maximumPossible, availableResults
    )
}

/** Supabase Auth (Email, Google OAuth, Apple ID OAuth) and PostgREST; only the publishable key is included in the app. */
interface PoolApiService {
    @POST("auth/v1/signup")
    suspend fun signUp(
        @Header("apikey") publishableKey: String,
        @Body body: PoolSignUpRequest
    ): Response<PoolAuthResponse>

    @POST("auth/v1/token")
    suspend fun signIn(
        @Header("apikey") publishableKey: String,
        @Query("grant_type") grantType: String = "password",
        @Body body: PoolAuthCredentials
    ): Response<PoolAuthResponse>

    @POST("auth/v1/token")
    suspend fun exchangePkceCode(
        @Header("apikey") publishableKey: String,
        @Query("grant_type") grantType: String = "pkce",
        @Body body: PoolPkceExchangeRequest
    ): Response<PoolAuthResponse>

    @POST("auth/v1/token")
    suspend fun refresh(
        @Header("apikey") publishableKey: String,
        @Query("grant_type") grantType: String = "refresh_token",
        @Body body: PoolRefreshRequest
    ): Response<PoolAuthResponse>

    @GET("auth/v1/user")
    suspend fun getCurrentUser(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") bearer: String
    ): Response<PoolAuthUser>

    @PUT("auth/v1/user")
    suspend fun updateCurrentUser(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") bearer: String,
        @Body body: PoolUpdateUserRequest
    ): Response<PoolAuthUser>

    @POST("auth/v1/recover")
    suspend fun recoverPassword(
        @Header("apikey") publishableKey: String,
        @Body body: PoolRecoverRequest
    ): Response<Unit>

    @POST("auth/v1/logout")
    suspend fun logout(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") bearer: String
    ): Response<Unit>

    @GET("rest/v1/pool_coupons")
    suspend fun listShared(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") bearer: String,
        @Query("week_key") weekFilter: String,
        @Query("select") select: String = "id,owner_id,week_key,coupon_title,masked_name,match_labels,columns,shared_at",
        @Query("order") order: String = "shared_at.desc",
        @Query("limit") limit: Int = 100
    ): Response<List<PoolCouponDto>>

    @Headers("Prefer: return=representation")
    @POST("rest/v1/pool_coupons")
    suspend fun publish(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") bearer: String,
        @Body coupon: PoolCouponUpload
    ): Response<List<PoolCouponDto>>

    @DELETE("rest/v1/pool_coupons")
    suspend fun withdraw(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") bearer: String,
        @Query("id") idFilter: String
    ): Response<Unit>

    @POST("rest/v1/rpc/pool_rank_for_fixture_set")
    suspend fun rank(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") bearer: String,
        @Body input: PoolRankRequest
    ): Response<List<PoolRankResponse>>
}
