package com.apexai.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.apexai.config.AppConfig
import com.apexai.provider.ProviderManager
import com.apexai.service.ApexService
import com.apexai.service.overlay.OverlayService
import com.apexai.ui.screens.*
import com.apexai.ui.theme.ApexTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appConfig: AppConfig
    @Inject lateinit var providerManager: ProviderManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle debug setup via adb: am start -n com.apexai/.ui.MainActivity
        //   --es provider "openrouter" --es api_key "sk-or-..." --es model "google/gemini-2.5-flash"
        intent?.let { handleSetupIntent(it) }

        // Start foreground service to maintain network access when backgrounded
        if (appConfig.isSetupComplete) {
            startService()
            // Start overlay if enabled and permission granted
            if (appConfig.overlayEnabled && Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, OverlayService::class.java))
            }
        }

        val pendingMessage = intent?.getStringExtra("message")

        setContent {
            ApexTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val startDest = if (appConfig.isSetupComplete) "chat" else "setup"

                    NavHost(navController = navController, startDestination = startDest) {
                        composable("setup") {
                            SetupScreen(
                                onComplete = {
                                    appConfig.isSetupComplete = true
                                    startService()
                                    navController.navigate("chat") {
                                        popUpTo("setup") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("chat") {
                            ChatScreen(
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToSkills = { navController.navigate("skills") },
                                onNavigateToApprovals = { navController.navigate("approvals") },
                                initialMessage = pendingMessage
                            )
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("skills") {
                            SkillsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("approvals") {
                            ApprovalScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    private fun handleSetupIntent(intent: Intent) {
        val provider = intent.getStringExtra("provider") ?: return
        val apiKey = intent.getStringExtra("api_key") ?: return
        val model = intent.getStringExtra("model")

        providerManager.switchProvider(provider)
        providerManager.setApiKey(provider, apiKey)
        if (model != null) {
            appConfig.model = model
        }
        appConfig.isSetupComplete = true
    }

    private fun startService() {
        val intent = Intent(this, ApexService::class.java).apply {
            action = ApexService.ACTION_START
        }
        startForegroundService(intent)
    }
}
