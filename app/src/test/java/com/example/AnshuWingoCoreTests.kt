package com.example

import android.content.Context
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.analysis.AnalysisCoordinator
import com.example.analytics.AnalyticsRepository
import com.example.analytics.AnalyticsWindow
import com.example.classification.ClassificationRuleConfig
import com.example.classification.ResultClassificationService
import com.example.data.local.AppDatabase
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity
import com.example.data.repository.HistoryCollector
import com.example.data.repository.HistoryRepository
import com.example.data.repository.HistorySyncResult
import com.example.di.AppContainer
import com.example.domain.model.DataFreshnessStatus
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus
import com.example.domain.model.WinGoColor
import com.example.domain.model.WinGoGameConfig
import com.example.network.ApiAuthenticationProvider
import com.example.network.ApiCallOutcome
import com.example.network.ApiRequestManager
import com.example.network.ApiSignatureProvider
import com.example.network.ApiTokenRefreshProvider
import com.example.network.AuthorizedApiCredentialProvider
import com.example.network.AuthorizedApiParams
import com.example.network.AuthorizedBackendProxyService
import com.example.network.BackendIntegrationStatus
import com.example.network.BackendProxyApiRequestManager
import com.example.network.BackendProxyConfig
import com.example.network.CompositeApiAuthenticationProvider
import com.example.network.DrawHistoryJsonService
import com.example.network.GetPanelApiService
import com.example.network.GetPanelParser
import com.example.network.GetPanelResponse
import com.example.network.GetPanelValidationResult
import com.example.network.HistoryApiService
import com.example.network.HistoryApiStatus
import com.example.network.HistoryDataSource
import com.example.network.HistoryFetchOutcome
import com.example.network.HistoryPageData
import com.example.network.HistoryPageResponse
import com.example.network.HistoryRecordDto
import com.example.network.KeystoreBackedCredentialProvider
import com.example.network.LiveHistoryPoller
import com.example.network.NetworkConnectivityMonitor
import com.example.network.PanelData
import com.example.network.RetrofitClient
import com.example.network.SignedRequestParams
import com.example.network.UnconfiguredApiAuthenticationProvider
import com.example.network.ValidatedSnapshotItem
import com.example.prediction.EnsembleEngine
import com.example.prediction.LockDecision
import com.example.prediction.PredictionLockManager
import com.example.prediction.PredictionRepository
import com.example.prediction.PredictionResult
import com.example.security.ApiAuthStatusCode
import com.example.security.ApiAuthenticationState
import com.example.security.CryptoKeyProvider
import com.example.security.DiagnosticApiAuthStatus
import com.example.security.DiagnosticLoginState
import com.example.security.DiagnosticSessionCheck
import com.example.security.KeystoreCryptoManager
import com.example.security.WebsiteDetectionState
import com.example.security.WebsiteSessionBridge
import com.example.security.WebsiteSessionManager
import com.example.security.WebsiteSessionState
import com.example.timer.TimerSyncManager
import com.example.timer.TimerUiState
import com.example.training.ModelTrainingSnapshot
import com.example.ui.PredictorPanelTab
import com.example.ui.PredictorPanelUiState
import com.example.ui.PredictorViewModel
import com.example.ui.UserActivationState
import com.example.ui.components.FloatingPredictorPanel
import com.example.ui.screens.WebViewScreen
import com.example.ui.theme.AnshuWingoTheme
import com.example.validation.AccuracyTracker
import com.example.validation.ProbabilityCalibrator
import com.example.webview.DomLoginObservation
import com.example.webview.SecureWebViewManager
import com.example.webview.WebsiteLoginDetector
import com.example.webview.WebsiteLoginState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnshuWingoCoreTests {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var classificationService: ResultClassificationService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        classificationService = ResultClassificationService(
            ClassificationRuleConfig(
                officialColorRuleConfirmed = true,
                officialBigSmallRuleConfirmed = true
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `result trimming to 500 never deletes prediction records`() = runBlocking {
        val resultDao = db.resultDao()
        val predictionDao = db.predictionDao()

        val prediction = PredictionEntity(
            issueNumber = "20261113100000001",
            createdAt = 1000L,
            lockedAt = 1500L,
            numberProbabilities = (0..9).associateWith { if (it == 7) 0.28 else 0.08 },
            colorProbabilities = mapOf("Green" to 0.6, "Red" to 0.3, "Violet" to 0.1),
            sizeProbabilities = mapOf("Big" to 0.65, "Small" to 0.35),
            predictedNumber = 7,
            predictedColor = "Green",
            predictedSize = "Big",
            confidence = 0.65,
            contributingModels = "frequency_analysis,bayesian_model",
            modelVersions = "frequency_analysis@1.0.0,bayesian_model@1.0.0",
            verificationStatus = VerificationStatus.PENDING,
            predictionStatus = PredictionStatus.LOCKED
        )
        predictionDao.insertPredictionAndTrim(prediction)

        val results = (1..520).map { i ->
            ResultEntity(
                issueNumber = "202611131000%05d".format(i),
                number = i % 10,
                color = WinGoColor.primaryFromNumber(i % 10).label.lowercase(),
                premium = "0",
                sum = i % 10,
                blockId = "b$i",
                blockNumber = "$i",
                blockTimestamp = "1700000000",
                receivedAt = 2000L + i
            )
        }
        resultDao.upsertAndTrimToLatest500(results)
        // Duplicate upsert of subset must preserve count and never clear predictions
        resultDao.upsertAndTrimToLatest500(results.take(10))

        assertEquals(WinGoGameConfig.MAX_HISTORY_RESULTS, resultDao.getResultCount())
        val survivingPrediction = predictionDao.getByIssueNumber("20261113100000001")
        assertNotNull(survivingPrediction)
    }

    @Test
    fun `result dao and collector order variable-length numeric issue numbers accurately`() = runBlocking {
        val resultDao = db.resultDao()
        resultDao.upsertAndTrimToLatest500(
            listOf(
                ResultEntity(
                    issueNumber = "999",
                    number = 2,
                    color = "red",
                    premium = "",
                    sum = 2,
                    blockId = "",
                    blockNumber = "",
                    blockTimestamp = "",
                    receivedAt = 1000L
                ),
                ResultEntity(
                    issueNumber = "1000",
                    number = 7,
                    color = "green",
                    premium = "",
                    sum = 7,
                    blockId = "",
                    blockNumber = "",
                    blockTimestamp = "",
                    receivedAt = 2000L
                )
            )
        )
        val latest = resultDao.getLatestResultsSnapshot(10)
        assertEquals(listOf("1000", "999"), latest.map { it.issueNumber })
        assertTrue(HistoryCollector.compareIssuesDescending("1000", "999") < 0)
    }

    @Test
    fun `prediction verification enforces lock prior to result arrival and ignores skipped`() = runBlocking {
        val repo = PredictionRepository(db.predictionDao(), classificationService)
        val analytics = AnalyticsRepository(db.predictionDao(), db.analysisDao(), AccuracyTracker())

        val lockedInTime = PredictionEntity(
            issueNumber = "ISSUE_001",
            createdAt = 1000L,
            lockedAt = 1500L,
            numberProbabilities = emptyMap(),
            colorProbabilities = emptyMap(),
            sizeProbabilities = emptyMap(),
            predictedNumber = 7,
            predictedColor = "Green",
            predictedSize = "Big",
            confidence = 0.7,
            contributingModels = "frequency_analysis",
            modelVersions = "frequency_analysis@1.0.0",
            verificationStatus = VerificationStatus.PENDING,
            predictionStatus = PredictionStatus.LOCKED
        )
        val lockedTooLate = lockedInTime.copy(
            issueNumber = "ISSUE_002",
            createdAt = 2000L,
            lockedAt = 5000L
        )
        val skipped = lockedInTime.copy(
            issueNumber = "ISSUE_003",
            createdAt = 3000L,
            lockedAt = 3100L,
            predictionStatus = PredictionStatus.SKIPPED,
            verificationStatus = VerificationStatus.SKIPPED
        )

        db.predictionDao().insertPredictionAndTrim(lockedInTime)
        db.predictionDao().insertPredictionAndTrim(lockedTooLate)
        db.predictionDao().insertPredictionAndTrim(skipped)

        repo.verifyAgainstNewResults(
            listOf(
                ResultEntity(
                    issueNumber = "ISSUE_001",
                    number = 7,
                    color = "green",
                    premium = "",
                    sum = 7,
                    blockId = "",
                    blockNumber = "",
                    blockTimestamp = "",
                    receivedAt = 2000L
                ),
                ResultEntity(
                    issueNumber = "ISSUE_002",
                    number = 7,
                    color = "green",
                    premium = "",
                    sum = 7,
                    blockId = "",
                    blockNumber = "",
                    blockTimestamp = "",
                    receivedAt = 4000L
                ),
                ResultEntity(
                    issueNumber = "ISSUE_003",
                    number = 7,
                    color = "green",
                    premium = "",
                    sum = 7,
                    blockId = "",
                    blockNumber = "",
                    blockTimestamp = "",
                    receivedAt = 4500L
                )
            )
        )

        val v1 = db.predictionDao().getByIssueNumber("ISSUE_001")!!
        val v2 = db.predictionDao().getByIssueNumber("ISSUE_002")!!
        assertEquals(VerificationStatus.VERIFIED, v1.verificationStatus)
        assertEquals(true, v1.numberCorrect)
        assertEquals(VerificationStatus.UNVERIFIABLE, v2.verificationStatus)

        val snapshot = analytics.buildSnapshot(
            AnalyticsWindow.LAST_50,
            db.predictionDao().getLatestPredictionsSnapshot(50)
        )
        assertEquals(1, snapshot.verifiedCount)
        assertEquals(1, snapshot.correctCount)
        assertEquals(1, snapshot.skippedCount)
        assertEquals(100.0, snapshot.overallAccuracyPercent!!, 0.01)
    }

    @Test
    fun `accuracy tracker uses strict full-target match without inflating via binary size or color`() {
        val tracker = AccuracyTracker()
        val wrongNumberOnlyColorSizeMatch = PredictionEntity(
            issueNumber = "STRICT_001",
            createdAt = 1000L,
            lockedAt = 1100L,
            numberProbabilities = emptyMap(),
            colorProbabilities = emptyMap(),
            sizeProbabilities = emptyMap(),
            predictedNumber = 7,
            predictedColor = "Green",
            predictedSize = "Big",
            actualNumber = 9,
            actualColor = "green",
            actualSize = "big",
            numberCorrect = false,
            colorCorrect = true,
            sizeCorrect = true,
            confidence = 0.6,
            contributingModels = "markov_chain",
            modelVersions = "markov_chain@1.0.0",
            verificationStatus = VerificationStatus.VERIFIED,
            predictionStatus = PredictionStatus.LOCKED
        )

        val report = tracker.computeReport(listOf(wrongNumberOnlyColorSizeMatch))
        assertEquals(1, report.verifiedCount)
        assertEquals(0, report.correctCount)
        assertEquals(1, report.wrongCount)
        assertEquals(0.0, report.overallAccuracy!!, 1e-6)
        assertEquals(0.0, report.numberAccuracy!!, 1e-6)
        assertEquals(1.0, report.colorAccuracy!!, 1e-6)
        assertEquals(1.0, report.sizeAccuracy!!, 1e-6)
    }

    @Test
    fun `ensemble reports exact unavailable reason when empty or missing issue`() {
        val ensemble = EnsembleEngine(classificationService, ProbabilityCalibrator())

        val emptyRes = ensemble.combine(
            issueNumber = "20261113100010849",
            freshness = DataFreshnessStatus.LIVE,
            dataCutoffIssue = null,
            trainingSnapshot = ModelTrainingSnapshot(historySampleSize = 0)
        )
        assertTrue(emptyRes is PredictionResult.Unavailable)
        assertEquals("No historical data available.", (emptyRes as PredictionResult.Unavailable).headline)

        val noIssueRes = ensemble.combine(
            issueNumber = null,
            freshness = DataFreshnessStatus.AWAITING_AUTH,
            dataCutoffIssue = "100",
            trainingSnapshot = ModelTrainingSnapshot(historySampleSize = 50)
        )
        assertTrue(noIssueRes is PredictionResult.Unavailable)
        assertEquals("Authorized API session unavailable.", (noIssueRes as PredictionResult.Unavailable).headline)
    }

    @Test
    fun `lock manager prevents post-outcome prediction creation and duplicate overwrite`() = runBlocking {
        val lockManager = PredictionLockManager(db.predictionDao(), db.resultDao())

        db.resultDao().upsertAndTrimToLatest500(
            listOf(
                ResultEntity(
                    issueNumber = "SETTLED_ISSUE",
                    number = 4,
                    color = "red",
                    premium = "",
                    sum = 4,
                    blockId = "",
                    blockNumber = "",
                    blockTimestamp = "",
                    receivedAt = 1000L
                )
            )
        )

        val readyTemplate = PredictionResult.Ready(
            issueNumber = "SETTLED_ISSUE",
            createdAtMillis = 2000L,
            lockedAtMillis = null,
            predictionStatus = PredictionStatus.DRAFT,
            freshness = DataFreshnessStatus.LIVE,
            predictedNumber = 4,
            predictedColor = WinGoColor.RED,
            predictedSize = null,
            colorUnavailableReason = null,
            sizeUnavailableReason = null,
            numberProbabilities = (0..9).associateWith { 0.1 },
            colorProbabilities = mapOf("Red" to 0.5, "Green" to 0.4, "Violet" to 0.1),
            sizeProbabilities = null,
            confidence = 0.25,
            disagreementScore = 0.05,
            validContributingCount = 2,
            modelVersions = "test@1.0",
            dataCutoffIssue = "PREV",
            contributions = emptyList(),
            moduleOutputs = emptyList(),
            baselineBenchmark = null,
            calibrationReport = null,
            driftReport = null
        )

        val settledDecision = lockManager.lockBeforeOutcome(readyTemplate, DataFreshnessStatus.LIVE, 15_000L, "Betting")
        assertTrue(settledDecision is LockDecision.Rejected)

        val openDecision1 = lockManager.lockBeforeOutcome(
            readyTemplate.copy(issueNumber = "OPEN_ISSUE"),
            DataFreshnessStatus.LIVE,
            15_000L,
            "Betting"
        )
        assertTrue(openDecision1 is LockDecision.Locked)

        val openDecision2 = lockManager.lockBeforeOutcome(
            readyTemplate.copy(issueNumber = "OPEN_ISSUE", predictedNumber = 9),
            DataFreshnessStatus.LIVE,
            14_000L,
            "Betting"
        )
        assertTrue(openDecision2 is LockDecision.AlreadyLocked)
        assertEquals(4, (openDecision2 as LockDecision.AlreadyLocked).existing.predictedNumber)
    }

    @Test
    fun `website session manager keeps UNKNOWN without official signal and separates API 401 from website auth`() {
        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))

        // URL/registration navigation alone must keep WebsiteSessionState.Unknown
        sessionManager.evaluateCookieSession("https://9kclub55.com/#/home", mainFrameLoaded = true)
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Unknown)
        assertEquals(DiagnosticLoginState.UNKNOWN, sessionManager.diagnostics.value.loginState)

        sessionManager.onBridgeSessionSignal(true, "https://untrusted.example.com/#/home")
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Unknown)

        // Confirmed origin-validated website login
        sessionManager.onBridgeSessionSignal(true, "https://9kclub55.com/#/home")
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Authenticated)
        assertTrue(sessionManager.diagnostics.value.floatingButtonVisible)

        // API 401/403 must update ApiAuthenticationState.Rejected ONLY without logging out website user
        sessionManager.onApiAuthenticationRejected(401, "API rejected authorization (HTTP 401).")
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Authenticated)
        assertTrue(sessionManager.apiAuthState.value is ApiAuthenticationState.Rejected)
        assertEquals(401, (sessionManager.apiAuthState.value as ApiAuthenticationState.Rejected).httpCode)
        assertEquals(DiagnosticLoginState.AUTHENTICATED, sessionManager.diagnostics.value.loginState)
        assertEquals(DiagnosticApiAuthStatus.REJECTED, sessionManager.diagnostics.value.apiAuthStatus)
        assertTrue(sessionManager.diagnostics.value.floatingButtonVisible)

        // Explicit website logout hides floating button & marks Unauthenticated
        sessionManager.onBridgeSessionSignal(false, "https://9kclub55.com/#/home")
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Unauthenticated)
        assertFalse(sessionManager.diagnostics.value.floatingButtonVisible)
    }

    @Test
    fun `history repository computes accurate frequencies and gap stats from real records`() {
        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))
        val networkMonitor = NetworkConnectivityMonitor(context)
        val retrofit = RetrofitClient.createRetrofit()
        val apiRequestManager = ApiRequestManager(
            historyApiService = RetrofitClient.createHistoryApiService(retrofit),
            getPanelApiService = RetrofitClient.createGetPanelApiService(retrofit),
            authProvider = UnconfiguredApiAuthenticationProvider(sessionManager),
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )
        val collector = HistoryCollector(apiRequestManager, db.resultDao(), networkMonitor)
        val repo = HistoryRepository(db.resultDao(), collector)

        val draws = listOf(
            ResultEntity(issueNumber = "103", number = 8, color = "red", premium = "", sum = 8, blockId = "", blockNumber = "", blockTimestamp = "", receivedAt = 3000L),
            ResultEntity(issueNumber = "102", number = 6, color = "red", premium = "", sum = 6, blockId = "", blockNumber = "", blockTimestamp = "", receivedAt = 2000L),
            ResultEntity(issueNumber = "101", number = 2, color = "red", premium = "", sum = 2, blockId = "", blockNumber = "", blockTimestamp = "", receivedAt = 1000L)
        )
        val summary = repo.computeHistoricalAnalysis(draws)
        assertEquals(3, summary.sampleCount)
        assertEquals("Big streak", summary.recentPattern?.streakLabel)
        assertEquals(2, summary.recentPattern?.streakLength)
        assertEquals(0, summary.numberStats.first { it.number == 8 }.currentGapDraws)
        assertEquals(2, summary.numberStats.first { it.number == 2 }.currentGapDraws)
        networkMonitor.unregister()
    }

    @Test
    fun `api request manager 401 response keeps website session authenticated`() = runBlocking {
        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))
        sessionManager.onBridgeSessionSignal(true, WinGoGameConfig.INITIAL_REFERRAL_URL)

        val fakeAuthProvider = object : ApiAuthenticationProvider {
            override suspend fun getAuthorizedParams(queryParams: Map<String, String>): Result<AuthorizedApiParams> {
                return Result.success(
                    AuthorizedApiParams(
                        bearerAuthorization = "Bearer test_stub",
                        random = "1234",
                        signature = "sig",
                        timestamp = 1_700_000_000L
                    )
                )
            }
        }
        val fakeHistoryApi = object : HistoryApiService {
            override suspend fun getHistoryIssuePage(
                authorization: String,
                origin: String,
                referer: String,
                gameCode: String,
                pageNo: Int,
                pageSize: Int,
                language: String,
                random: String,
                signature: String,
                timestamp: Long
            ): Response<HistoryPageResponse> {
                return Response.error(401, "{}".toResponseBody(null))
            }
        }
        val fakePanelApi = object : GetPanelApiService {
            override suspend fun getPanel(
                authorization: String,
                origin: String,
                referer: String,
                gameCode: String,
                language: String,
                random: String,
                signature: String,
                timestamp: Long
            ): Response<GetPanelResponse> {
                return Response.error(403, "{}".toResponseBody(null))
            }
        }

        val networkMonitor = NetworkConnectivityMonitor(context)
        val apiRequestManager = ApiRequestManager(
            historyApiService = fakeHistoryApi,
            getPanelApiService = fakePanelApi,
            authProvider = fakeAuthProvider,
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )

        val panelOutcome = apiRequestManager.fetchPanel()
        assertTrue(panelOutcome is ApiCallOutcome.SessionExpired || panelOutcome is ApiCallOutcome.NetworkError)
        // Regardless of API failure/403, WebsiteSessionState MUST remain Authenticated
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Authenticated)
        networkMonitor.unregister()
    }

    @Test
    fun `timer sync manager normalizes seconds timestamps and updates timer state before rollover callback`() = runBlocking {
        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))
        val networkMonitor = NetworkConnectivityMonitor(context)
        val fakeAuthProvider = object : ApiAuthenticationProvider {
            override suspend fun getAuthorizedParams(queryParams: Map<String, String>): Result<AuthorizedApiParams> {
                return Result.success(AuthorizedApiParams("Bearer test", "r", "s", 1_700_000_000L))
            }
        }
        val fakeHistoryApi = object : HistoryApiService {
            override suspend fun getHistoryIssuePage(
                authorization: String,
                origin: String,
                referer: String,
                gameCode: String,
                pageNo: Int,
                pageSize: Int,
                language: String,
                random: String,
                signature: String,
                timestamp: Long
            ): Response<HistoryPageResponse> = Response.success(HistoryPageResponse(code = 0))
        }
        val fakePanelApi = object : GetPanelApiService {
            override suspend fun getPanel(
                authorization: String,
                origin: String,
                referer: String,
                gameCode: String,
                language: String,
                random: String,
                signature: String,
                timestamp: Long
            ): Response<GetPanelResponse> {
                return Response.success(
                    GetPanelResponse(
                        code = 0,
                        data = PanelData(
                            phase = "Betting",
                            serverTime = 1_700_000_010L, // seconds
                            startTime = 1_700_000_000L,  // seconds
                            endTime = 1_700_000_060L,    // seconds (50s remaining)
                            issueNumber = "202611140001"
                        )
                    )
                )
            }
        }
        val apiRequestManager = ApiRequestManager(
            historyApiService = fakeHistoryApi,
            getPanelApiService = fakePanelApi,
            authProvider = fakeAuthProvider,
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var callbackObservedIssue: String? = null
        var callbackObservedFreshness: DataFreshnessStatus? = null
        lateinit var timerManager: TimerSyncManager
        timerManager = TimerSyncManager(
            apiRequestManager = apiRequestManager,
            networkMonitor = networkMonitor,
            scope = scope,
            onIssueChanged = { _, _ ->
                callbackObservedIssue = timerManager.timerState.value.issueNumber
                callbackObservedFreshness = timerManager.timerState.value.freshness
            }
        )

        if (networkMonitor.isOnline()) {
            timerManager.synchronizeNow()
            assertEquals("202611140001", callbackObservedIssue)
            assertEquals(DataFreshnessStatus.LIVE, callbackObservedFreshness)
            assertTrue((timerManager.timerState.value.remainingMillis ?: 0L) in 45_000L..50_000L)
        }
        scope.cancel()
        networkMonitor.unregister()
    }

    @Test
    fun `webview domain filter enforces https and approved hosts only`() {
        assertTrue(SecureWebViewManager.isApprovedHttpsUrl(WinGoGameConfig.INITIAL_REFERRAL_URL))
        assertTrue(SecureWebViewManager.isApprovedHttpsUrl("https://m.9kclub5.com/#/home"))
        assertFalse(SecureWebViewManager.isApprovedHttpsUrl("http://9kclub55.com"))
        assertFalse(SecureWebViewManager.isApprovedHttpsUrl("https://evil-example.com/login"))
        assertFalse(SecureWebViewManager.isApprovedHttpsUrl("javascript:alert(1)"))
    }

    @Test
    fun `analysis coordinator initializes all 12 distinct real modules`() {
        val coordinator = AnalysisCoordinator()
        assertEquals(12, coordinator.modules.size)
        assertEquals(12, coordinator.modules.map { it.moduleId }.toSet().size)
    }

    @Test
    fun `floating predictor enforces login activation drag memory minimize and logout hide`() {
        val container = AppContainer(context)
        val viewModel = PredictorViewModel(container)

        // 1. Before login: panel cannot open and WebsiteSessionState is Unknown
        viewModel.openPredictorPanel()
        assertFalse(viewModel.panelUiState.value.isPanelOpen)
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Unknown)

        // 2. Authenticated signal activates floating button & allows opening panel
        container.websiteSessionManager.onBridgeSessionSignal(true, WinGoGameConfig.INITIAL_REFERRAL_URL)
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Authenticated)

        // API rejection must NOT hide floating button or block panel opening
        container.websiteSessionManager.onApiAuthenticationRejected(401, "API unauthorized")
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Authenticated)

        viewModel.updateFloatingButtonOffset(Offset(120f, 240f))
        assertEquals(Offset(120f, 240f), viewModel.panelUiState.value.buttonOffset)

        viewModel.openPredictorPanel()
        assertTrue(viewModel.panelUiState.value.isPanelOpen)

        // 3. Tabs, expansion, and panel drag memory work cleanly
        viewModel.selectPanelTab(PredictorPanelTab.ANALYSIS)
        assertEquals(PredictorPanelTab.ANALYSIS, viewModel.panelUiState.value.activeTab)
        viewModel.selectPanelTab(PredictorPanelTab.HISTORY)
        assertEquals(PredictorPanelTab.HISTORY, viewModel.panelUiState.value.activeTab)
        viewModel.togglePanelExpanded()
        assertTrue(viewModel.panelUiState.value.isExpanded)
        viewModel.updatePanelOffset(Offset(45f, 95f))
        assertEquals(Offset(45f, 95f), viewModel.panelUiState.value.panelOffset)

        // 4. Minimize hides panel while keeping authenticated session (floating A button stays visible)
        viewModel.minimizePredictorPanel()
        assertFalse(viewModel.panelUiState.value.isPanelOpen)
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Authenticated)

        // 5. Logout closes open panel and hides floating A button
        viewModel.openPredictorPanel()
        assertTrue(viewModel.panelUiState.value.isPanelOpen)
        container.websiteSessionManager.onBridgeSessionSignal(false, WinGoGameConfig.INITIAL_REFERRAL_URL)
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Unauthenticated)
        container.networkMonitor.unregister()
    }

    @Test
    fun `login diagnostics accurately report load approved domain unverified SPA state and logout`() {
        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))

        val initial = sessionManager.diagnostics.value
        assertFalse(initial.webViewLoaded)
        assertFalse(initial.isApprovedDomain)
        assertEquals(DiagnosticLoginState.UNKNOWN, initial.loginState)
        assertEquals(DiagnosticSessionCheck.NOT_AVAILABLE, initial.sessionCheck)
        assertEquals(DiagnosticApiAuthStatus.AUTH_REQUIRED, initial.apiAuthStatus)
        assertFalse(initial.floatingButtonVisible)

        // Page finished on approved domain without cookie/bridge -> stays UNKNOWN with explicit reason
        sessionManager.onWebViewNavigationState(WinGoGameConfig.INITIAL_REFERRAL_URL, mainFrameLoaded = true)
        sessionManager.evaluateCookieSession(WinGoGameConfig.INITIAL_REFERRAL_URL, mainFrameLoaded = true)
        val postLoad = sessionManager.diagnostics.value
        assertTrue(postLoad.webViewLoaded)
        assertTrue(postLoad.isApprovedDomain)
        assertEquals(DiagnosticLoginState.UNKNOWN, postLoad.loginState)
        assertEquals(DiagnosticSessionCheck.NOT_AVAILABLE, postLoad.sessionCheck)
        assertFalse(postLoad.floatingButtonVisible)
        assertTrue(postLoad.hidingReason.contains("Registration/URL changes do not confirm login"))

        // Unapproved host fails domain validation
        sessionManager.onWebViewNavigationState("https://evil.example.com", mainFrameLoaded = true)
        val unapproved = sessionManager.diagnostics.value
        assertFalse(unapproved.isApprovedDomain)
        assertEquals(DiagnosticSessionCheck.FAILED, unapproved.sessionCheck)
        assertFalse(unapproved.floatingButtonVisible)

        // Valid bridge signal after approved load activates floating button
        sessionManager.onWebViewNavigationState(WinGoGameConfig.INITIAL_REFERRAL_URL, mainFrameLoaded = true)
        sessionManager.onBridgeSessionSignal(true, WinGoGameConfig.INITIAL_REFERRAL_URL)
        val authenticated = sessionManager.diagnostics.value
        assertEquals(DiagnosticLoginState.AUTHENTICATED, authenticated.loginState)
        assertEquals(DiagnosticSessionCheck.SUCCESS, authenticated.sessionCheck)
        assertTrue(authenticated.floatingButtonVisible)

        // Logout hides floating button and records UNAUTHENTICATED
        sessionManager.markWebsiteLoggedOut()
        val loggedOut = sessionManager.diagnostics.value
        assertEquals(DiagnosticLoginState.UNAUTHENTICATED, loggedOut.loginState)
        assertEquals(DiagnosticSessionCheck.FAILED, loggedOut.sessionCheck)
        assertFalse(loggedOut.floatingButtonVisible)
    }

    @Test
    fun `webview screen renders floating A button and opens panel with API error independently`() {
        val container = AppContainer(context)
        val viewModel = PredictorViewModel(container)

        composeRule.setContent {
            AnshuWingoTheme {
                WebViewScreen(
                    viewModel = viewModel,
                    sessionManager = container.websiteSessionManager,
                    onNavigateToAnalytics = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Initially UNKNOWN -> floating button does not exist; diagnostic panel is displayed
        composeRule.onNodeWithTag("login_diagnostic_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("floating_predictor_button").assertDoesNotExist()

        // Website login succeeds while API is rejected with 401 -> floating button MUST appear
        container.websiteSessionManager.onBridgeSessionSignal(true, WinGoGameConfig.INITIAL_REFERRAL_URL)
        container.websiteSessionManager.onApiAuthenticationRejected(401, "Unauthorized test error")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("floating_predictor_button").assertIsDisplayed()

        // Clicking floating A button opens FloatingPredictorPanel above WebView
        composeRule.onNodeWithTag("floating_predictor_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("floating_predictor_button").assertDoesNotExist()
        composeRule.onNodeWithTag("panel_api_auth_error").assertIsDisplayed()

        // Simulate API returning 401 while panel is open -> panel stays open & displays 401 inside panel
        container.websiteSessionManager.onApiAuthenticationRejected(401, "Unauthorized test error")
        composeRule.waitForIdle()
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Authenticated)
        assertTrue(viewModel.panelUiState.value.isPanelOpen)
        composeRule.onNodeWithTag("panel_api_auth_error").assertIsDisplayed()
        composeRule.onNodeWithText("Unauthorized test error").assertIsDisplayed()

        container.networkMonitor.unregister()
    }

    @Test
    fun `manual activation shows prompt on loaded approved domain keeps website UNKNOWN and supports deactivate and logout reset`() {
        val container = AppContainer(context)
        val viewModel = PredictorViewModel(container)

        composeRule.setContent {
            AnshuWingoTheme {
                WebViewScreen(
                    viewModel = viewModel,
                    sessionManager = container.websiteSessionManager,
                    onNavigateToAnalytics = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Before load completes: prompt is not shown
        composeRule.onNodeWithTag("manual_activation_prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("floating_predictor_button").assertDoesNotExist()

        // Approved 9K Club page loads with WebsiteSessionState.Unknown -> native prompt appears
        container.websiteSessionManager.onWebViewNavigationState(
            url = WinGoGameConfig.INITIAL_REFERRAL_URL,
            mainFrameLoaded = true
        )
        container.websiteSessionManager.evaluateCookieSession(
            url = WinGoGameConfig.INITIAL_REFERRAL_URL,
            mainFrameLoaded = true
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("manual_activation_prompt").assertIsDisplayed()
        composeRule.onNodeWithText("Have you successfully logged into 9K Club?").assertIsDisplayed()
        composeRule.onNodeWithTag("activate_anshu_ai_button").assertIsDisplayed()
        composeRule.onNodeWithTag("dismiss_activation_prompt_button").assertIsDisplayed()

        // Clicking "Not now" dismisses prompt while keeping UserActivationState.INACTIVE & floating button hidden
        composeRule.onNodeWithTag("dismiss_activation_prompt_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("manual_activation_prompt").assertDoesNotExist()
        assertEquals(UserActivationState.INACTIVE, viewModel.userActivationState.value)
        composeRule.onNodeWithTag("floating_predictor_button").assertDoesNotExist()

        // Reset prompt and click "Activate Anshu AI" directly on the prompt card
        viewModel.resetSession()
        container.websiteSessionManager.onWebViewNavigationState(
            url = WinGoGameConfig.INITIAL_REFERRAL_URL,
            mainFrameLoaded = true
        )
        container.websiteSessionManager.evaluateCookieSession(
            url = WinGoGameConfig.INITIAL_REFERRAL_URL,
            mainFrameLoaded = true
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("activate_anshu_ai_button").performClick()
        composeRule.waitForIdle()
        assertEquals(UserActivationState.ACTIVE, viewModel.userActivationState.value)
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Unknown)
        assertTrue(viewModel.apiAuthState.value is ApiAuthenticationState.AuthRequired)
        composeRule.onNodeWithTag("floating_predictor_button").assertIsDisplayed()

        // Opening panel displays unfabricated API auth error banner + Deactivate Anshu AI button
        composeRule.onNodeWithTag("floating_predictor_button").performClick()
        composeRule.waitForIdle()
        assertTrue(viewModel.panelUiState.value.isPanelOpen)
        composeRule.onNodeWithTag("panel_api_auth_error").assertIsDisplayed()
        composeRule.onNodeWithTag("panel_deactivate_anshu_ai_button").performScrollTo().assertIsDisplayed()

        // Minimizing panel brings back floating A button
        composeRule.onNodeWithTag("predictor_panel_minimize_button").performClick()
        composeRule.waitForIdle()
        assertFalse(viewModel.panelUiState.value.isPanelOpen)
        composeRule.onNodeWithTag("floating_predictor_button").assertIsDisplayed()

        // Explicit Deactivate Anshu AI hides floating button & marks INACTIVE
        composeRule.onNodeWithTag("debug_deactivate_anshu_ai_button").performClick()
        composeRule.waitForIdle()
        assertEquals(UserActivationState.INACTIVE, viewModel.userActivationState.value)
        composeRule.onNodeWithTag("floating_predictor_button").assertDoesNotExist()

        // Re-activating, opening panel, and clicking Deactivate inside panel also works cleanly
        composeRule.onNodeWithTag("debug_activate_anshu_ai_button").performClick()
        composeRule.waitForIdle()
        assertEquals(UserActivationState.ACTIVE, viewModel.userActivationState.value)
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            composeRule.onAllNodesWithTag("floating_predictor_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("floating_predictor_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("panel_deactivate_anshu_ai_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(UserActivationState.INACTIVE, viewModel.userActivationState.value)
        assertFalse(viewModel.panelUiState.value.isPanelOpen)
        composeRule.onNodeWithTag("floating_predictor_button").assertDoesNotExist()

        // Receiving an explicit reliable website logout clears activation too
        viewModel.activateManualPredictor()
        composeRule.waitForIdle()
        assertEquals(UserActivationState.ACTIVE, viewModel.userActivationState.value)
        container.websiteSessionManager.onBridgeSessionSignal(false, WinGoGameConfig.INITIAL_REFERRAL_URL)
        composeRule.waitForIdle()
        assertEquals(UserActivationState.INACTIVE, viewModel.userActivationState.value)
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Unauthenticated)
        composeRule.onNodeWithTag("floating_predictor_button").assertDoesNotExist()

        // Application session reset also clears activation & resets state to UNKNOWN
        viewModel.activateManualPredictor()
        assertEquals(UserActivationState.ACTIVE, viewModel.userActivationState.value)
        viewModel.resetSession()
        assertEquals(UserActivationState.INACTIVE, viewModel.userActivationState.value)
        assertTrue(viewModel.websiteSessionState.value is WebsiteSessionState.Unknown)

        container.networkMonitor.unregister()
    }

    private fun buildCapturedGetPanelFixtureJson(): String = """
        {
          "code": 0,
          "msg": "Succeed",
          "msgCode": 0,
          "serviceTime": 1763131804490,
          "data": {
            "phase": "Betting",
            "serverTime": 1763131804481,
            "startTime": 1763131800000,
            "endTime": 1763131860000,
            "issueNumber": "20261114100010500",
            "currentIssueNumber": "20261114100010500",
            "snapshot": {
              "snapNo": 12,
              "list": [
                {"number": 7, "percent": 22, "rank": 1, "medal": 1, "prevRank": 2},
                {"number": 3, "percent": 18, "rank": 2, "medal": 2, "prevRank": 1},
                {"number": 0, "percent": 12, "rank": 3, "medal": 3, "prevRank": 4},
                {"number": 5, "percent": 10, "rank": 4, "medal": 0, "prevRank": 3},
                {"number": 9, "percent": 9, "rank": 5, "medal": 0, "prevRank": 6},
                {"number": 2, "percent": 8, "rank": 6, "medal": 0, "prevRank": 5},
                {"number": 6, "percent": 7, "rank": 7, "medal": 0, "prevRank": 8},
                {"number": 4, "percent": 6, "rank": 8, "medal": 0, "prevRank": 7},
                {"number": 1, "percent": 5, "rank": 9, "medal": 0, "prevRank": 10},
                {"number": 8, "percent": 3, "rank": 10, "medal": 0, "prevRank": 9}
              ]
            },
            "account": {
              "remaining": 40,
              "earned": 60,
              "used": 20,
              "participated": true
            },
            "unlockedNumbers": [7, 3],
            "repeatNumbers": [5],
            "bonusRates": {"1": 1.25, "2": 1.5},
            "firstBetEnergy": 10,
            "earnCap": 500,
            "cumulativeBetAmount": 120,
            "cumulativeBetEnergy": 30,
            "todayJackpots": []
          }
        }
    """.trimIndent()

    @Test
    fun `getPanel fixture parses completely with 60000ms round 55519ms remaining and valid 0-9 snapshot`() {
        val result = GetPanelParser.parseJson(buildCapturedGetPanelFixtureJson())
        assertTrue(result is GetPanelValidationResult.Valid)
        val panel = (result as GetPanelValidationResult.Valid).panel

        assertEquals(0, panel.code)
        assertEquals("Succeed", panel.msg)
        assertEquals(0, panel.msgCode)
        assertEquals(1763131804490L, panel.serviceTimeMs)
        assertEquals(1763131804481L, panel.serverTimeMs)
        assertEquals(1763131800000L, panel.startTimeMs)
        assertEquals(1763131860000L, panel.endTimeMs)
        assertEquals(60_000L, panel.roundDurationMs)
        assertEquals(55_519L, panel.endTimeMs - panel.serverTimeMs)

        assertEquals("20261114100010500", panel.activeIssueNumber)
        assertEquals("20261114100010500", panel.issueNumber)
        assertEquals("20261114100010500", panel.currentIssueNumber)
        assertTrue(panel.issueConsistent)
        assertEquals("Betting", panel.phase)

        assertEquals(12, panel.snapshotNo)
        assertEquals(10, panel.snapshotItems.size)
        assertEquals((0..9).toSet(), panel.snapshotItems.map { it.number }.toSet())
        assertEquals(7, panel.snapshotItems.first().number)
        assertEquals(1, panel.snapshotItems.first().rank)
        assertEquals(22.0, panel.snapshotItems.first().percent, 0.001)
        assertEquals(listOf(7, 3), panel.unlockedNumbers)
        assertEquals(listOf(5), panel.repeatNumbers)

        // Timestamp normalization check (10-digit seconds -> ms, 13-digit ms preserved)
        assertEquals(1_763_131_804_000L, GetPanelParser.normalizeEpochMillis(1_763_131_804L))
        assertEquals(1_763_131_804_481L, GetPanelParser.normalizeEpochMillis(1_763_131_804_481L))
    }

    @Test
    fun `getPanel parser safely rejects missing fields invalid codes bad timer bounds and malformed json`() {
        // Non-zero code
        val nonZeroCode = """{"code": 401, "msg": "Unauthorized", "msgCode": 401}"""
        assertTrue(GetPanelParser.parseJson(nonZeroCode) is GetPanelValidationResult.Invalid)

        // Missing code
        val missingCode = """{"data": {"serverTime": 1763131804481, "startTime": 1763131800000, "endTime": 1763131860000, "issueNumber": "20261114100010500"}}"""
        assertTrue(GetPanelParser.parseJson(missingCode) is GetPanelValidationResult.Invalid)

        // Missing data
        val missingData = """{"code": 0, "msg": "Succeed"}"""
        assertTrue(GetPanelParser.parseJson(missingData) is GetPanelValidationResult.Invalid)

        // Missing endTime
        val missingEndTime = """{"code": 0, "data": {"serverTime": 1763131804481, "startTime": 1763131800000, "issueNumber": "20261114100010500"}}"""
        assertTrue(GetPanelParser.parseJson(missingEndTime) is GetPanelValidationResult.Invalid)

        // Invalid round bounds (endTime <= startTime)
        val badBounds = """{"code": 0, "data": {"serverTime": 1763131804481, "startTime": 1763131860000, "endTime": 1763131800000, "issueNumber": "20261114100010500"}}"""
        assertTrue(GetPanelParser.parseJson(badBounds) is GetPanelValidationResult.Invalid)

        // Missing issue numbers
        val missingIssue = """{"code": 0, "data": {"serverTime": 1763131804481, "startTime": 1763131800000, "endTime": 1763131860000}}"""
        assertTrue(GetPanelParser.parseJson(missingIssue) is GetPanelValidationResult.Invalid)

        // Malformed JSON syntax
        assertTrue(GetPanelParser.parseJson("{not-valid-json") is GetPanelValidationResult.Invalid)

        // Issue inconsistency flagged cleanly without crashing
        val mismatchIssues = """
            {
              "code": 0,
              "data": {
                "phase": "Lock",
                "serverTime": 1763131804481,
                "startTime": 1763131800000,
                "endTime": 1763131860000,
                "issueNumber": "20261114100010500",
                "currentIssueNumber": "20261114100010501",
                "snapshot": {
                  "snapNo": 4,
                  "list": [
                    {"number": 99, "percent": 50, "rank": 1, "medal": 0, "prevRank": 1},
                    {"number": 2, "percent": 15, "rank": 2, "medal": 1, "prevRank": null}
                  ]
                }
              }
            }
        """.trimIndent()
        val mismatchParsed = GetPanelParser.parseJson(mismatchIssues) as GetPanelValidationResult.Valid
        assertFalse(mismatchParsed.panel.issueConsistent)
        assertEquals("20261114100010501", mismatchParsed.panel.activeIssueNumber)
        // Out-of-range snapshot number 99 filtered out safely; valid number 2 kept
        assertEquals(listOf(2), mismatchParsed.panel.snapshotItems.map { it.number })
    }

    @Test
    fun `timer sync manager computes 55519ms remaining 60000ms duration clock offset monotonic countdown and never inserts fixture into history`() = runBlocking {
        val parsedFixture = GetPanelParser.buildMoshi()
            .adapter(GetPanelResponse::class.java)
            .fromJson(buildCapturedGetPanelFixtureJson())!!

        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))
        val networkMonitor = NetworkConnectivityMonitor(context)

        var fakeElapsedMs = 10_000L
        val fakeWallMs = 1_763_131_804_000L // 481ms behind serverTime -> serverClockOffsetMs = +481ms
        var panelFetchCount = 0
        var activeResponse = parsedFixture

        val fakeAuthProvider = object : ApiAuthenticationProvider {
            override suspend fun getAuthorizedParams(queryParams: Map<String, String>): Result<AuthorizedApiParams> {
                return Result.success(AuthorizedApiParams("Bearer test", "rnd", "sig", 1_763_131_804L))
            }
        }
        val fakeHistoryApi = object : HistoryApiService {
            override suspend fun getHistoryIssuePage(
                authorization: String, origin: String, referer: String, gameCode: String,
                pageNo: Int, pageSize: Int, language: String, random: String, signature: String, timestamp: Long
            ): Response<HistoryPageResponse> = Response.success(HistoryPageResponse(code = 0))
        }
        val fakePanelApi = object : GetPanelApiService {
            override suspend fun getPanel(
                authorization: String, origin: String, referer: String, gameCode: String,
                language: String, random: String, signature: String, timestamp: Long
            ): Response<GetPanelResponse> {
                panelFetchCount++
                return Response.success(activeResponse)
            }
        }
        val apiRequestManager = ApiRequestManager(
            historyApiService = fakeHistoryApi,
            getPanelApiService = fakePanelApi,
            authProvider = fakeAuthProvider,
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val observedIssues = mutableListOf<String>()
        val observedPhases = mutableListOf<String>()
        val timerManager = TimerSyncManager(
            apiRequestManager = apiRequestManager,
            networkMonitor = networkMonitor,
            scope = scope,
            onIssueChanged = { newIssue, _ -> observedIssues.add(newIssue) },
            onPhaseChanged = { newPhase, _ -> observedPhases.add(newPhase) },
            elapsedRealtimeProvider = { fakeElapsedMs },
            wallTimeProvider = { fakeWallMs }
        )

        if (networkMonitor.isOnline()) {
            timerManager.synchronizeNow()
            val s0 = timerManager.timerState.value
            assertEquals("20261114100010500", s0.issueNumber)
            assertEquals("Betting", s0.phase)
            assertEquals(60_000L, s0.roundDurationMillis)
            assertEquals(55_519L, s0.remainingMillis)
            assertEquals("00:55", s0.formattedCountdown)
            assertEquals(481L, s0.serverClockOffsetMs)
            assertEquals(490L, s0.serviceTimeOffsetMs)
            assertEquals(9L, s0.serviceToServerDeltaMs)
            assertEquals(DataFreshnessStatus.LIVE, s0.freshness)
            assertEquals(12, s0.snapshotNo)
            assertEquals(10, s0.snapshotItems.size)
            assertEquals(listOf("20261114100010500"), observedIssues)

            // Monotonic tick advances locally (+1s) WITHOUT calling API every second
            fakeElapsedMs += 1_000L
            timerManager.tickOnce()
            assertEquals(54_519L, timerManager.timerState.value.remainingMillis)
            assertEquals("00:54", timerManager.timerState.value.formattedCountdown)
            assertEquals(1, panelFetchCount)

            // Phase change detection on periodic refresh (+20s)
            activeResponse = parsedFixture.copy(
                data = parsedFixture.data?.copy(phase = "Locked", serverTime = 1763131824481L)
            )
            fakeElapsedMs += 20_000L
            timerManager.tickOnce()
            assertEquals(2, panelFetchCount)
            assertEquals(listOf("Locked"), observedPhases)
            assertEquals("Locked", timerManager.timerState.value.phase)

            // Deadline expiration detection
            fakeElapsedMs += 40_000L
            activeResponse = parsedFixture.copy(
                data = parsedFixture.data?.copy(serverTime = 1763131865000L) // already past endTime
            )
            timerManager.tickOnce()
            assertTrue(timerManager.timerState.value.isDeadlineExpired)
            assertEquals(0L, timerManager.timerState.value.remainingMillis)
            assertEquals(DataFreshnessStatus.STALE, timerManager.timerState.value.freshness)
        }

        // Captured fixture MUST NEVER be inserted into production history database
        assertEquals(0, db.resultDao().getResultCount())

        scope.cancel()
        networkMonitor.unregister()
    }

    @Test
    fun `floating predictor panel displays synchronized GetPanel details snapshot and exact auth error when missing`() {
        val syncedTimer = TimerUiState(
            issueNumber = "20261114100010500",
            currentIssueNumber = "20261114100010500",
            issueConsistent = true,
            phase = "Betting",
            remainingMillis = 55_519L,
            roundDurationMillis = 60_000L,
            formattedCountdown = "00:55",
            freshness = DataFreshnessStatus.LIVE,
            statusDetail = "Synchronized with GetPanel (RTT 0ms, offset +481ms)",
            lastSyncedAtMillis = 1_763_131_804_481L,
            snapshotNo = 12,
            snapshotItems = listOf(
                ValidatedSnapshotItem(number = 7, percent = 22.0, rank = 1, medal = 1, prevRank = 2),
                ValidatedSnapshotItem(number = 3, percent = 18.0, rank = 2, medal = 2, prevRank = 1)
            )
        )

        var currentTimerState by mutableStateOf(syncedTimer)
        var currentAuthState by mutableStateOf<ApiAuthenticationState>(ApiAuthenticationState.Ready)

        composeRule.setContent {
            AnshuWingoTheme {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val widthPx = with(density) { maxWidth.toPx() }
                    val heightPx = with(density) { maxHeight.toPx() }
                    FloatingPredictorPanel(
                        containerWidthPx = widthPx,
                        containerHeightPx = heightPx,
                        panelUiState = PredictorPanelUiState(isPanelOpen = true, activeTab = PredictorPanelTab.PREDICTION),
                        networkStatus = com.example.domain.model.NetworkStatus.Online,
                        timerState = currentTimerState,
                        predictionResult = null,
                        trainingSnapshot = ModelTrainingSnapshot(historySampleSize = 0),
                        apiAuthState = currentAuthState,
                        lastSyncMessage = null,
                        lastLockNote = null,
                        isEvaluating = false,
                        historicalAnalysis = com.example.data.repository.HistoricalAnalysisSummary.Empty,
                        moduleSummaries = emptyList(),
                        moduleStatuses = emptyMap(),
                        historyResults = emptyList(),
                        historyPredictions = emptyList(),
                        onDragOffset = {},
                        onSelectTab = {},
                        onToggleExpand = {},
                        onMinimize = {},
                        onRefreshSync = {},
                        onOpenAnalytics = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("panel_issue_number").assertIsDisplayed()
        composeRule.onNodeWithText("20261114100010500").assertIsDisplayed()
        composeRule.onNodeWithText("00:55").assertIsDisplayed()
        composeRule.onNodeWithText("Phase: Betting").assertIsDisplayed()
        composeRule.onNodeWithText("API CONNECTED").assertIsDisplayed()
        composeRule.onNodeWithText("Data freshness: Live Synchronized").assertIsDisplayed()
        composeRule.onNodeWithTag("panel_last_sync").assertIsDisplayed()
        composeRule.onNodeWithTag("panel_getpanel_snapshot").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("GetPanel Snapshot (#12)").assertIsDisplayed()
        composeRule.onNodeWithText("#1 Num 7").assertIsDisplayed()
        composeRule.onNodeWithText("22%").assertIsDisplayed()

        // When API auth is missing, show LOGIN REQUIRED + exact error and never display a fabricated countdown
        currentAuthState = ApiAuthenticationState.AuthRequired(
            missingBearerToken = true,
            missingSignatureAlgorithm = true,
            details = "Missing Bearer token and dynamic signature algorithm."
        )
        currentTimerState = TimerUiState(
            freshness = DataFreshnessStatus.AWAITING_AUTH,
            formattedCountdown = "--:--",
            statusDetail = "Awaiting API authentication."
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("panel_api_auth_error").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("LOGIN REQUIRED").assertIsDisplayed()
        composeRule.onNodeWithText("Missing Bearer token and dynamic signature algorithm.").assertIsDisplayed()
        composeRule.onNodeWithTag("panel_countdown").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("--:--").assertIsDisplayed()

        // When API auth is rejected (401), show AUTHORIZATION REJECTED while keeping UI usable
        currentAuthState = ApiAuthenticationState.Rejected(
            httpCode = 401,
            reason = "Upstream rejected expired Bearer credential."
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUTHORIZATION REJECTED").assertIsDisplayed()
        composeRule.onNodeWithText("Upstream rejected expired Bearer credential.").assertIsDisplayed()
    }

    @Test
    fun `root serviceTime acts as fallback when data serverTime is null and API_REFERER has trailing slash`() = runBlocking {
        assertEquals("https://9kclub5.com/", WinGoGameConfig.API_REFERER)

        val jsonWithNullDataServerTime = """
            {
              "code": 0,
              "msg": "Succeed",
              "msgCode": 0,
              "serviceTime": 1763131804481,
              "data": {
                "phase": "Betting",
                "serverTime": null,
                "startTime": 1763131800000,
                "endTime": 1763131860000,
                "issueNumber": "20261114100010500",
                "currentIssueNumber": "20261114100010500",
                "snapshot": {
                  "snapNo": 12,
                  "list": [
                    {"number": 7, "percent": 22, "rank": 1, "medal": 1, "prevRank": 2}
                  ]
                }
              }
            }
        """.trimIndent()

        val parsed = GetPanelParser.parseJson(jsonWithNullDataServerTime)
        assertTrue(parsed is GetPanelValidationResult.Valid)
        val panel = (parsed as GetPanelValidationResult.Valid).panel
        assertTrue(panel.usedServiceTimeFallback)
        assertEquals(1763131804481L, panel.serverTimeMs)
        assertEquals(60_000L, panel.roundDurationMs)
        assertEquals(55_519L, panel.endTimeMs - panel.serverTimeMs)
        assertEquals(12, panel.snapshotNo)
        assertEquals(1, panel.snapshotItems.size)
    }

    @Test
    fun `auth architecture contracts and backend proxy stay non-operational until configured`() = runBlocking {
        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))
        val networkMonitor = NetworkConnectivityMonitor(context)

        // 1. Unconfigured provider blocks requests and reports all missing contracts cleanly
        val unconfigured = UnconfiguredApiAuthenticationProvider(sessionManager)
        assertFalse(unconfigured.isCredentialConfigured())
        assertFalse(unconfigured.isSignatureAlgorithmConfigured())
        assertFalse(unconfigured.isRefreshSupported())
        assertTrue(unconfigured.getBearerAuthorization().isFailure)
        assertTrue(unconfigured.signRequest(emptyMap()).isFailure)
        assertTrue(unconfigured.refreshBearerAuthorization("old").isFailure)

        // 2. CompositeApiAuthenticationProvider composes credential + signature providers and retries once on refresh
        var currentBearer = "Bearer expired_token"
        val credProvider = object : AuthorizedApiCredentialProvider {
            override suspend fun getBearerAuthorization(): Result<String> = Result.success(currentBearer)
            override suspend fun isCredentialConfigured(): Boolean = true
        }
        val sigProvider = object : ApiSignatureProvider {
            override fun isSignatureAlgorithmConfigured(): Boolean = true
            override suspend fun signRequest(queryParams: Map<String, String>): Result<SignedRequestParams> =
                Result.success(SignedRequestParams("rnd1", "sig1", 1_763_131_804L))
        }
        val refreshProvider = object : ApiTokenRefreshProvider {
            override fun isRefreshSupported(): Boolean = true
            override suspend fun refreshBearerAuthorization(expiredAuthorization: String?): Result<String> {
                currentBearer = "Bearer fresh_token"
                return Result.success(currentBearer)
            }
        }
        val compositeAuth = CompositeApiAuthenticationProvider(credProvider, sigProvider)
        var panelAttempts = 0
        val retryPanelApi = object : GetPanelApiService {
            override suspend fun getPanel(
                authorization: String, origin: String, referer: String, gameCode: String,
                language: String, random: String, signature: String, timestamp: Long
            ): Response<GetPanelResponse> {
                panelAttempts++
                return if (authorization == "Bearer expired_token") {
                    Response.error(401, "{}".toResponseBody(null))
                } else {
                    Response.success(GetPanelResponse(code = 0, msg = "Succeed"))
                }
            }
        }
        val retryHistoryApi = object : HistoryApiService {
            override suspend fun getHistoryIssuePage(
                authorization: String, origin: String, referer: String, gameCode: String,
                pageNo: Int, pageSize: Int, language: String, random: String, signature: String, timestamp: Long
            ): Response<HistoryPageResponse> = Response.success(HistoryPageResponse(code = 0))
        }
        val requestManager = ApiRequestManager(
            historyApiService = retryHistoryApi,
            getPanelApiService = retryPanelApi,
            authProvider = compositeAuth,
            sessionManager = sessionManager,
            networkMonitor = networkMonitor,
            tokenRefreshProvider = refreshProvider
        )
        if (networkMonitor.isOnline()) {
            val outcome = requestManager.fetchPanel()
            assertTrue(outcome is ApiCallOutcome.Success)
            assertEquals(2, panelAttempts)
        }

        // 3. BackendProxyApiRequestManager refuses unconfigured / non-HTTPS URLs without claiming operational
        val unconfiguredProxy = BackendProxyApiRequestManager(
            config = BackendProxyConfig(baseUrl = "http://insecure.example.com"),
            serviceProvider = { error("Should not build service for non-HTTPS URL") },
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )
        assertTrue(unconfiguredProxy.integrationStatus is BackendIntegrationStatus.NotConfigured)
        assertTrue(unconfiguredProxy.fetchPanel() is ApiCallOutcome.MissingAuth)

        // 4. Configured HTTPS proxy delegates GET /api/wingo/v1/panel & /api/wingo/v1/history cleanly
        val fakeProxyService = object : AuthorizedBackendProxyService {
            override suspend fun getPanel(
                appClientAuthorization: String?, gameCode: String, language: String
            ): Response<GetPanelResponse> {
                val parsed = GetPanelParser.buildMoshi()
                    .adapter(GetPanelResponse::class.java)
                    .fromJson(buildCapturedGetPanelFixtureJson())!!
                return Response.success(parsed)
            }

            override suspend fun getHistory(
                appClientAuthorization: String?, gameCode: String, pageNo: Int, pageSize: Int, language: String
            ): Response<HistoryPageResponse> = Response.success(HistoryPageResponse(code = 0))
        }
        val configuredProxy = BackendProxyApiRequestManager(
            config = BackendProxyConfig(baseUrl = "https://proxy.example.com"),
            serviceProvider = { fakeProxyService },
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )
        assertTrue(configuredProxy.integrationStatus is BackendIntegrationStatus.ConfiguredAwaitingVerification)
        if (networkMonitor.isOnline()) {
            val proxyPanel = configuredProxy.fetchPanel()
            assertTrue(proxyPanel is ApiCallOutcome.Success)
            val proxyHistory = configuredProxy.fetchHistoryPage(1)
            assertTrue(proxyHistory is ApiCallOutcome.Success)
        }

        networkMonitor.unregister()
    }

    @Test
    fun `verified GetPanel Betting snapNo 1 and Locked snapNo -1 fixtures deserialize and drive TimerSyncManager safely`() = runBlocking {
        // Test-only Fixture 1: Betting phase with snapNo = 1 and bonusRates = [1.0, 0.8, 0.5]
        val bettingFixtureJson = """
            {
              "code": 0,
              "msg": "Succeed",
              "msgCode": 0,
              "serviceTime": 1794667984490,
              "data": {
                "phase": "Betting",
                "serverTime": 1794667984481,
                "startTime": 1794667980000,
                "endTime": 1794668040000,
                "issueNumber": "20261114100010894",
                "currentIssueNumber": "20261114100010894",
                "snapshot": {
                  "snapNo": 1,
                  "list": [
                    {"number": 7, "percent": 20, "rank": 1, "medal": 1, "prevRank": 2},
                    {"number": 3, "percent": 15, "rank": 2, "medal": 2, "prevRank": 1},
                    {"number": 0, "percent": 12, "rank": 3, "medal": 3, "prevRank": 4},
                    {"number": 5, "percent": 11, "rank": 4, "medal": 0, "prevRank": 3},
                    {"number": 9, "percent": 10, "rank": 5, "medal": 0, "prevRank": 6},
                    {"number": 2, "percent": 9, "rank": 6, "medal": 0, "prevRank": 5},
                    {"number": 6, "percent": 8, "rank": 7, "medal": 0, "prevRank": 8},
                    {"number": 4, "percent": 7, "rank": 8, "medal": 0, "prevRank": 7},
                    {"number": 1, "percent": 5, "rank": 9, "medal": 0, "prevRank": 10},
                    {"number": 8, "percent": 3, "rank": 10, "medal": 0, "prevRank": 9}
                  ]
                },
                "account": {"remaining": 30, "earned": 50, "used": 20, "participated": true},
                "unlockedNumbers": [7, 3],
                "repeatNumbers": [5],
                "bonusRates": [1.0, 0.8, 0.5],
                "firstBetEnergy": 10,
                "earnCap": 500,
                "cumulativeBetAmount": 100,
                "cumulativeBetEnergy": 25,
                "todayJackpots": []
              }
            }
        """.trimIndent()

        // Test-only Fixture 2: Locked phase with snapNo = -1
        val lockedFixtureJson = """
            {
              "code": 0,
              "msg": "Succeed",
              "msgCode": 0,
              "serviceTime": 1794670017530,
              "data": {
                "phase": "Locked",
                "serverTime": 1794670017525,
                "startTime": 1794669960000,
                "endTime": 1794670020000,
                "issueNumber": "20261114100010927",
                "currentIssueNumber": "20261114100010927",
                "snapshot": {
                  "snapNo": -1,
                  "list": [
                    {"number": 7, "percent": 20, "rank": 1, "medal": 1, "prevRank": 1},
                    {"number": 3, "percent": 15, "rank": 2, "medal": 2, "prevRank": 2}
                  ]
                },
                "unlockedNumbers": [],
                "repeatNumbers": [],
                "bonusRates": [1.0, 0.8, 0.5],
                "todayJackpots": []
              }
            }
        """.trimIndent()

        val bettingRes = GetPanelParser.parseJson(bettingFixtureJson)
        assertTrue(bettingRes is GetPanelValidationResult.Valid)
        val bettingPanel = (bettingRes as GetPanelValidationResult.Valid).panel
        assertEquals("Betting", bettingPanel.phase)
        assertEquals(1, bettingPanel.snapshotNo)
        assertEquals("20261114100010894", bettingPanel.activeIssueNumber)
        assertEquals(60_000L, bettingPanel.roundDurationMs)
        assertEquals(55_519L, bettingPanel.endTimeMs - bettingPanel.serverTimeMs)

        val lockedRes = GetPanelParser.parseJson(lockedFixtureJson)
        assertTrue(lockedRes is GetPanelValidationResult.Valid)
        val lockedPanel = (lockedRes as GetPanelValidationResult.Valid).panel
        assertEquals("Locked", lockedPanel.phase)
        assertEquals(-1, lockedPanel.snapshotNo)
        assertEquals("20261114100010927", lockedPanel.activeIssueNumber)
        assertEquals(60_000L, lockedPanel.roundDurationMs)
        assertEquals(2_475L, lockedPanel.endTimeMs - lockedPanel.serverTimeMs)

        // Feed Fixture 2 into TimerSyncManager -> verifies "Locked", snapNo=-1, and 00:02 countdown
        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))
        val networkMonitor = NetworkConnectivityMonitor(context)
        val moshiAdapter = GetPanelParser.buildMoshi().adapter(GetPanelResponse::class.java)
        val lockedDto = moshiAdapter.fromJson(lockedFixtureJson)!!
        val apiRequestManager = ApiRequestManager(
            historyApiService = object : HistoryApiService {
                override suspend fun getHistoryIssuePage(
                    authorization: String, origin: String, referer: String, gameCode: String,
                    pageNo: Int, pageSize: Int, language: String, random: String, signature: String, timestamp: Long
                ): Response<HistoryPageResponse> = Response.success(HistoryPageResponse(code = 0))
            },
            getPanelApiService = object : GetPanelApiService {
                override suspend fun getPanel(
                    authorization: String, origin: String, referer: String, gameCode: String,
                    language: String, random: String, signature: String, timestamp: Long
                ): Response<GetPanelResponse> = Response.success(lockedDto)
            },
            authProvider = object : ApiAuthenticationProvider {
                override suspend fun getAuthorizedParams(queryParams: Map<String, String>): Result<AuthorizedApiParams> =
                    Result.success(AuthorizedApiParams("Bearer test", "r", "s", 1794670017L))
            },
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val timerManager = TimerSyncManager(
            apiRequestManager = apiRequestManager,
            networkMonitor = networkMonitor,
            scope = scope,
            elapsedRealtimeProvider = { 5_000L },
            wallTimeProvider = { 1794670017500L }
        )
        if (networkMonitor.isOnline()) {
            timerManager.synchronizeNow()
            val state = timerManager.timerState.value
            assertEquals("20261114100010927", state.issueNumber)
            assertEquals("Locked", state.phase)
            assertEquals(-1, state.snapshotNo)
            assertEquals(2_475L, state.remainingMillis)
            assertEquals("00:02", state.formattedCountdown)
            assertEquals(DataFreshnessStatus.LIVE, state.freshness)
        }
        assertEquals(0, db.resultDao().getResultCount())
        scope.cancel()
        networkMonitor.unregister()
    }

    @Test
    fun `history collector paginates deduplicates filters malformed records stores source and enforces 500 retention`() = runBlocking {
        val sessionManager = WebsiteSessionManager(KeystoreCryptoManager(context))
        val networkMonitor = NetworkConnectivityMonitor(context)

        val paginatedHistoryApi = object : HistoryApiService {
            override suspend fun getHistoryIssuePage(
                authorization: String, origin: String, referer: String, gameCode: String,
                pageNo: Int, pageSize: Int, language: String, random: String, signature: String, timestamp: Long
            ): Response<HistoryPageResponse> {
                val baseIdx = (pageNo - 1) * 10
                val list = mutableListOf<HistoryRecordDto>()
                for (offset in 0 until 10) {
                    val seq = 600 - (baseIdx + offset)
                    if (seq <= 0) break
                    val digit = seq % 10
                    list.add(
                        HistoryRecordDto(
                            issueNumber = "202611141000%05d".format(seq),
                            number = digit,
                            color = WinGoColor.primaryFromNumber(digit).label.lowercase(),
                            premium = "0",
                            sum = digit,
                            blockId = "blk_$seq",
                            blockNumber = "$seq",
                            blockTimestamp = "2026-11-14 07:00:00"
                        )
                    )
                }
                // Inject overlapping duplicate from page 1 and malformed entries on page 2
                if (pageNo == 2) {
                    list.add(
                        HistoryRecordDto(
                            issueNumber = "20261114100000600",
                            number = 0,
                            color = "red,violet"
                        )
                    )
                    list.add(HistoryRecordDto(issueNumber = "BAD", number = 99, color = "red"))
                    list.add(HistoryRecordDto(issueNumber = "", number = 4, color = "red"))
                    list.add(HistoryRecordDto(issueNumber = "20261114100009999", number = null, color = "green"))
                }
                return Response.success(
                    HistoryPageResponse(
                        code = 0,
                        msg = "Succeed",
                        data = HistoryPageData(
                            pageNo = pageNo,
                            totalPage = 55,
                            totalCount = 600,
                            list = list
                        )
                    )
                )
            }
        }

        val apiRequestManager = ApiRequestManager(
            historyApiService = paginatedHistoryApi,
            getPanelApiService = object : GetPanelApiService {
                override suspend fun getPanel(
                    authorization: String, origin: String, referer: String, gameCode: String,
                    language: String, random: String, signature: String, timestamp: Long
                ): Response<GetPanelResponse> = Response.success(GetPanelResponse(code = 0))
            },
            authProvider = object : ApiAuthenticationProvider {
                override suspend fun getAuthorizedParams(queryParams: Map<String, String>): Result<AuthorizedApiParams> =
                    Result.success(AuthorizedApiParams("Bearer test", "rnd", "sig", 1794670000L))
            },
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )

        val collector = HistoryCollector(apiRequestManager, db.resultDao(), networkMonitor)
        if (networkMonitor.isOnline()) {
            val sync = collector.collectLatest500()
            assertTrue(sync is HistorySyncResult.Success)
            val success = sync as HistorySyncResult.Success
            assertEquals(WinGoGameConfig.MAX_HISTORY_RESULTS, success.collectedUniqueCount)
            assertEquals(WinGoGameConfig.MAX_HISTORY_RESULTS, db.resultDao().getResultCount())
            val stored = db.resultDao().getLatestResultsSnapshot(WinGoGameConfig.MAX_HISTORY_RESULTS)
            assertEquals("20261114100000600", stored.first().issueNumber)
            assertEquals("20261114100000101", stored.last().issueNumber)
            assertTrue(stored.all { it.source == "GetHistoryIssuePage" })
            assertTrue(stored.none { it.issueNumber == "BAD" || it.issueNumber == "20261114100009999" })
        }

        // Unconfigured auth must immediately stop without touching DB or creating fake records
        val unconfiguredCollector = HistoryCollector(
            apiRequestManager = ApiRequestManager(
                historyApiService = paginatedHistoryApi,
                getPanelApiService = RetrofitClient.createGetPanelApiService(RetrofitClient.createRetrofit()),
                authProvider = UnconfiguredApiAuthenticationProvider(sessionManager),
                sessionManager = sessionManager,
                networkMonitor = networkMonitor
            ),
            resultDao = db.resultDao(),
            networkMonitor = networkMonitor
        )
        if (networkMonitor.isOnline()) {
            val authFail = unconfiguredCollector.collectLatest500()
            assertTrue(authFail is HistorySyncResult.AuthUnavailable)
        }
        networkMonitor.unregister()
    }

    @Test
    fun `database MIGRATION_2_3 preserves actual_results rows and adds source column`() {
        val dbName = "migration_test_${System.nanoTime()}.db"
        try {
            val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS actual_results (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    issueNumber TEXT NOT NULL,
                                    number INTEGER NOT NULL,
                                    color TEXT NOT NULL,
                                    premium TEXT NOT NULL,
                                    sum INTEGER NOT NULL,
                                    blockId TEXT NOT NULL,
                                    blockNumber TEXT NOT NULL,
                                    blockTimestamp TEXT NOT NULL,
                                    receivedAt INTEGER NOT NULL
                                )
                                """.trimIndent()
                            )
                            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_actual_results_issueNumber ON actual_results(issueNumber)")
                            db.execSQL(
                                """
                                INSERT INTO actual_results (issueNumber, number, color, premium, sum, blockId, blockNumber, blockTimestamp, receivedAt)
                                VALUES ('202611140001', 7, 'green', '0', 7, 'b1', '1', '1700000000', 1700000000000)
                                """.trimIndent()
                            )
                        }

                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build()
            )
            val v2Db = helper.writableDatabase
            AppDatabase.MIGRATION_2_3.migrate(v2Db)
            val cursor = v2Db.query("SELECT issueNumber, number, source FROM actual_results WHERE issueNumber = '202611140001'")
            assertTrue(cursor.moveToFirst())
            assertEquals("202611140001", cursor.getString(0))
            assertEquals(7, cursor.getInt(1))
            assertEquals("", cursor.getString(2))
            cursor.close()
            v2Db.close()
            helper.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun createTestKeystoreCryptoManager(): KeystoreCryptoManager {
        val rawKey = ByteArray(32) { idx -> (idx + 11).toByte() }
        val secretKey = javax.crypto.spec.SecretKeySpec(rawKey, "AES")
        val testProvider = CryptoKeyProvider { secretKey }
        return KeystoreCryptoManager(context, testProvider)
    }

    @Test
    fun `cooperative bridge session exchange encrypts token via AES-GCM validates origin and clears on logout or expiry`() = runBlocking {
        var nowMs = 1_794_670_000_000L
        val cryptoManager = createTestKeystoreCryptoManager()
        cryptoManager.clearAll()
        val sessionManager = WebsiteSessionManager(cryptoManager, wallTimeProvider = { nowMs })

        // 1. Untrusted origin exchange MUST be rejected
        var currentUrl = "https://untrusted.example.com/#/home"
        var mainFrameLoaded = true
        val bridge = WebsiteSessionBridge(
            sessionManager = sessionManager,
            currentUrlProvider = { currentUrl },
            isMainFrameLoadedProvider = { mainFrameLoaded },
            mainThreadExecutor = { action -> action() }
        )
        bridge.onAuthorizedSessionExchange("session_grant_value_1", nowMs + 60_000L)
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Unknown)
        assertEquals(null, sessionManager.getStoredTokenOrNull())

        // 2. Approved origin main-frame exchange stores encrypted credential and sets Authenticated
        currentUrl = "https://9kclub55.com/#/home"
        bridge.onAuthorizedSessionExchange("session_grant_value_2", nowMs + 60_000L)
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Authenticated)
        assertTrue(cryptoManager.hasEncryptedEntry(WebsiteSessionManager.KEY_API_TOKEN))
        val rawCipher = cryptoManager.getRawStoredValueForAudit(WebsiteSessionManager.KEY_API_TOKEN)
        assertNotNull(rawCipher)
        assertFalse(rawCipher!!.contains("session_grant_value_2"))

        val credProvider = KeystoreBackedCredentialProvider(sessionManager)
        assertTrue(credProvider.isCredentialConfigured())
        assertEquals("Bearer session_grant_value_2", credProvider.getBearerAuthorization().getOrNull())

        // Bridge exposes zero getter methods back to JS
        val jsMethodNames = WebsiteSessionBridge::class.java.declaredMethods
            .filter { it.isAnnotationPresent(android.webkit.JavascriptInterface::class.java) }
            .map { it.name }
        assertTrue(jsMethodNames.none { it.startsWith("get") || it.contains("Token", ignoreCase = true) && it != "onAuthorizedSessionExchange" })

        // 3. Token expiry automatically clears encrypted storage and transitions to TOKEN EXPIRED
        nowMs += 65_000L
        assertEquals(null, sessionManager.getStoredTokenOrNull())
        assertTrue(sessionManager.apiAuthState.value is ApiAuthenticationState.TokenExpired)
        assertEquals(DiagnosticApiAuthStatus.TOKEN_EXPIRED, sessionManager.diagnostics.value.apiAuthStatus)
        assertFalse(cryptoManager.hasEncryptedEntry(WebsiteSessionManager.KEY_API_TOKEN))

        // 4. Logout clears any newly stored credential and resets website state to UNAUTHENTICATED
        bridge.onAuthorizedSessionExchange("session_grant_value_3", nowMs + 120_000L)
        assertTrue(credProvider.isCredentialConfigured())
        bridge.onSessionStatusChanged(false)
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Unauthenticated)
        assertFalse(cryptoManager.hasEncryptedEntry(WebsiteSessionManager.KEY_API_TOKEN))
        assertEquals(DiagnosticApiAuthStatus.AUTH_REQUIRED, sessionManager.diagnostics.value.apiAuthStatus)
    }

    @Test
    fun `refresh failure and signature provider failure block requests cleanly without logging out website user`() = runBlocking {
        val nowMs = 1_794_670_000_000L
        val cryptoManager = createTestKeystoreCryptoManager()
        cryptoManager.clearAll()
        val sessionManager = WebsiteSessionManager(cryptoManager, wallTimeProvider = { nowMs })
        val networkMonitor = NetworkConnectivityMonitor(context)

        // Cooperative token present, but ApiSignatureProvider is unconfigured -> SIGNATURE NOT CONFIGURED
        assertTrue(sessionManager.onAuthorizedSessionExchange("token_abc", nowMs + 60_000L, "https://9kclub55.com/#/home"))
        val credProvider = KeystoreBackedCredentialProvider(sessionManager)
        val unconfiguredSig = object : ApiSignatureProvider {
            override fun isSignatureAlgorithmConfigured(): Boolean = false
            override suspend fun signRequest(queryParams: Map<String, String>): Result<SignedRequestParams> =
                Result.failure(IllegalStateException("Not configured"))
        }
        val compositeMissingSig = CompositeApiAuthenticationProvider(credProvider, unconfiguredSig)
        val noSigManager = ApiRequestManager(
            historyApiService = RetrofitClient.createHistoryApiService(RetrofitClient.createRetrofit()),
            getPanelApiService = RetrofitClient.createGetPanelApiService(RetrofitClient.createRetrofit()),
            authProvider = compositeMissingSig,
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )
        if (networkMonitor.isOnline()) {
            val missingSigOutcome = noSigManager.fetchPanel()
            assertTrue(missingSigOutcome is ApiCallOutcome.MissingAuth)
            assertTrue(sessionManager.apiAuthState.value is ApiAuthenticationState.SignatureNotConfigured)
            assertEquals(DiagnosticApiAuthStatus.SIGNATURE_NOT_CONFIGURED, sessionManager.diagnostics.value.apiAuthStatus)
            assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Authenticated)
        }

        // Refresh failure after 401 clears stored token, sets TOKEN EXPIRED, and leaves WebsiteSessionState intact
        val validSig = object : ApiSignatureProvider {
            override fun isSignatureAlgorithmConfigured(): Boolean = true
            override suspend fun signRequest(queryParams: Map<String, String>): Result<SignedRequestParams> =
                Result.success(SignedRequestParams("r", "s", 1794670000L))
        }
        var refreshAttempts = 0
        val failingRefresh = object : ApiTokenRefreshProvider {
            override fun isRefreshSupported(): Boolean = true
            override suspend fun refreshBearerAuthorization(expiredAuthorization: String?): Result<String> {
                refreshAttempts++
                return Result.failure(IllegalStateException("Refresh grant rejected by upstream."))
            }
        }
        val always401Panel = object : GetPanelApiService {
            override suspend fun getPanel(
                authorization: String, origin: String, referer: String, gameCode: String,
                language: String, random: String, signature: String, timestamp: Long
            ): Response<GetPanelResponse> = Response.error(401, "{}".toResponseBody(null))
        }
        val always401History = object : HistoryApiService {
            override suspend fun getHistoryIssuePage(
                authorization: String, origin: String, referer: String, gameCode: String,
                pageNo: Int, pageSize: Int, language: String, random: String, signature: String, timestamp: Long
            ): Response<HistoryPageResponse> = Response.error(401, "{}".toResponseBody(null))
        }

        val failingRefreshManager = ApiRequestManager(
            historyApiService = always401History,
            getPanelApiService = always401Panel,
            authProvider = CompositeApiAuthenticationProvider(credProvider, validSig),
            sessionManager = sessionManager,
            networkMonitor = networkMonitor,
            tokenRefreshProvider = failingRefresh
        )
        if (networkMonitor.isOnline()) {
            val outcome = failingRefreshManager.fetchPanel()
            assertTrue(outcome is ApiCallOutcome.SessionExpired)
            assertEquals(1, refreshAttempts)
            assertTrue(sessionManager.apiAuthState.value is ApiAuthenticationState.TokenExpired)
            assertEquals(DiagnosticApiAuthStatus.TOKEN_EXPIRED, sessionManager.diagnostics.value.apiAuthStatus)
            assertFalse(cryptoManager.hasEncryptedEntry(WebsiteSessionManager.KEY_API_TOKEN))
            assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Authenticated)
        }
        networkMonitor.unregister()
    }

    @Test
    fun `connection ui displays all required status labels and AI engine rejects insufficient history honestly`() = runBlocking {
        var authState by mutableStateOf<ApiAuthenticationState>(ApiAuthenticationState.Authorizing())
        var isEval by mutableStateOf(false)

        composeRule.setContent {
            AnshuWingoTheme {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    FloatingPredictorPanel(
                        containerWidthPx = with(density) { maxWidth.toPx() },
                        containerHeightPx = with(density) { maxHeight.toPx() },
                        panelUiState = PredictorPanelUiState(isPanelOpen = true, activeTab = PredictorPanelTab.PREDICTION),
                        networkStatus = com.example.domain.model.NetworkStatus.Online,
                        timerState = TimerUiState(freshness = DataFreshnessStatus.AWAITING_AUTH),
                        predictionResult = null,
                        trainingSnapshot = ModelTrainingSnapshot(historySampleSize = 0),
                        apiAuthState = authState,
                        lastSyncMessage = null,
                        lastLockNote = null,
                        isEvaluating = isEval,
                        historicalAnalysis = com.example.data.repository.HistoricalAnalysisSummary.Empty,
                        moduleSummaries = emptyList(),
                        moduleStatuses = emptyMap(),
                        historyResults = emptyList(),
                        historyPredictions = emptyList(),
                        onDragOffset = {},
                        onSelectTab = {},
                        onToggleExpand = {},
                        onMinimize = {},
                        onRefreshSync = {},
                        onOpenAnalytics = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AUTHORIZING").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last successful sync: Never | History: 0/500").performScrollTo().assertIsDisplayed()

        authState = ApiAuthenticationState.SignatureNotConfigured("Signing spec unavailable.")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SIGNATURE NOT CONFIGURED").assertIsDisplayed()

        authState = ApiAuthenticationState.TokenExpired("Token timed out.")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("TOKEN EXPIRED").assertIsDisplayed()

        authState = ApiAuthenticationState.ApiError("HTTP 503 Service Unavailable")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("API ERROR").assertIsDisplayed()

        isEval = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("DATA SYNCHRONIZING").assertIsDisplayed()

        // AI execution with insufficient history (< 40 draws) must never fabricate predictions
        val coordinator = AnalysisCoordinator()
        val shortRecords = (1..15).map { idx ->
            ResultEntity(
                issueNumber = "202611141000%05d".format(idx),
                number = idx % 10,
                color = "red",
                premium = "0",
                sum = idx % 10,
                blockId = "b$idx",
                blockNumber = "$idx",
                blockTimestamp = "1794670000",
                source = "GetHistoryIssuePage",
                receivedAt = 1794670000000L + idx * 60_000L
            )
        }
        val outputs = coordinator.runAllModules(
            historyNewestFirst = shortRecords,
            targetIssueNumber = "20261114100000016",
            classificationService = classificationService,
            dataFreshness = DataFreshnessStatus.LIVE
        )
        assertTrue(outputs.any { it.status == com.example.analysis.ModelStatus.INSUFFICIENT_DATA })
        val ensemble = EnsembleEngine(classificationService, ProbabilityCalibrator())
        val insufficientRes = ensemble.combine(
            issueNumber = "20261114100000016",
            freshness = DataFreshnessStatus.LIVE,
            dataCutoffIssue = "20261114100000015",
            trainingSnapshot = ModelTrainingSnapshot(
                historySampleSize = shortRecords.size,
                moduleOutputs = outputs
            )
        )
        assertTrue(insufficientRes is PredictionResult.Unavailable)
        assertEquals("Insufficient training data.", (insufficientRes as PredictionResult.Unavailable).headline)
    }

    @Test
    fun `diagnostic states track WEBVIEW_LOADING UNKNOWN SESSION_EXCHANGE_PENDING LOGIN_CONFIRMED and NOT_LOGGED_IN accurately`() = runBlocking {
        var nowMs = 1_794_670_000_000L
        val cryptoManager = createTestKeystoreCryptoManager()
        cryptoManager.clearAll()
        val sessionManager = WebsiteSessionManager(cryptoManager, wallTimeProvider = { nowMs })

        // 1. Initial & navigating main frame -> WEBVIEW_LOADING + API_AUTH_REQUIRED
        assertEquals(WebsiteDetectionState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteDetectionState)
        assertEquals("API_AUTH_REQUIRED", sessionManager.diagnostics.value.apiAuthStatus.stateCode)

        sessionManager.onWebViewNavigationState(WinGoGameConfig.INITIAL_REFERRAL_URL, mainFrameLoaded = false)
        assertEquals(WebsiteDetectionState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteDetectionState)
        assertFalse(sessionManager.diagnostics.value.webViewLoaded)

        // 2. Approved domain finishes loading without cookie/bridge -> WEBSITE_AUTHENTICATION_UNKNOWN
        sessionManager.onWebViewNavigationState(WinGoGameConfig.INITIAL_REFERRAL_URL, mainFrameLoaded = true)
        sessionManager.evaluateCookieSession(WinGoGameConfig.INITIAL_REFERRAL_URL, mainFrameLoaded = true)
        val unknownDiag = sessionManager.diagnostics.value
        assertEquals(WebsiteDetectionState.WEBSITE_AUTHENTICATION_UNKNOWN, unknownDiag.websiteDetectionState)
        assertTrue(unknownDiag.isApprovedDomain)
        assertEquals("9kclub55.com", unknownDiag.currentApprovedHost)
        assertTrue(unknownDiag.websiteDetectionMethod.contains("WebMessageListener"))
        assertFalse(unknownDiag.credentialAvailable)
        assertFalse(unknownDiag.signatureProviderAvailable)

        // 3. Boolean bridge signal without Bearer token -> Authenticated website, SESSION_EXCHANGE_PENDING
        sessionManager.onBridgeSessionSignal(true, WinGoGameConfig.INITIAL_REFERRAL_URL)
        assertEquals(WebsiteDetectionState.SESSION_EXCHANGE_PENDING, sessionManager.diagnostics.value.websiteDetectionState)
        assertFalse(sessionManager.diagnostics.value.credentialAvailable)

        // 4. Explicit cooperative session exchange -> WEBSITE_LOGIN_CONFIRMED + credentialAvailable
        assertTrue(sessionManager.onAuthorizedSessionExchange("secret_grant_9k", nowMs + 60_000L, WinGoGameConfig.INITIAL_REFERRAL_URL))
        val confirmedDiag = sessionManager.diagnostics.value
        assertEquals(WebsiteDetectionState.WEBSITE_LOGIN_CONFIRMED, confirmedDiag.websiteDetectionState)
        assertTrue(confirmedDiag.credentialAvailable)
        assertFalse(confirmedDiag.signatureProviderAvailable)

        // 5. Token present without signature algorithm -> SIGNATURE_NOT_CONFIGURED
        sessionManager.onApiCredentialsMissing(
            missingBearerToken = false,
            missingSignatureAlgorithm = true,
            details = WebsiteSessionManager.MISSING_SIGNATURE_NOTICE
        )
        assertEquals("SIGNATURE_NOT_CONFIGURED", sessionManager.diagnostics.value.apiAuthStatus.stateCode)
        assertTrue(sessionManager.diagnostics.value.credentialAvailable)
        assertFalse(sessionManager.diagnostics.value.signatureProviderAvailable)

        // 6. Token expiry & API rejection states -> TOKEN_EXPIRED and AUTHORIZATION_REJECTED
        sessionManager.onApiTokenExpired("Token expired.")
        assertEquals("TOKEN_EXPIRED", sessionManager.diagnostics.value.apiAuthStatus.stateCode)
        assertEquals(WebsiteDetectionState.SESSION_EXCHANGE_PENDING, sessionManager.diagnostics.value.websiteDetectionState)

        sessionManager.onApiAuthenticationRejected(401, "API HTTP 401")
        assertEquals("AUTHORIZATION_REJECTED", sessionManager.diagnostics.value.apiAuthStatus.stateCode)
        assertEquals(401, sessionManager.diagnostics.value.lastHttpStatus)

        // 7. Website logout -> WEBSITE_NOT_LOGGED_IN + API_AUTH_REQUIRED
        sessionManager.markWebsiteLoggedOut()
        assertEquals(WebsiteDetectionState.WEBSITE_NOT_LOGGED_IN, sessionManager.diagnostics.value.websiteDetectionState)
        assertEquals("API_AUTH_REQUIRED", sessionManager.diagnostics.value.apiAuthStatus.stateCode)
        assertFalse(sessionManager.diagnostics.value.floatingButtonVisible)
    }

    @Test
    fun `authorized GetPanel and History retrofit wire requests synchronize timer Room and AI modules without leaking secrets`() = runBlocking {
        val nowMs = 1_794_670_000_000L
        val cryptoManager = createTestKeystoreCryptoManager()
        cryptoManager.clearAll()
        val sessionManager = WebsiteSessionManager(cryptoManager, wallTimeProvider = { nowMs })
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadowCm = org.robolectric.Shadows.shadowOf(cm)
        if (cm.activeNetwork == null) {
            @Suppress("DEPRECATION")
            val wifiInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED,
                android.net.ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true
            )
            @Suppress("DEPRECATION")
            shadowCm.setActiveNetworkInfo(wifiInfo)
        }
        val activeNetwork = requireNotNull(cm.activeNetwork) { "Expected activeNetwork in Robolectric" }
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        val shadowCaps = org.robolectric.Shadows.shadowOf(caps)
        shadowCaps.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCaps.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowCm.setNetworkCapabilities(activeNetwork, caps)

        val networkMonitor = NetworkConnectivityMonitor(context)

        val secretBearer = "secret_9k_bearer_value_never_in_ui"
        val secretSig = "signed_hash_never_in_ui"
        assertTrue(sessionManager.onAuthorizedSessionExchange(secretBearer, nowMs + 120_000L, WinGoGameConfig.INITIAL_REFERRAL_URL))

        var capturedPanelHeaders: okhttp3.Headers? = null
        var capturedPanelQuery: Map<String, String?> = emptyMap()

        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val historyItemsJson = (1..50).joinToString(",") { idx ->
            val issue = "202611141000%05d".format(10450 + idx)
            val digit = idx % 10
            val color = WinGoColor.primaryFromNumber(digit).label.lowercase()
            """{"issueNumber":"$issue","number":$digit,"color":"$color","premium":"0","sum":$digit}"""
        }

        val wireTestClient = RetrofitClient.createOkHttpClient()
            .newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url
                when (url.encodedPath) {
                    "/api/Energy/GetPanel" -> {
                        capturedPanelHeaders = req.headers
                        capturedPanelQuery = mapOf(
                            "gameCode" to url.queryParameter("gameCode"),
                            "language" to url.queryParameter("language"),
                            "random" to url.queryParameter("random"),
                            "signature" to url.queryParameter("signature"),
                            "timestamp" to url.queryParameter("timestamp")
                        )
                        okhttp3.Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(buildCapturedGetPanelFixtureJson().toResponseBody(jsonMedia))
                            .build()
                    }
                    "/api/Lottery/GetHistoryIssuePage" -> {
                        val pageNo = url.queryParameter("pageNo")?.toIntOrNull() ?: 1
                        val listJson = if (pageNo == 1) historyItemsJson else ""
                        val bodyJson = """{"code":0,"msg":"Succeed","data":{"pageNo":$pageNo,"totalPage":1,"totalCount":50,"list":[$listJson]}}"""
                        okhttp3.Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(bodyJson.toResponseBody(jsonMedia))
                            .build()
                    }
                    else -> okhttp3.Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("{}".toResponseBody(jsonMedia))
                        .build()
                }
            }
            .build()

        val retrofit = RetrofitClient.createRetrofit(wireTestClient)
        val panelService = RetrofitClient.createGetPanelApiService(retrofit)
        val historyService = RetrofitClient.createHistoryApiService(retrofit)

        val credProvider = KeystoreBackedCredentialProvider(sessionManager)
        val sigProvider = object : ApiSignatureProvider {
            override fun isSignatureAlgorithmConfigured(): Boolean = true
            override suspend fun signRequest(queryParams: Map<String, String>): Result<SignedRequestParams> =
                Result.success(SignedRequestParams("rnd_wire_9k", secretSig, 1763131804L))
        }
        val apiManager = ApiRequestManager(
            historyApiService = historyService,
            getPanelApiService = panelService,
            authProvider = CompositeApiAuthenticationProvider(credProvider, sigProvider),
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val timerSync = TimerSyncManager(
            apiRequestManager = apiManager,
            networkMonitor = networkMonitor,
            scope = scope
        )
        val collector = HistoryCollector(apiManager, db.resultDao(), networkMonitor)

        assertTrue("Robolectric NetworkConnectivityMonitor must be online", networkMonitor.isOnline())
        timerSync.synchronizeNow()

        // Verify exact GetPanel wire request headers & query parameters
        assertNotNull(capturedPanelHeaders)
        assertEquals("Bearer $secretBearer", capturedPanelHeaders!!["Authorization"])
        assertEquals("https://9kclub5.com", capturedPanelHeaders!!["Origin"])
        assertEquals("https://9kclub5.com/", capturedPanelHeaders!!["Referer"])
        assertEquals("WinGo_1M", capturedPanelQuery["gameCode"])
        assertEquals("en", capturedPanelQuery["language"])
        assertEquals("rnd_wire_9k", capturedPanelQuery["random"])
        assertEquals(secretSig, capturedPanelQuery["signature"])
        assertEquals("1763131804", capturedPanelQuery["timestamp"])

        // Verify live TimerSyncManager and Diagnostics HTTP 200 + API_CONNECTED
        val timerState = timerSync.timerState.value
        assertEquals("20261114100010500", timerState.issueNumber)
        assertEquals("Betting", timerState.phase)
        assertEquals(DataFreshnessStatus.LIVE, timerState.freshness)
        assertEquals(12, timerState.snapshotNo)

        val syncResult = collector.collectLatest500()
        assertTrue(syncResult is HistorySyncResult.Success)
        assertEquals(50, db.resultDao().getResultCount())

        val diag = sessionManager.diagnostics.value
        assertEquals("API_CONNECTED", diag.apiAuthStatus.stateCode)
        assertEquals(200, diag.lastHttpStatus)
        assertTrue(diag.lastRequestStatus.contains("HTTP 200 OK"))
        assertTrue(diag.credentialAvailable)
        assertTrue(diag.signatureProviderAvailable)

        // Verify real persisted draws feed all 12 AI modules
        val storedDraws = db.resultDao().getLatestResultsSnapshot(500)
        val coordinator = AnalysisCoordinator()
        val outputs = coordinator.runAllModules(
            historyNewestFirst = storedDraws,
            targetIssueNumber = timerState.issueNumber,
            classificationService = classificationService,
            dataFreshness = timerState.freshness
        )
        assertEquals(12, outputs.size)
        assertTrue(outputs.any { it.numberProbabilities != null })

        // Verify Diagnostics UI shows Website & API telemetry and never renders the secret token/sig
        val container = AppContainer(context)
        val viewModel = PredictorViewModel(container)
        composeRule.setContent {
            AnshuWingoTheme {
                WebViewScreen(
                    viewModel = viewModel,
                    sessionManager = sessionManager,
                    onNavigateToAnalytics = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("login_diagnostic_panel").assertIsDisplayed()
        composeRule.onNodeWithText("Website Diagnostics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Website detection method").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("API Diagnostics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Credential availability").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Signature provider").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last request status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("HTTP status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last successful sync").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(secretBearer, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(secretSig, substring = true).assertDoesNotExist()

        container.networkMonitor.unregister()
        scope.cancel()
        networkMonitor.unregister()
    }

    @Test
    fun `websiteLoginDetector WebMessageListener detects logged-in logged-out conflicting UNKNOWN expired and stops off-origin`() {
        val cryptoManager = createTestKeystoreCryptoManager()
        cryptoManager.clearAll()
        val sessionManager = WebsiteSessionManager(cryptoManager, wallTimeProvider = { 1_794_670_000_000L })

        var currentUrl: String? = WinGoGameConfig.INITIAL_REFERRAL_URL
        var mainFrameLoaded = false
        val evaluatedScripts = mutableListOf<String>()

        val detector = WebsiteLoginDetector(
            sessionManager = sessionManager,
            currentUrlProvider = { currentUrl },
            isMainFrameLoadedProvider = { mainFrameLoaded },
            evaluateJavascript = { script -> evaluatedScripts.add(script) },
            mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        )

        // 1. Initial state before page finish -> WEBVIEW_LOADING
        assertEquals(WebsiteLoginState.WEBVIEW_LOADING, sessionManager.diagnostics.value.websiteLoginState)
        assertFalse(detector.isDetectorActive())

        // 2. WebMessageListener unsupported degrades gracefully to UNKNOWN without crash
        val webView = android.webkit.WebView(context)
        assertFalse(detector.attachToWebView(webView) { false })
        assertFalse(detector.isWebMessageListenerSupported())

        // Simulate supported WebViewFeature.WEB_MESSAGE_LISTENER & onPageFinished on approved domain
        val detector2 = WebsiteLoginDetector(
            sessionManager = sessionManager,
            currentUrlProvider = { currentUrl },
            isMainFrameLoadedProvider = { mainFrameLoaded },
            evaluateJavascript = { script -> evaluatedScripts.add(script) },
            mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        )
        mainFrameLoaded = true
        sessionManager.onWebViewNavigationState(currentUrl, mainFrameLoaded = true)
        detector2.onPageFinished(currentUrl)
        assertTrue(detector2.isDetectorActive())
        assertTrue(sessionManager.diagnostics.value.isScannerPollingActive)
        assertEquals(1, evaluatedScripts.size)
        assertTrue(evaluatedScripts.first().contains("MutationObserver"))
        assertFalse(evaluatedScripts.first().contains("localStorage"))
        assertFalse(evaluatedScripts.first().contains("sessionStorage"))
        assertFalse(evaluatedScripts.first().contains("document.cookie"))
        assertEquals(WebsiteLoginState.UNKNOWN, sessionManager.diagnostics.value.websiteLoginState)

        // 3. Inconclusive DOM observation keeps state UNKNOWN
        detector2.handleWebMessage(
            rawJsonPayload = """
                {"type":"dom_login_observation","origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/home?inviteCode=A86A78N","hasAuthenticatedDom":false,"hasLoginFormDom":false,"hasSessionExpiredDom":false,"indicatorKind":"inconclusive_dom"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub55.com/#/home?inviteCode=A86A78N"
        )
        assertEquals("https://9kclub55.com/#/home", sessionManager.diagnostics.value.lastReportedWebUrl)
        assertEquals(WebsiteLoginState.UNKNOWN, sessionManager.diagnostics.value.websiteLoginState)

        // 4. Login form DOM -> LOGGED_OUT
        detector2.handleWebMessage(
            rawJsonPayload = """
                {"type":"dom_login_observation","origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/login","hasAuthenticatedDom":false,"hasLoginFormDom":true,"hasSessionExpiredDom":false,"indicatorKind":"login_form_dom"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub55.com/#/login"
        )
        assertEquals(WebsiteLoginState.LOGGED_OUT, sessionManager.diagnostics.value.websiteLoginState)
        assertTrue(sessionManager.websiteSessionState.value is WebsiteSessionState.Unauthenticated)

        // 5. Authenticated DOM after SPA login -> LOGIN_CONFIRMED while GetPanel stays API_AUTH_REQUIRED
        currentUrl = "https://9kclub55.com/#/WinGo"
        detector2.onSpaUrlUpdated(currentUrl)
        detector2.handleWebMessage(
            rawJsonPayload = """
                {"type":"dom_login_observation","origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/WinGo","hasAuthenticatedDom":true,"hasLoginFormDom":false,"hasSessionExpiredDom":false,"indicatorKind":"dom_logout_control"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub55.com/#/WinGo"
        )
        val loggedInDiag = sessionManager.diagnostics.value
        assertEquals(WebsiteLoginState.LOGIN_CONFIRMED, loggedInDiag.websiteLoginState)
        assertEquals("LOGGED IN", loggedInDiag.websiteLoginState.uiLabel)
        assertEquals(ApiAuthStatusCode.API_AUTH_REQUIRED, loggedInDiag.apiStatusCode)
        assertEquals("AUTH REQUIRED", loggedInDiag.apiStatusCode.uiLabel)
        assertTrue(loggedInDiag.floatingButtonVisible)
        assertTrue(loggedInDiag.apiAuthReason.contains("Bearer token and signing specification remain unavailable"))

        // 6. Conflicting login & logout DOM indicators MUST resolve to UNKNOWN
        assertEquals(
            WebsiteLoginState.UNKNOWN,
            WebsiteLoginDetector.resolveWebsiteLoginState(
                DomLoginObservation(
                    observedOrigin = "https://9kclub55.com",
                    sanitizedUrl = "https://9kclub55.com/#/home",
                    hasAuthenticatedDom = true,
                    hasLoginFormDom = true,
                    hasSessionExpiredDom = false,
                    indicatorKind = "conflicting_dom"
                )
            )
        )
        detector2.handleWebMessage(
            rawJsonPayload = """
                {"type":"dom_login_observation","origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/home","hasAuthenticatedDom":true,"hasLoginFormDom":true,"hasSessionExpiredDom":false,"indicatorKind":"conflicting_dom"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub55.com/#/home"
        )
        assertEquals(WebsiteLoginState.UNKNOWN, sessionManager.diagnostics.value.websiteLoginState)

        // Re-confirm login then verify subframe & spoofed-origin WebMessages are rejected
        detector2.handleWebMessage(
            rawJsonPayload = """
                {"type":"dom_login_observation","origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/WinGo","hasAuthenticatedDom":true,"hasLoginFormDom":false,"hasSessionExpiredDom":false,"indicatorKind":"dom_logout_control"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub55.com/#/WinGo"
        )
        assertEquals(WebsiteLoginState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteLoginState)

        // Subframe message (isMainFrame = false) MUST be ignored
        detector2.handleWebMessage(
            rawJsonPayload = """
                {"type":"dom_login_observation","origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/login","hasAuthenticatedDom":false,"hasLoginFormDom":true,"hasSessionExpiredDom":false,"indicatorKind":"login_form_dom"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = false,
            currentWebViewUrl = "https://9kclub55.com/#/WinGo"
        )
        assertEquals(WebsiteLoginState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteLoginState)

        // Unapproved sourceOrigin MUST be rejected
        detector2.handleWebMessage(
            rawJsonPayload = """
                {"type":"dom_login_observation","origin":"https://evil.example.com","cleanUrl":"https://evil.example.com/#/logout","hasAuthenticatedDom":false,"hasLoginFormDom":true,"hasSessionExpiredDom":true,"indicatorKind":"spoof"}
            """.trimIndent(),
            sourceOrigin = "https://evil.example.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub55.com/#/WinGo"
        )
        assertEquals(WebsiteLoginState.LOGIN_CONFIRMED, sessionManager.diagnostics.value.websiteLoginState)

        // 7. Session expired DOM -> SESSION_EXPIRED (API stays AUTH_REQUIRED if no token existed)
        detector2.resumeDetection()
        assertTrue(detector2.isDetectorActive())
        detector2.handleWebMessage(
            rawJsonPayload = """
                {"type":"dom_login_observation","origin":"https://9kclub55.com","cleanUrl":"https://9kclub55.com/#/WinGo","hasAuthenticatedDom":false,"hasLoginFormDom":false,"hasSessionExpiredDom":true,"indicatorKind":"session_expired_dom"}
            """.trimIndent(),
            sourceOrigin = "https://9kclub55.com",
            isMainFrame = true,
            currentWebViewUrl = "https://9kclub55.com/#/WinGo"
        )
        assertEquals(WebsiteLoginState.SESSION_EXPIRED, sessionManager.diagnostics.value.websiteLoginState)
        assertEquals(ApiAuthStatusCode.API_AUTH_REQUIRED, sessionManager.diagnostics.value.apiStatusCode)
        assertFalse(sessionManager.diagnostics.value.floatingButtonVisible)

        // 8. Navigation off approved origin or detach stops detector cleanly
        currentUrl = "https://unapproved.example.net/"
        detector2.onSpaUrlUpdated(currentUrl)
        assertFalse(detector2.isDetectorActive())
        assertFalse(sessionManager.diagnostics.value.isScannerPollingActive)

        detector2.detach(webView)
        assertFalse(detector2.injectOrRefreshDetector())
        webView.destroy()
    }

    @Test
    fun `webview diagnostics ui displays LOGGED IN AUTH REQUIRED History and GetPanel state independently`() {
        val container = AppContainer(context)
        val viewModel = PredictorViewModel(container)

        composeRule.setContent {
            AnshuWingoTheme {
                WebViewScreen(
                    viewModel = viewModel,
                    sessionManager = container.websiteSessionManager,
                    onNavigateToAnalytics = {}
                )
            }
        }
        composeRule.waitForIdle()

        // GetPanel unavailable -> diagnostics show NOT AVAILABLE, UNKNOWN, NOT SYNCHRONIZED
        composeRule.onNodeWithText("History Diagnostics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("History API status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Actual record count").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Current issue").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("NOT AVAILABLE").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("NOT SYNCHRONIZED").performScrollTo().assertIsDisplayed()

        // DOM detector confirms website login on approved domain while API auth stays unavailable
        container.websiteSessionManager.onWebViewNavigationState(
            url = WinGoGameConfig.INITIAL_REFERRAL_URL,
            mainFrameLoaded = true
        )
        container.websiteSessionManager.onDomLoginStateObserved(
            currentWebViewUrl = WinGoGameConfig.INITIAL_REFERRAL_URL,
            sanitizedUrl = "https://9kclub55.com/#/home",
            resolvedState = WebsiteLoginState.LOGIN_CONFIRMED,
            indicatorKind = "dom_logout_control"
        )
        composeRule.waitForIdle()

        // Floating "A" button appears automatically once website login is confirmed
        composeRule.onNodeWithTag("floating_predictor_button").assertIsDisplayed()
        composeRule.onNodeWithText("Website authentication").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("LOGGED IN").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("API authentication").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("AUTH REQUIRED").performScrollTo().assertIsDisplayed()

        // After a verified authenticated API response -> displays CONNECTED while Website stays LOGGED IN
        container.websiteSessionManager.onApiAuthenticationVerified(httpStatus = 200)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LOGGED IN").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CONNECTED").performScrollTo().assertIsDisplayed()

        container.networkMonitor.unregister()
    }

    @Test
    fun `live HistoryDataSource parses real draw json handles missing optional fields errors timeouts offline stale and backoff`() = runBlocking {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadowCm = org.robolectric.Shadows.shadowOf(cm)
        if (cm.activeNetwork == null) {
            @Suppress("DEPRECATION")
            val wifiInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED,
                android.net.ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true
            )
            @Suppress("DEPRECATION")
            shadowCm.setActiveNetworkInfo(wifiInfo)
        }
        val activeNetwork = requireNotNull(cm.activeNetwork)
        val onlineCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(onlineCaps).apply {
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowCm.setNetworkCapabilities(activeNetwork, onlineCaps)
        val networkMonitor = NetworkConnectivityMonitor(context)
        assertTrue(networkMonitor.isOnline())

        var nowMs = 1_794_675_000_000L
        var mode = "LIVE_CAPTURED"

        val capturedRealDrawJson = """
            {
              "data": {
                "list": [
                  {
                    "issueNumber": "20261114100011007",
                    "number": "0",
                    "color": "red,violet",
                    "premium": "0",
                    "sum": 0
                  },
                  {
                    "issueNumber": "20261114100011006",
                    "number": "7",
                    "color": "green",
                    "premium": "0"
                  }
                ],
                "pageNo": 1,
                "totalPage": 50,
                "totalCount": 500
              },
              "code": 0,
              "msg": "Succeed",
              "msgCode": 0,
              "serviceTime": 1794674817450
            }
        """.trimIndent()

        val jsonMedia = "application/json; charset=utf-8".toMediaType()
        val testClient = RetrofitClient.createOkHttpClient().newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                when (mode) {
                    "LIVE_CAPTURED" -> okhttp3.Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(capturedRealDrawJson.toResponseBody(jsonMedia)).build()
                    "MALFORMED_JSON" -> okhttp3.Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("{not-valid-json".toResponseBody(jsonMedia)).build()
                    "HTTP_503" -> okhttp3.Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(503).message("Service Unavailable")
                        .body("{}".toResponseBody(jsonMedia)).build()
                    "TIMEOUT" -> throw java.net.SocketTimeoutException("Read timed out after 10s")
                    else -> okhttp3.Response.Builder()
                        .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"data":{"list":[],"pageNo":1,"totalPage":0,"totalCount":0},"code":0,"msg":"Succeed","serviceTime":1794674900000}""".toResponseBody(jsonMedia))
                        .build()
                }
            }
            .build()

        val service = HistoryDataSource.createDefaultService(
            baseUrl = WinGoGameConfig.HISTORY_JSON_BASE_URL,
            okHttpClient = testClient
        )
        val dataSource = HistoryDataSource(
            service = service,
            networkMonitor = networkMonitor,
            wallTimeProvider = { nowMs },
            staleThresholdMs = 15_000L
        )
        val sessionManager = WebsiteSessionManager(createTestKeystoreCryptoManager())
        val apiRequestManager = ApiRequestManager(
            historyApiService = RetrofitClient.createHistoryApiService(),
            getPanelApiService = RetrofitClient.createGetPanelApiService(),
            authProvider = UnconfiguredApiAuthenticationProvider(sessionManager),
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )
        val collector = HistoryCollector(apiRequestManager, db.resultDao(), networkMonitor)

        // 1. Live captured JSON parses string number "0", missing optional sum/blockTimestamp without crashing or inventing
        val okOutcome = dataSource.fetchLatestHistory()
        assertTrue(okOutcome is HistoryFetchOutcome.Success)
        val records = (okOutcome as HistoryFetchOutcome.Success).records
        assertEquals(2, records.size)
        assertEquals(0, records[0].number)
        assertEquals(7, records[1].number)
        assertEquals(null, records[1].sum)
        val mappedMissingSum = collector.validateAndMapRecord(records[1], nowMs)!!
        assertEquals(-1, mappedMissingSum.sum)
        assertEquals("", mappedMissingSum.blockTimestamp)
        assertEquals(HistoryApiStatus.LIVE, dataSource.diagnostics.value.status)

        // 2. Stale detection when elapsed > threshold
        nowMs += 20_000L
        dataSource.evaluateStaleState()
        assertEquals(HistoryApiStatus.STALE, dataSource.diagnostics.value.status)

        // 3. Malformed JSON -> HISTORY_API_UNAVAILABLE
        mode = "MALFORMED_JSON"
        val parseFail = dataSource.fetchLatestHistory()
        assertTrue(parseFail is HistoryFetchOutcome.ParseFailure)
        assertEquals(HistoryApiStatus.HISTORY_API_UNAVAILABLE, dataSource.diagnostics.value.status)
        assertEquals(1, dataSource.diagnostics.value.consecutiveFailures)

        // 4. Non-200 HTTP error -> HISTORY_API_UNAVAILABLE + exponential backoff
        mode = "HTTP_503"
        val httpFail = dataSource.fetchLatestHistory()
        assertTrue(httpFail is HistoryFetchOutcome.HttpFailure)
        assertEquals(503, (httpFail as HistoryFetchOutcome.HttpFailure).httpCode)
        assertEquals(2, dataSource.diagnostics.value.consecutiveFailures)
        assertEquals(6_000L, HistoryDataSource.computeBackoffDelayMs(2, baseIntervalMs = 3_000L))

        // 5. Socket timeout -> NetworkFailure(isTimeout = true) & HISTORY_API_UNAVAILABLE
        mode = "TIMEOUT"
        val timeoutFail = dataSource.fetchLatestHistory()
        assertTrue(timeoutFail is HistoryFetchOutcome.NetworkFailure)
        assertTrue((timeoutFail as HistoryFetchOutcome.NetworkFailure).isTimeout)
        assertEquals(HistoryApiStatus.HISTORY_API_UNAVAILABLE, dataSource.diagnostics.value.status)

        // 6. Offline check with cached lastSuccess transitions to STALE cleanly
        val offlineCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowCm.setNetworkCapabilities(activeNetwork, offlineCaps)
        assertFalse(networkMonitor.isOnline())
        val offlineOutcome = dataSource.fetchLatestHistory()
        assertTrue(offlineOutcome is HistoryFetchOutcome.Offline)
        assertEquals(HistoryApiStatus.STALE, dataSource.diagnostics.value.status)

        networkMonitor.unregister()
    }

    @Test
    fun `liveHistoryPoller enforces single-flight deduplication 500 retention limit and coroutine cancellation`() = runBlocking {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadowCm = org.robolectric.Shadows.shadowOf(cm)
        if (cm.activeNetwork == null) {
            @Suppress("DEPRECATION")
            val wifiInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED,
                android.net.ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true
            )
            @Suppress("DEPRECATION")
            shadowCm.setActiveNetworkInfo(wifiInfo)
        }
        val activeNetwork = requireNotNull(cm.activeNetwork)
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).apply {
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowCm.setNetworkCapabilities(activeNetwork, caps)
        val networkMonitor = NetworkConnectivityMonitor(context)

        var activeConcurrentCalls = 0
        var maxConcurrentCalls = 0
        var totalCalls = 0
        val batchIssues = mutableListOf<List<HistoryRecordDto>>()
        val batch1 = (1..260).map { idx ->
            HistoryRecordDto(
                issueNumber = "202611141000%05d".format(idx),
                number = idx % 10,
                color = WinGoColor.primaryFromNumber(idx % 10).label.lowercase(),
                premium = "0",
                sum = idx % 10
            )
        }
        val batch2 = (251..520).map { idx -> // 10 duplicates + 260 new -> 520 unique total -> trimmed to 500
            HistoryRecordDto(
                issueNumber = "202611141000%05d".format(idx),
                number = idx % 10,
                color = WinGoColor.primaryFromNumber(idx % 10).label.lowercase(),
                premium = "0",
                sum = idx % 10
            )
        }
        batchIssues.add(batch1)
        batchIssues.add(batch2)

        val slowFakeService = object : DrawHistoryJsonService {
            override suspend fun getHistoryIssuePage(timestamp: Long): Response<HistoryPageResponse> {
                activeConcurrentCalls++
                if (activeConcurrentCalls > maxConcurrentCalls) {
                    maxConcurrentCalls = activeConcurrentCalls
                }
                val idx = totalCalls++
                kotlinx.coroutines.delay(25L)
                activeConcurrentCalls--
                val list = batchIssues.getOrElse(idx) { emptyList() }
                return Response.success(
                    HistoryPageResponse(
                        code = 0,
                        msg = "Succeed",
                        serviceTime = 1794674900000L,
                        data = HistoryPageData(
                            pageNo = 1,
                            totalPage = 1,
                            totalCount = list.size,
                            list = list
                        )
                    )
                )
            }
        }

        val dataSource = HistoryDataSource(slowFakeService, networkMonitor)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = HistoryCollector(
            apiRequestManager = ApiRequestManager(
                RetrofitClient.createHistoryApiService(),
                RetrofitClient.createGetPanelApiService(),
                UnconfiguredApiAuthenticationProvider(WebsiteSessionManager(createTestKeystoreCryptoManager())),
                WebsiteSessionManager(createTestKeystoreCryptoManager()),
                networkMonitor
            ),
            resultDao = db.resultDao(),
            networkMonitor = networkMonitor
        )

        val poller = LiveHistoryPoller(
            historyDataSource = dataSource,
            resultDao = db.resultDao(),
            scope = scope,
            mapRecord = { dto, now -> collector.validateAndMapRecord(dto, now) },
            pollIntervalMs = 5_000L
        )

        val job1 = async(Dispatchers.Default) { poller.syncOnce() }
        val job2 = async(Dispatchers.Default) { poller.syncOnce() }
        job1.await()
        job2.await()

        assertEquals(1, maxConcurrentCalls)
        assertEquals(500, db.resultDao().getResultCount())
        assertEquals(500, dataSource.diagnostics.value.totalStoredCount)

        // Start & stop polling cleans up coroutine without leaking active polling state
        poller.startPolling()
        assertTrue(poller.isPollingActive())
        poller.pausePolling()
        assertFalse(poller.isPollingActive())
        poller.resumePolling()
        assertTrue(poller.isPollingActive())
        poller.stopPolling()
        assertFalse(poller.isPollingActive())

        scope.cancel()
        networkMonitor.unregister()
    }

    @Test
    fun `phase 1 disables runtime AI execution and shows honest NOT AVAILABLE UNKNOWN NOT SYNCHRONIZED UI`() {
        assertFalse(WinGoGameConfig.PHASE_1_AI_EXECUTION_ENABLED)

        val container = AppContainer(context)
        val viewModel = PredictorViewModel(container)

        // Calling evaluateCurrentPrediction() in Phase 1 must be a strict no-op while preserving all 12 modules
        viewModel.evaluateCurrentPrediction()
        assertEquals(12, viewModel.moduleSummaries.size)
        assertEquals(null, viewModel.getCurrentPredictionOrNull())

        val sampleRow = ResultEntity(
            issueNumber = "20261114100011007",
            number = 0,
            color = "red,violet",
            premium = "0",
            sum = 0,
            blockId = "",
            blockNumber = "",
            blockTimestamp = "",
            source = "GetHistoryIssuePage",
            receivedAt = 1_794_674_820_000L
        )

        var activeTab by mutableStateOf(PredictorPanelTab.PREDICTION)
        composeRule.setContent {
            AnshuWingoTheme {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    FloatingPredictorPanel(
                        containerWidthPx = with(density) { maxWidth.toPx() },
                        containerHeightPx = with(density) { maxHeight.toPx() },
                        panelUiState = PredictorPanelUiState(isPanelOpen = true, activeTab = activeTab),
                        networkStatus = com.example.domain.model.NetworkStatus.Online,
                        timerState = TimerUiState(freshness = DataFreshnessStatus.AWAITING_AUTH),
                        predictionResult = null,
                        trainingSnapshot = ModelTrainingSnapshot(historySampleSize = 0),
                        apiAuthState = ApiAuthenticationState.AuthRequired(),
                        lastSyncMessage = "Live history synced: 1 fetched (1/500 cached, RTT 42ms).",
                        lastLockNote = null,
                        isEvaluating = false,
                        historicalAnalysis = com.example.data.repository.HistoricalAnalysisSummary.Empty,
                        moduleSummaries = viewModel.moduleSummaries,
                        moduleStatuses = emptyMap(),
                        historyResults = listOf(sampleRow),
                        historyPredictions = emptyList(),
                        onDragOffset = {},
                        onSelectTab = { activeTab = it },
                        onToggleExpand = {},
                        onMinimize = {},
                        onRefreshSync = {},
                        onOpenAnalytics = {},
                        historyDiagnostics = com.example.network.HistorySyncDiagnostics(
                            status = HistoryApiStatus.LIVE,
                            lastHttpStatus = 200,
                            lastSuccessfulSyncAtMillis = 1_794_674_820_000L,
                            totalStoredCount = 1
                        )
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("NOT AVAILABLE").assertIsDisplayed()
        composeRule.onNodeWithText("Phase: UNKNOWN").assertIsDisplayed()
        composeRule.onNodeWithText("--:--").assertIsDisplayed()
        composeRule.onNodeWithText("Timer: NOT SYNCHRONIZED").assertIsDisplayed()
        composeRule.onNodeWithText("Phase 1: AI Prediction Execution Paused").performScrollTo().assertIsDisplayed()

        // Switch to History tab and verify actual Room record + sum + color + LIVE badge render
        activeTab = PredictorPanelTab.HISTORY
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Actual record count: 1/500").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("20261114100011007").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("red,violet").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Sum: 0").performScrollTo().assertIsDisplayed()

        container.networkMonitor.unregister()
    }
}
