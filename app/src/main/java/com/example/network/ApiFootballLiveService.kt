package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/** Official API-FOOTBALL v3 fixture data; no generated scores or elapsed minutes. */
interface ApiFootballLiveService {
    @GET("fixtures")
    suspend fun getLiveFixtures(
        @Header("x-apisports-key") apiKey: String,
        @Query("live") live: String = "all"
    ): Response<ApiFootballLiveResponse>

    @GET("fixtures")
    suspend fun getFixturesByDate(
        @Header("x-apisports-key") apiKey: String,
        @Query("date") date: String,
        @Query("timezone") timezone: String = "Europe/Istanbul"
    ): Response<ApiFootballLiveResponse> = Response.success(ApiFootballLiveResponse())

    @GET("fixtures")
    suspend fun getFixturesByLeague(
        @Header("x-apisports-key") apiKey: String,
        @Query("league") leagueId: Int,
        @Query("season") season: Int,
        @Query("from") fromDate: String? = null,
        @Query("to") toDate: String? = null,
        @Query("timezone") timezone: String = "Europe/Istanbul"
    ): Response<ApiFootballLiveResponse> = Response.success(ApiFootballLiveResponse())
}

@JsonClass(generateAdapter = true)
data class ApiFootballLiveResponse(
    val response: List<ApiFootballLiveFixture> = emptyList(),
    // The provider returns [] on success and an object on error.
    val errors: Any? = null
)

@JsonClass(generateAdapter = true)
data class ApiFootballLiveFixture(
    val fixture: ApiFootballFixtureInfo,
    val teams: ApiFootballTeams,
    val goals: ApiFootballGoals,
    val league: ApiFootballLeague? = null
)

@JsonClass(generateAdapter = true)
data class ApiFootballFixtureInfo(
    val id: Int,
    val date: String? = null,
    val status: ApiFootballFixtureStatus
)

@JsonClass(generateAdapter = true)
data class ApiFootballFixtureStatus(
    val short: String = "",
    val elapsed: Int? = null
)

@JsonClass(generateAdapter = true)
data class ApiFootballTeams(
    val home: ApiFootballTeam,
    val away: ApiFootballTeam
)

@JsonClass(generateAdapter = true)
data class ApiFootballTeam(val name: String)

@JsonClass(generateAdapter = true)
data class ApiFootballGoals(val home: Int? = null, val away: Int? = null)

@JsonClass(generateAdapter = true)
data class ApiFootballLeague(
    val name: String? = null,
    val id: Int? = null,
    val country: String? = null,
    val round: String? = null
)
