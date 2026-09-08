package com.whispertype.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.whispertype.android.data.history.EncryptedHistoryRepository
import com.whispertype.android.data.secrets.AndroidKeystoreKeyStore
import com.whispertype.android.data.secrets.FileBlobStore
import com.whispertype.android.data.secrets.JavaxAesGcmCipher
import com.whispertype.android.data.secrets.KeystoreKeyProvider
import com.whispertype.android.data.secrets.KeyProvider
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.accessibility.SetupStatus
import com.whispertype.android.platform.accessibility.WhisperTypeAccessibilityService
import com.whispertype.android.platform.runtime.FlowRuntimeService
import com.whispertype.android.platform.updates.AppUpdateChecker
import com.whispertype.android.ui.dictionary.DictionaryScreen
import com.whispertype.android.ui.history.HistoryScreen
import com.whispertype.android.ui.home.HomeScreen
import com.whispertype.android.ui.onboarding.OnboardingWizard
import com.whispertype.android.ui.settings.SettingsScreen
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioType
import com.whispertype.android.ui.theme.WhisperTypeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val keyProvider by lazy { KeystoreKeyProvider(applicationContext) }
    private val historyRepository by lazy {
        EncryptedHistoryRepository(
            keystore = AndroidKeystoreKeyStore(EncryptedHistoryRepository.DEFAULT_KEY_ALIAS),
            cipher = JavaxAesGcmCipher(),
            blobStore = FileBlobStore(applicationContext, EncryptedHistoryRepository.DEFAULT_FILE_NAME),
            retentionDays = { cachedHistoryRetentionDays },
        )
    }
    @Volatile
    private var cachedHistoryRetentionDays = SettingsRepository.DEFAULT_RETENTION_DAYS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhisperTypeTheme {
                var overlayGranted by remember { mutableStateOf(canDrawOverlays()) }
                val onboardingDone by settingsRepository.onboardingCompleted.collectAsState(initial = false)
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            overlayGranted = canDrawOverlays()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { }
                val scope = rememberCoroutineScope()
                if (overlayGranted && onboardingDone) {
                    AppShell(
                        settings = settingsRepository,
                        keyProvider = keyProvider,
                        historyRepository = historyRepository,
                        isAccessibilityEnabled = ::isAccessibilityEnabled,
                        onRequestOverlay = ::requestOverlayPermission,
                        onRequestMic = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        },
                        onRequestNotifications = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        },
                        onOpenAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenAppInfo = { openAppInfo() },
                    )
                } else {
                    OnboardingWizard(
                        overlayGranted = overlayGranted,
                        keyProvider = keyProvider,
                        hasMic = ::hasMicPermission,
                        hasAccessibility = ::isAccessibilityEnabled,
                        onRequestOverlay = ::requestOverlayPermission,
                        onRequestMicNotifications = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ),
                            )
                        },
                        onOpenAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenAppInfo = { openAppInfo() },
                        onContinue = {
                            scope.launch { settingsRepository.setOnboardingCompleted(true) }
                        },
                    )
                }
            }
        }
        if (canDrawOverlays()) {
            startRuntime()
        }
        lifecycleScope.launch {
            settingsRepository.appEnabled.collect { enabled ->
                if (enabled && canDrawOverlays() && !FlowRuntimeService.isRunning) {
                    startRuntime()
                }
            }
        }
        lifecycleScope.launch {
            settingsRepository.historyRetentionDays.collect { cachedHistoryRetentionDays = it }
        }
        lifecycleScope.launch {
            AppUpdateChecker.checkForUpdate(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        if (canDrawOverlays()) {
            startRuntime()
        }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            ),
        )
    }

    private fun openAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun startRuntime() {
        lifecycleScope.launch {
            if (settingsRepository.appEnabled.first()) {
                startForegroundService(Intent(this@MainActivity, FlowRuntimeService::class.java))
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${WhisperTypeAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun AppShell(
        settings: SettingsRepository,
        keyProvider: KeyProvider,
        historyRepository: EncryptedHistoryRepository,
        isAccessibilityEnabled: () -> Boolean,
        onRequestOverlay: () -> Unit,
        onRequestMic: () -> Unit,
        onRequestNotifications: () -> Unit,
        onOpenAccessibility: () -> Unit,
        onOpenAppInfo: () -> Unit,
    ) {
        var selectedTab by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        var openSystemPage by remember { mutableStateOf(false) }
        var settingsScrollToGemini by remember { mutableStateOf(false) }
        val historyEntries by historyRepository.events().collectAsState(initial = emptyList())
        val historyEnabled by settings.historyEnabled.collectAsState(initial = true)
        val appEnabled by settings.appEnabled.collectAsState(initial = true)
        val updateRelease by AppUpdateChecker.latestRelease.collectAsState()
        val eligibility by produceState(initialValue = FlowRuntimeService.currentEligibility) {
            while (true) {
                value = FlowRuntimeService.currentEligibility
                delay(1000)
            }
        }
        val overlayGrantedNow = canDrawOverlays()
        val runtimeRunning = FlowRuntimeService.isRunning
        val notificationsGranted = hasNotificationPermission()
        val setupBannerReasons = remember(
            eligibility,
            appEnabled,
            overlayGrantedNow,
            runtimeRunning,
            notificationsGranted,
        ) {
            SetupStatus.homeBannerReasons(
                eligibility = eligibility,
                overlayGranted = overlayGrantedNow,
                runtimeRunning = runtimeRunning,
                notificationsGranted = notificationsGranted,
            ).toMutableList().apply {
                if (!appEnabled && !contains(com.whispertype.android.platform.accessibility.EligibilityExplanation.REASON_APP_DISABLED)) {
                    add(com.whispertype.android.platform.accessibility.EligibilityExplanation.REASON_APP_DISABLED)
                }
            }
        }
        val apiKeyConfigured = keyProvider.hasKey()
        val systemGates = remember(
            eligibility,
            overlayGrantedNow,
            runtimeRunning,
            notificationsGranted,
            apiKeyConfigured,
        ) {
            SetupStatus.systemGateReasons(
                eligibility = eligibility,
                overlayGranted = overlayGrantedNow,
                runtimeRunning = runtimeRunning,
                notificationsGranted = notificationsGranted,
                apiKeyConfigured = apiKeyConfigured,
            )
        }
        if (selectedTab != 0) {
            BackHandler { selectedTab = 0 }
        }
        val tabColors = NavigationBarItemDefaults.colors(
            selectedIconColor = StudioColors.Accent,
            selectedTextColor = StudioColors.Accent,
            unselectedIconColor = StudioColors.OnSurfaceVariant.copy(alpha = 0.45f),
            unselectedTextColor = StudioColors.OnSurfaceVariant.copy(alpha = 0.45f),
            indicatorColor = StudioColors.AccentSoft,
        )
        Scaffold(
            containerColor = StudioColors.Background,
            bottomBar = {
                NavigationBar(
                    containerColor = StudioColors.Surface,
                    tonalElevation = 0.dp,
                    windowInsets = NavigationBarDefaults.windowInsets,
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_home), style = StudioType.tabLabel) },
                        colors = tabColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_history), style = StudioType.tabLabel) },
                        colors = tabColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Outlined.AutoStories, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_dictionary), style = StudioType.tabLabel) },
                        colors = tabColors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings), style = StudioType.tabLabel) },
                        colors = tabColors,
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (selectedTab) {
                    1 -> HistoryScreen(
                        historyRepository = historyRepository,
                        onCopied = { msg -> Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() },
                    )
                    2 -> DictionaryScreen(settings = settings)
                    3 -> SettingsScreen(
                        settings = settings,
                        keyProvider = keyProvider,
                        scrollToGemini = settingsScrollToGemini,
                        onGeminiScrollDone = { settingsScrollToGemini = false },
                        openSystemPage = openSystemPage,
                        onSystemPageOpened = { openSystemPage = false },
                        systemGates = systemGates,
                        onFixGate = { gateId ->
                            when (gateId) {
                                "overlay" -> onRequestOverlay()
                                "runtime" -> startRuntime()
                                "accessibility" -> onOpenAccessibility()
                                "gemini_key" -> {
                                    settingsScrollToGemini = true
                                    selectedTab = 3
                                }
                                "microphone" -> onRequestMic()
                                "notifications" -> onRequestNotifications()
                            }
                        },
                    )
                    else -> HomeScreen(
                        historyEnabled = historyEnabled,
                        entries = historyEntries,
                        setupBannerReasons = setupBannerReasons,
                        updateRelease = updateRelease,
                        onDownloadUpdate = { downloadUrl ->
                            val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri()).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(intent)
                        },
                        onSetupBannerTap = {
                            openSystemPage = true
                            selectedTab = 3
                        },
                        onOpenHistory = { selectedTab = 1 },
                        onEnableHistory = { scope.launch { settings.setHistoryEnabled(true) } },
                    )
                }
            }
        }
    }
}
