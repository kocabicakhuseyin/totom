package com.example.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Only masked nicknames, never emails or platform credentials, are public. */
@JsonClass(generateAdapter = true)
data class PoolCouponDto(
    val id: String,
    @Json(name = "week_key") val weekKey: String,
    @Json(name = "coupon_title") val title: String,
    @Json(name = "masked_name") val maskedName: String,
    @Json(name = "match_labels") val matchLabels: List<String>,
    val columns: List<String>,
    @Json(name = "shared_at") val sharedAt: String = "",
    @Json(name = "owner_id") val ownerId: String = ""
)

@JsonClass(generateAdapter = true)
data class PoolCouponUpload(
    @Json(name = "week_key") val weekKey: String,
    @Json(name = "source_coupon_id") val sourceCouponId: String,
    @Json(name = "coupon_title") val title: String,
    @Json(name = "masked_name") val maskedName: String,
    @Json(name = "match_labels") val matchLabels: List<String>,
    val columns: List<String>,
    @Json(name = "confirmed_by_member") val confirmedByMember: Boolean = true
)

data class PoolColumnRank(
    val couponId: String,
    val displayName: String,
    val columnNumber: Int,
    val predictions: String,
    val finishedCorrect: Int,
    val finishedWrong: Int,
    val liveCorrect: Int,
    val liveWrong: Int,
    val maximumPossible: Int,
    val availableResults: Int
)

object CouponPoolEvaluator {
    private val aliases = mapOf(
        "turkey" to "turkiye",
        "fransa" to "france", "almanya" to "germany", "italya" to "italy",
        "ispanya" to "spain", "belcika" to "belgium", "ingiltere" to "england",
        "hollanda" to "netherlands", "hirvatistan" to "croatia",
        "portekiz" to "portugal", "danimarka" to "denmark",
        "isvicre" to "switzerland", "isvec" to "sweden", "polonya" to "poland",
        "sirbistan" to "serbia", "macaristan" to "hungary",
        "estonya" to "estonia", "gurcistan" to "georgia",
        "izlanda" to "iceland", "norvec" to "norway", "galler" to "wales",
        "azerbaycan" to "azerbaijan", "lihtenstayn" to "liechtenstein",
        "luksemburg" to "luxembourg", "faroe adalari" to "faroe islands",
        "slovakya" to "slovakia", "avusturya" to "austria",
        "bulgaristan" to "bulgaria", "cekya" to "czechia",
        "czech republic" to "czechia", "ukrayna" to "ukraine",
        "yunanistan" to "greece", "romanya" to "romania",
        "iskocya" to "scotland", "irlanda" to "ireland",
        "kuzey irlanda" to "northern ireland", "finlandiya" to "finland",
        "slovenya" to "slovenia", "bosna hersek" to "bosnia and herzegovina",
        "bosnia herzegovina" to "bosnia and herzegovina",
        "arnavutluk" to "albania", "karadag" to "montenegro",
        "kuzey makedonya" to "north macedonia", "makedonya" to "north macedonia",
        "kosova" to "kosovo", "rusya" to "russia",
        "brezilya" to "brazil", "arjantin" to "argentina",
        "istanbul basaksehir" to "basaksehir",
        "rams basaksehir" to "basaksehir",
        "basaksehir" to "basaksehir",
        "caykur rizespor" to "rizespor",
        "fatih karagumruk" to "karagumruk",
        "gaziantep fk" to "gaziantep",
        "gazisehir gaziantep" to "gaziantep",
        "gaziantep bb" to "gaziantep",
        "mke ankaragucu" to "ankaragucu",
        "kasimpasa sk" to "kasimpasa",
        "besiktas jk" to "besiktas",
        "fenerbahce sk" to "fenerbahce",
        "galatasaray sk" to "galatasaray",
        "trabzonspor as" to "trabzonspor",
        "amed sportif faaliyetler" to "amed",
        "amed sk" to "amed",
        "amedspor" to "amed",
        "erzurumspor" to "erzurum bb",
        "bb erzurumspor" to "erzurum bb",
        "erzurum bb" to "erzurum bb",
        "corum fk" to "corum",
        "corumspor" to "corum",
        "corum belediyespor" to "corum",
        "genclerbirligi" to "genclerbirligi",
        "kocaelispor" to "kocaelispor",
        "eyupspor" to "eyupspor",
        "samsunspor" to "samsunspor",
        "goztepe" to "goztepe",
        "alanyaspor" to "alanyaspor",
        "konyaspor" to "konyaspor",
        "antalyaspor" to "antalyaspor",
        "kayserispor" to "kayserispor",
        "sivasspor" to "sivasspor",
        "hatayspor" to "hatayspor",
        "adana demirspor" to "adana demirspor",
        "bodrum" to "bodrumspor",
        "bodrumspor" to "bodrumspor",
        "sakaryaspor" to "sakaryaspor",
        "pendikspor" to "pendikspor",
        "bandirmaspor" to "bandirmaspor",
        "boluspor" to "boluspor",
        "umraniyespor" to "umraniyespor",
        "manisa" to "manisa fk",
        "manisa fk" to "manisa fk",
        "ankara keciorengucu" to "keciorengucu",
        "keciorengucu" to "keciorengucu",
        "igdir" to "igdir fk",
        "igdir fk" to "igdir fk",
        "esenler erokspor" to "erokspor",
        "bayern munih" to "bayern munich",
        "vfb stuttgart" to "stuttgart",
        "fc schalke 04" to "schalke 04",
        "losc lille" to "lille",
        "olympique lyonnais" to "lyon",
        "olympique lyon" to "lyon",
        "newcastle united" to "newcastle",
        "man city" to "manchester city",
        "man united" to "manchester united",
        "man utd" to "manchester united",
        "inter milan" to "inter",
        "internazionale" to "inter",
        "ac milan" to "milan",
        "paris sg" to "paris saint germain",
        "psg" to "paris saint germain",
        "athletic bilbao" to "athletic club",
        "fc bayern munich" to "bayern munich",
        "fc bayern munchen" to "bayern munich",
        "atletico de madrid" to "atletico madrid",
        "as monaco" to "monaco",
        "stade rennais" to "rennes",
        "borussia dortmund" to "dortmund",
        "bayer leverkusen" to "leverkusen",
        "rb leipzig" to "leipzig",
        "tottenham hotspur" to "tottenham",
        "wolverhampton wanderers" to "wolves",
        "west ham united" to "west ham",
        "brighton and hove albion" to "brighton"
    )

    private val clubSponsorPrefixes = setOf(
        "rams", "tumosan", "corendon", "yukatel", "ikas", "sipay", "bellona",
        "onvo", "atakas", "bitexen", "mondihome", "mke", "caykur", "net", "global",
        "ems", "yapi", "siltaas", "tuzla", "central", "hospital", "solwie", "energy",
        "trendyol"
    )

    private val clubCorporateSuffixes = setOf(
        "a", "s", "as", "sk", "fk", "jk", "sfk", "fc", "cf", "futbol", "kulubu",
        "sportif", "faaliyetler"
    )

    fun maskedName(nickname: String): String {
        val visible = nickname.trim().replace(Regex("[\\p{C}\\r\\n]"), "")
            .take(24)
        if (visible.isEmpty()) return "Ü***e"
        if (visible.length <= 2) return visible.first() + "***"
        return visible.first() + "***" + visible.last()
    }

    fun matchLabels(matches: List<TotoMatch>): List<String> = matches
        .sortedBy { it.matchNumber }
        .take(15)
        .map { "${it.homeTeam}|${it.awayTeam}|${it.matchDate}" }

    fun resolveBulletinScores(matches: List<TotoMatch>, liveScores: List<MatchLiveScore>): List<MatchLiveScore?> {
        if (matches.isEmpty()) return emptyList()
        val ordered = matches.sortedBy { it.matchNumber }.take(15)
        return resolveScores(matchLabels(ordered), liveScores)
    }

    /**
     * Filters raw provider scores down to ONLY fixtures present in the Spor Toto bulletin
     * (including live, half-time, and finished matches), aligned by bulletin matchNumber (1..15).
     */
    fun filterAndAlignToBulletin(
        matches: List<TotoMatch>,
        liveScores: List<MatchLiveScore>
    ): List<MatchLiveScore> {
        if (matches.isEmpty()) return emptyList()
        val ordered = matches.sortedBy { it.matchNumber }.take(15)
        val matched = resolveBulletinScores(ordered, liveScores)
        return ordered.mapIndexedNotNull { idx, totoMatch ->
            matched.getOrNull(idx)?.copy(matchNumber = totoMatch.matchNumber)
        }.sortedWith(
            compareByDescending<MatchLiveScore> { it.isLive }
                .thenBy { it.matchNumber }
        )
    }

    /** Each character is 1/X/2 if verified, or '-' when not yet known. */
    fun resultCodes(matchLabels: List<String>, liveScores: List<MatchLiveScore>): Pair<String, String> {
        if (matchLabels.size != 15) return "-".repeat(15) to "-".repeat(15)
        val matched = resolveScores(matchLabels, liveScores)
        val final = matched.joinToString("") { score ->
            if (score?.status == MatchStatus.FINISHED) score.winningOutcome?.code ?: "-" else "-"
        }
        val live = matched.joinToString("") { score ->
            if (score?.isLive == true) score.winningOutcome?.code ?: "-" else "-"
        }
        return final to live
    }

    fun rank(coupons: List<PoolCouponDto>, liveScores: List<MatchLiveScore>): List<PoolColumnRank> {
        return coupons.asSequence().flatMap { coupon ->
            if (coupon.matchLabels.size != 15) return@flatMap emptySequence()
            val results = resolveScores(coupon.matchLabels, liveScores)
            coupon.columns.asSequence().mapIndexedNotNull { idx, code ->
                if (!code.matches(Regex("[1X2]{15}"))) return@mapIndexedNotNull null
                var finishedCorrect = 0
                var finishedWrong = 0
                var liveCorrect = 0
                var liveWrong = 0
                results.forEachIndexed { index, score ->
                    val outcome = score?.winningOutcome?.code ?: return@forEachIndexed
                    val correct = code[index].toString() == outcome
                    when (score.status) {
                        MatchStatus.FINISHED -> if (correct) finishedCorrect++ else finishedWrong++
                        MatchStatus.LIVE, MatchStatus.HALF_TIME -> if (correct) liveCorrect++ else liveWrong++
                        MatchStatus.UPCOMING -> Unit
                    }
                }
                PoolColumnRank(
                    couponId = coupon.id,
                    displayName = coupon.maskedName,
                    columnNumber = idx + 1,
                    predictions = code,
                    finishedCorrect = finishedCorrect,
                    finishedWrong = finishedWrong,
                    liveCorrect = liveCorrect,
                    liveWrong = liveWrong,
                    maximumPossible = 15 - finishedWrong,
                    availableResults = finishedCorrect + finishedWrong + liveCorrect + liveWrong
                )
            }
        }.sortedWith(
            compareByDescending<PoolColumnRank> { it.finishedCorrect }
                .thenByDescending { it.maximumPossible }
                .thenByDescending { it.liveCorrect }
                .thenBy { it.finishedWrong }
        ).take(40).toList()
    }

    fun resolveScores(labels: List<String>, liveScores: List<MatchLiveScore>): List<MatchLiveScore?> {
        val scoresByTeams = liveScores
            .filter { it.homeScore != null && it.awayScore != null && it.status != MatchStatus.UPCOMING }
            .groupBy { pairKey(it.homeTeam, it.awayTeam) }
        return labels.map { label ->
            val parts = label.split('|', limit = 3)
            if (parts.size < 2) return@map null
            val expectedDate = parts.getOrNull(2)?.let { matchTimeMs(it, isBulletin = true) }
            scoresByTeams[pairKey(parts[0], parts[1])].orEmpty().filter { score ->
                val actualDate = matchTimeMs(score.matchDate, isBulletin = false)
                expectedDate == null || actualDate == null || abs(expectedDate - actualDate) < 12 * 60 * 60 * 1000L
            }.singleOrNull() // Ambiguous fixtures are never guessed.
        }
    }

    private fun pairKey(home: String, away: String): String =
        "${canonical(home)}|${canonical(away)}"

    private fun canonical(team: String): String {
        val deaccented = Normalizer.normalize(team.lowercase(Locale.ROOT).replace('ı', 'i'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim().replace(Regex("\\s+"), " ")
        aliases[deaccented]?.let { return it }

        val tokens = deaccented.split(" ").toMutableList()
        while (tokens.size > 1 && tokens.first() in clubSponsorPrefixes) {
            tokens.removeAt(0)
        }
        while (tokens.size > 1 && tokens.last() in clubCorporateSuffixes) {
            tokens.removeAt(tokens.size - 1)
        }
        val stripped = tokens.joinToString(" ")
        return aliases[stripped] ?: stripped
    }

    private fun matchTimeMs(value: String, isBulletin: Boolean): Long? {
        if (value.isBlank()) return null
        val patterns = if (isBulletin) listOf("dd.MM.yyyy HH:mm", "yyyy-MM-dd HH:mm:ss")
            else listOf(
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mmXXX",
                "yyyy-MM-dd'T'HH:mm'Z'",
                "yyyy-MM-dd HH:mm:ss"
            )
        return patterns.firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.ROOT).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone(if (isBulletin) "Europe/Istanbul" else "UTC")
                }.parse(value)?.time
            } catch (_: Exception) { null }
        }
    }
}
