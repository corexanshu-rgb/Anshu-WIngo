package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.analytics.AnalyticsViewModel
import com.example.di.AppContainer
import com.example.ui.PredictorViewModel
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.WebViewScreen
import com.example.ui.theme.AnshuWingoTheme
import com.example.webview.WebViewController

const val ROUTE_WEBSITE = "website"
const val ROUTE_ANALYTICS = "analytics"

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer by lazy { AppContainer(applicationContext) }
    private var activeWebViewController: WebViewController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AnshuWingoTheme {
                val navController = rememberNavController()
                val webViewController = remember {
                    WebViewController().also { activeWebViewController = it }
                }
                val predictorFactory = remember { PredictorViewModel.Factory(appContainer) }
                val analyticsFactory = remember { AnalyticsViewModel.Factory(appContainer) }
                val predictorViewModel: PredictorViewModel = viewModel(factory = predictorFactory)
                val analyticsViewModel: AnalyticsViewModel = viewModel(factory = analyticsFactory)

                val backStackEntry by navController.currentBackStackEntryAsState()
                val isWebsiteRouteActive = backStackEntry?.destination?.route != ROUTE_ANALYTICS

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                predictorViewModel.onAppResumed()
                                webViewController.resumeLoginDetection()
                            }
                            Lifecycle.Event.ON_PAUSE -> {
                                predictorViewModel.onAppPaused()
                                webViewController.pauseLoginDetection()
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                // Keep WebViewScreen permanently mounted as the base layer so internal navigation
                // to AnalyticsScreen overlays without destroying or reloading the active WebView session.
                Box(modifier = Modifier.fillMaxSize()) {
                    WebViewScreen(
                        viewModel = predictorViewModel,
                        sessionManager = appContainer.websiteSessionManager,
                        webViewController = webViewController,
                        isScreenActive = isWebsiteRouteActive,
                        onNavigateToAnalytics = {
                            navController.navigate(ROUTE_ANALYTICS) {
                                launchSingleTop = true
                            }
                        }
                    )

                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_WEBSITE
                    ) {
                        composable(ROUTE_WEBSITE) {
                            // WebViewScreen remains mounted & interactive in the base layer underneath.
                        }
                        composable(ROUTE_ANALYTICS) {
                            AnalyticsScreen(
                                viewModel = analyticsViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            activeWebViewController?.destroy()
            activeWebViewController = null
        }
        super.onDestroy()
    }
}
