package com.example

import com.example.model.FormulaType
import com.example.model.MatchLiveScore
import com.example.model.PredictionOutcome
import com.example.model.TotoMatch
import com.example.network.NetworkModule
import com.example.network.model.BulletinMatchDto
import com.example.network.model.WeeklyBulletinResponse
import com.example.network.model.toDomainTotoMatch
import com.example.repository.SporTotoBulletinRepository
import com.example.viewmodel.TotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class StubForumBulletinRepository : SporTotoBulletinRepository {
    private val bulletin = NetworkModule.createFallbackBulletin()
    override val cachedBulletin: StateFlow<WeeklyBulletinResponse?> = MutableStateFlow(bulletin)
    override val liveScores: StateFlow<List<MatchLiveScore>> = MutableStateFlow(emptyList())
    override val liveScoreProvider: StateFlow<String> = MutableStateFlow("Test")

    override suspend fun getWeeklyBulletin(forceRefresh: Boolean): Result<WeeklyBulletinResponse> =
        Result.success(bulletin)

    override suspend fun getBulletinByWeek(weekNumber: Int): Result<WeeklyBulletinResponse> =
        Result.success(bulletin)

    override suspend fun refreshLiveOdds(): Result<List<BulletinMatchDto>> =
        Result.success(bulletin.matches)

    override suspend fun getDomainMatches(forceRefresh: Boolean): Result<List<TotoMatch>> =
        Result.success(bulletin.matches.map { it.toDomainTotoMatch() })

    override suspend fun pollLiveScores(): Result<List<MatchLiveScore>> =
        Result.success(emptyList())

    override fun hasApiFootballKey(): Boolean = false
    override fun configureApiFootballKey(apiKey: String) {}
    override fun freeScoreRequestsRemaining(): Int? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class BulletinForumTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial forum posts link to active bulletin matches`() {
        val viewModel = TotoViewModel(bulletinRepository = StubForumBulletinRepository())
        val posts = viewModel.forumPosts.value
        val match1 = viewModel.matches.value.first { it.matchNumber == 1 }
        val p1 = posts.first { it.id == "p1" }
        assertEquals(1, p1.matchNumber)
        assertEquals("#1 ${match1.homeTeam} - ${match1.awayTeam}", p1.matchReference)
        assertTrue(posts.any { it.matchNumber == null }) // general bulletin post
    }

    @Test
    fun `addForumPost attaches bulletin match prediction reply and member metadata`() {
        val viewModel = TotoViewModel(bulletinRepository = StubForumBulletinRepository())
        val coupon = viewModel.generateCoupon(FormulaType.GUARANTEE_14, "Hafta Kuponum")
        val targetMatch = viewModel.matches.value.first { it.matchNumber == 3 }

        viewModel.addForumPost(
            content = "Deplasman galibiyeti çok güçlü görünüyor.",
            attachedCouponId = coupon.id,
            matchNumber = targetMatch.matchNumber,
            predictedOutcome = PredictionOutcome.AWAY,
            replyToPostId = "p1",
            replyToAuthor = "TATAR34",
            overrideAuthorName = "HüseyinÜye",
            providerLabel = "Google Hesabı"
        )

        val newest = viewModel.forumPosts.value.first()
        assertEquals("HüseyinÜye", newest.authorName)
        assertEquals("Google Hesabı", newest.providerLabel)
        assertEquals("Doğrulanmış Üye", newest.authorTitle)
        assertEquals(3, newest.matchNumber)
        assertEquals("#3 ${targetMatch.homeTeam} - ${targetMatch.awayTeam}", newest.matchReference)
        assertEquals(PredictionOutcome.AWAY, newest.predictedOutcome)
        assertEquals("p1", newest.replyToPostId)
        assertEquals("TATAR34", newest.replyToAuthor)
        assertNotNull(newest.attachedCouponSummary)
        assertTrue(newest.attachedCouponSummary!!.contains("Hafta Kuponum"))
        assertEquals(coupon.totalColumns, newest.attachedCouponColumns.size)
        assertEquals(coupon.formulaType.title, newest.attachedCouponFormulaTitle)
    }

    @Test
    fun `shared forum coupon remains inspectable and re-openable even if removed from local coupons`() {
        val viewModel = TotoViewModel(bulletinRepository = StubForumBulletinRepository())
        val coupon = viewModel.generateCoupon(FormulaType.GUARANTEE_13, "Paylaşılan 13G")

        viewModel.addForumPost(
            content = "Bu kuponu forumda inceleyebilirsiniz.",
            attachedCouponId = coupon.id
        )

        val sharedPost = viewModel.forumPosts.value.first()
        assertEquals(coupon.totalColumns, sharedPost.attachedCouponColumns.size)

        // Delete original coupon from local list; snapshot on post must still resolve it
        viewModel.deleteCoupon(coupon.id)
        assertTrue(viewModel.coupons.value.none { it.id == coupon.id })

        val resolved = com.example.ui.screens.resolveForumPostCoupon(
            post = sharedPost,
            coupons = viewModel.coupons.value
        )
        assertNotNull(resolved)
        assertEquals(coupon.totalColumns, resolved!!.totalColumns)
        assertEquals(coupon.columns.first().asString, resolved.columns.first().asString)

        viewModel.openSharedForumCoupon(resolved)
        assertEquals(resolved.id, viewModel.currentCoupon.value?.id)
        assertTrue(viewModel.coupons.value.any { it.id == resolved.id })
    }
}
