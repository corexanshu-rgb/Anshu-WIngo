package com.example.di

import android.content.Context
import com.example.analysis.AnalysisCoordinator
import com.example.analytics.AnalyticsRepository
import com.example.classification.ClassificationRuleConfig
import com.example.classification.ResultClassificationService
import com.example.data.local.AppDatabase
import com.example.data.repository.HistoryCollector
import com.example.data.repository.HistoryRepository
import com.example.domain.model.WinGoGameConfig
import com.example.network.ApiAuthenticationProvider
import com.example.network.ApiRequestManager
import com.example.network.BackendIntegrationStatus
import com.example.network.BackendProxyApiRequestManager
import com.example.network.BackendProxyConfig
import com.example.network.DrawHistoryJsonService
import com.example.network.GetPanelApiService
import com.example.network.HistoryApiService
import com.example.network.HistoryDataSource
import com.example.network.LiveHistoryPoller
import com.example.network.NetworkConnectivityMonitor
import com.example.network.RetrofitClient
import com.example.network.UnconfiguredApiAuthenticationProvider
import com.example.prediction.EnsembleEngine
import com.example.prediction.PredictionCoordinator
import com.example.prediction.PredictionLockManager
import com.example.prediction.PredictionRepository
import com.example.security.KeystoreCryptoManager
import com.example.security.WebsiteSessionManager
import com.example.timer.TimerSyncManager
import com.example.training.ModelTrainingManager
import com.example.validation.AccuracyTracker
import com.example.validation.DriftDetector
import com.example.validation.ProbabilityCalibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }
    val resultDao by lazy { database.resultDao() }
    val predictionDao by lazy { database.predictionDao() }
    val analysisDao by lazy { database.analysisDao() }

    val cryptoManager: KeystoreCryptoManager by lazy { KeystoreCryptoManager(appContext) }
    val websiteSessionManager: WebsiteSessionManager by lazy { WebsiteSessionManager(cryptoManager) }
    val networkMonitor: NetworkConnectivityMonitor by lazy { NetworkConnectivityMonitor(appContext) }

    private val okHttpClient by lazy { RetrofitClient.createOkHttpClient() }
    private val retrofit by lazy { RetrofitClient.createRetrofit(okHttpClient) }
    val historyApiService: HistoryApiService by lazy { RetrofitClient.createHistoryApiService(retrofit) }
    val getPanelApiService: GetPanelApiService by lazy { RetrofitClient.createGetPanelApiService(retrofit) }
    val drawHistoryJsonService: DrawHistoryJsonService by lazy {
        RetrofitClient.createDrawHistoryJsonService(okHttpClient)
    }

    val historyDataSource: HistoryDataSource by lazy {
        HistoryDataSource(
            service = drawHistoryJsonService,
            networkMonitor = networkMonitor
        )
    }

    val backendProxyConfig: BackendProxyConfig = BackendProxyConfig(baseUrl = null)
    val backendIntegrationStatus: BackendIntegrationStatus = if (backendProxyConfig.isConfiguredHttps) {
        BackendIntegrationStatus.ConfiguredAwaitingVerification(backendProxyConfig.baseUrl!!.trim())
    } else {
        BackendIntegrationStatus.NotConfigured(BackendProxyApiRequestManager.UNCONFIGURED_REASON)
    }

    val apiAuthenticationProvider: ApiAuthenticationProvider by lazy {
        UnconfiguredApiAuthenticationProvider(websiteSessionManager)
    }

    val apiRequestManager: ApiRequestManager by lazy {
        ApiRequestManager(
            historyApiService = historyApiService,
            getPanelApiService = getPanelApiService,
            authProvider = apiAuthenticationProvider,
            sessionManager = websiteSessionManager,
            networkMonitor = networkMonitor
        )
    }

    val classificationService: ResultClassificationService by lazy {
        ResultClassificationService(
            ClassificationRuleConfig(
                officialColorRuleConfirmed = false,
                officialBigSmallRuleConfirmed = false,
                minRecordsForEmpiricalColorVerification = 30
            )
        )
    }

    val predictionRepository: PredictionRepository by lazy {
        PredictionRepository(
            predictionDao = predictionDao,
            classificationService = classificationService
        )
    }

    val accuracyTracker: AccuracyTracker by lazy { AccuracyTracker() }

    val analyticsRepository: AnalyticsRepository by lazy {
        AnalyticsRepository(
            predictionDao = predictionDao,
            analysisDao = analysisDao,
            accuracyTracker = accuracyTracker
        )
    }

    val historyCollector: HistoryCollector by lazy {
        HistoryCollector(
            apiRequestManager = apiRequestManager,
            resultDao = resultDao,
            networkMonitor = networkMonitor,
            onNewResultsStored = { newResults ->
                predictionRepository.verifyAgainstNewResults(newResults)
            }
        )
    }

    val liveHistoryPoller: LiveHistoryPoller by lazy {
        LiveHistoryPoller(
            historyDataSource = historyDataSource,
            resultDao = resultDao,
            scope = appScope,
            mapRecord = { dto, now -> historyCollector.validateAndMapRecord(dto, now) },
            onNewResultsStored = { newResults ->
                predictionRepository.verifyAgainstNewResults(newResults)
            }
        )
    }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(
            resultDao = resultDao,
            historyCollector = historyCollector,
            historyDataSource = historyDataSource,
            liveHistoryPoller = liveHistoryPoller
        )
    }

    val analysisCoordinator: AnalysisCoordinator by lazy { AnalysisCoordinator() }
    val calibrator: ProbabilityCalibrator by lazy { ProbabilityCalibrator() }
    val driftDetector: DriftDetector by lazy { DriftDetector() }

    val modelTrainingManager: ModelTrainingManager by lazy {
        ModelTrainingManager(
            analysisCoordinator = analysisCoordinator,
            analysisDao = analysisDao,
            classificationService = classificationService,
            driftDetector = driftDetector,
            calibrator = calibrator
        )
    }

    val predictionLockManager: PredictionLockManager by lazy {
        PredictionLockManager(
            predictionDao = predictionDao,
            resultDao = resultDao
        )
    }

    val ensembleEngine: EnsembleEngine by lazy {
        EnsembleEngine(
            classificationService = classificationService,
            calibrator = calibrator
        )
    }

    val predictionCoordinator: PredictionCoordinator by lazy {
        PredictionCoordinator(
            historyRepository = historyRepository,
            predictionRepository = predictionRepository,
            trainingManager = modelTrainingManager,
            ensembleEngine = ensembleEngine,
            lockManager = predictionLockManager
        )
    }

    val timerSyncManager: TimerSyncManager by lazy {
        TimerSyncManager(
            apiRequestManager = apiRequestManager,
            networkMonitor = networkMonitor,
            scope = appScope,
            onIssueChanged = { newIssue, previousIssue ->
                appScope.launch {
                    if (previousIssue != null) {
                        liveHistoryPoller.syncOnce()
                    }
                    if (WinGoGameConfig.PHASE_1_AI_EXECUTION_ENABLED) {
                        val currentTimer = timerSyncManager.timerState.value
                        predictionCoordinator.evaluateRound(
                            issueNumber = newIssue,
                            freshness = currentTimer.freshness,
                            remainingMillis = currentTimer.remainingMillis,
                            currentPhase = currentTimer.phase
                        )
                    }
                }
            },
            onPhaseChanged = { _, _ ->
                appScope.launch {
                    if (!WinGoGameConfig.PHASE_1_AI_EXECUTION_ENABLED) return@launch
                    val currentTimer = timerSyncManager.timerState.value
                    val issue = currentTimer.issueNumber ?: return@launch
                    predictionCoordinator.evaluateRound(
                        issueNumber = issue,
                        freshness = currentTimer.freshness,
                        remainingMillis = currentTimer.remainingMillis,
                        currentPhase = currentTimer.phase
                    )
                }
            }
        )
    }
}
