package com.example.network

import com.example.model.MatchLiveScore
import com.example.model.MatchStatus
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.Locale

/**
 * Keyless ESPN public soccer scoreboard endpoint:
 * https://site.api.espn.com/apis/site/v2/sports/soccer/{league}/scoreboard
 * Supports league slugs such as tur.1, eng.1, esp.1, ger.1, ita.1, fra.1, uefa.nations.
 */
interface EspnSoccerService {
    @GET("apis/site/v2/sports/soccer/{league}/scoreboard")
    suspend fun getScoreboard(
        @Path("league") league: String,
        @Query("dates") dates: String? = null,
        @Query("limit") limit: Int? = 100
    ): Response<EspnScoreboardResponse>
}

@JsonClass(generateAdapter = true)
data class EspnScoreboardResponse(
    val leagues: List<EspnLeagueInfo> = emptyList(),
    val events: List<EspnEvent> = emptyList()
)

@JsonClass(generateAdapter = true)
data class EspnLeagueInfo(
    val id: String? = null,
    val name: String? = null,
    val abbreviation: String? = null,
    val slug: String? = null
)

@JsonClass(generateAdapter = true)
data class EspnEvent(
    val id: String = "",
    val date: String? = null,
    val name: String? = null,
    val shortName: String? = null,
    val competitions: List<EspnCompetition> = emptyList(),
    val status: EspnEventStatus? = null
)

@JsonClass(generateAdapter = true)
data class EspnCompetition(
    val id: String = "",
    val date: String? = null,
    val venue: EspnVenue? = null,
    val competitors: List<EspnCompetitor> = emptyList(),
    val status: EspnEventStatus? = null
)

@JsonClass(generateAdapter = true)
data class EspnVenue(
    val fullName: String? = null
)

@JsonClass(generateAdapter = true)
data class EspnCompetitor(
    val id: String = "",
    val homeAway: String = "",
    val score: String? = null,
    val team: EspnTeam = EspnTeam()
)

@JsonClass(generateAdapter = true)
data class EspnTeam(
    val id: String = "",
    val displayName: String = "",
    val shortDisplayName: String = "",
    val abbreviation: String = ""
)

@JsonClass(generateAdapter = true)
data class EspnEventStatus(
    val clock: Double? = null,
    val displayClock: String? = null,
    val period: Int? = null,
    val type: EspnStatusType? = null
)

@JsonClass(generateAdapter = true)
data class EspnStatusType(
    val id: String? = null,
    val name: String? = null,
    val state: String? = null,
    val completed: Boolean = false,
    val description: String? = null,
    val detail: String? = null,
    val shortDetail: String? = null
)

internal fun EspnEvent.resolveStatus(): MatchStatus {
    val effective = competitions.firstOrNull()?.status ?: status ?: return MatchStatus.UPCOMING
    val state = effective.type?.state?.lowercase(Locale.ROOT).orEmpty()
    val name = effective.type?.name?.uppercase(Locale.ROOT).orEmpty()
    return when {
        name == "STATUS_HALFTIME" -> MatchStatus.HALF_TIME
        effective.type?.completed == true ||
            state == "post" ||
            name in setOf(
                "STATUS_FINAL",
                "STATUS_FULL_TIME",
                "STATUS_FINAL_AET",
                "STATUS_FINAL_PEN"
            ) -> MatchStatus.FINISHED
        state == "in" ||
            name in setOf(
                "STATUS_IN_PROGRESS",
                "STATUS_FIRST_HALF",
                "STATUS_SECOND_HALF",
                "STATUS_OVERTIME",
                "STATUS_EXTRA_TIME",
                "STATUS_SHOOTOUT"
            ) -> MatchStatus.LIVE
        else -> MatchStatus.UPCOMING
    }
}

internal fun EspnEvent.toMatchLiveScore(leagueName: String): MatchLiveScore? {
    val competition = competitions.firstOrNull() ?: return null
    val home = competition.competitors.firstOrNull { it.homeAway.equals("home", ignoreCase = true) }
        ?: return null
    val away = competition.competitors.firstOrNull { it.homeAway.equals("away", ignoreCase = true) }
        ?: return null

    val matchStatus = resolveStatus()
    if (matchStatus == MatchStatus.UPCOMING) return null

    val effectiveStatus = competition.status ?: status
    val displayClock = effectiveStatus?.displayClock?.trim().orEmpty()
    val shortDetail = effectiveStatus?.type?.shortDetail?.trim().orEmpty()
    val minuteText = when (matchStatus) {
        MatchStatus.HALF_TIME -> "İY"
        MatchStatus.FINISHED -> "MS"
        MatchStatus.LIVE -> when {
            displayClock.isNotBlank() && displayClock != "0'" && displayClock != "0:00" -> displayClock
            shortDetail.contains("'") -> shortDetail
            else -> "—"
        }
        MatchStatus.UPCOMING -> ""
    }

    val homeName = home.team.displayName.ifBlank { home.team.shortDisplayName }.trim()
    val awayName = away.team.displayName.ifBlank { away.team.shortDisplayName }.trim()
    if (homeName.isBlank() || awayName.isBlank()) return null

    val numericId = id.toIntOrNull()
        ?: competition.id.toIntOrNull()
        ?: (("${homeName}|${awayName}|${competition.date ?: date.orEmpty()}").hashCode() and 0x7FFFFFFF)

    return MatchLiveScore(
        matchNumber = numericId,
        homeTeam = homeName,
        awayTeam = awayName,
        homeScore = home.score?.toIntOrNull(),
        awayScore = away.score?.toIntOrNull(),
        status = matchStatus,
        minuteText = minuteText,
        matchDate = competition.date ?: date.orEmpty(),
        league = leagueName
    )
}
