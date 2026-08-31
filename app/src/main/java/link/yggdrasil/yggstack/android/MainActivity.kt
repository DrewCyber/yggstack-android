package link.yggdrasil.yggstack.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.ExposeMapping
import link.yggdrasil.yggstack.android.data.ForwardMapping
import link.yggdrasil.yggstack.android.data.Protocol
import link.yggdrasil.yggstack.android.data.VersionChecker
import link.yggdrasil.yggstack.android.data.VersionInfo
import link.yggdrasil.yggstack.android.service.PeerFetcherService
import link.yggdrasil.yggstack.android.ui.configuration.ConfigurationScreen
import link.yggdrasil.yggstack.android.ui.configuration.ConfigurationViewModel
import link.yggdrasil.yggstack.android.ui.configuration.PendingDeepLink
import link.yggdrasil.yggstack.android.ui.diagnostics.DiagnosticsScreen
import link.yggdrasil.yggstack.android.ui.settings.SettingsScreen
import link.yggdrasil.yggstack.android.ui.theme.YggstackAndroidTheme
import link.yggdrasil.yggstack.android.utils.LocaleHelper
import link.yggdrasil.yggstack.android.utils.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    
    companion object {
        // Temporary screen state that survives activity recreation but not app restart
        var tempSelectedScreen: Int? = null
        // Pending deep link as a StateFlow so Compose reacts every time it is set
        val pendingDeepLinkFlow = MutableStateFlow<PendingDeepLink?>(null)

        fun parseDeepLink(uri: Uri?): PendingDeepLink? {
            if (uri == null || uri.scheme != "https" ||
                !uri.host.equals("DrewCyber.github.io", ignoreCase = true)) return null
            val proto = when (uri.getQueryParameter("proto")?.uppercase()) {
                "UDP" -> Protocol.UDP
                else -> Protocol.TCP
            }
            return when (uri.path) {
                "/mapping/expose" -> {
                    val localPort = uri.getQueryParameter("localPort")?.toIntOrNull() ?: return null
                    val localIp = uri.getQueryParameter("localIp") ?: "127.0.0.1"
                    val yggPort = uri.getQueryParameter("yggPort")?.toIntOrNull() ?: return null
                    val shortName = uri.getQueryParameter("name") ?: ""
                    PendingDeepLink.ExposeLink(ExposeMapping(proto, localPort, localIp, yggPort, shortName))
                }
                "/mapping/forward" -> {
                    val localIp = uri.getQueryParameter("localIp") ?: "127.0.0.1"
                    val localPort = uri.getQueryParameter("localPort")?.toIntOrNull() ?: return null
                    val remoteIp = uri.getQueryParameter("remoteIp") ?: return null
                    val remotePort = uri.getQueryParameter("remotePort")?.toIntOrNull() ?: return null
                    val shortName = uri.getQueryParameter("name") ?: ""
                    PendingDeepLink.ForwardLink(ForwardMapping(proto, localIp, localPort, remoteIp, remotePort, shortName))
                }
                else -> null
            }
        }
    }
    
    private fun registerAutomationShortcuts() {
        val startShortcut = ShortcutInfoCompat.Builder(this, "shortcut_start_yggstack")
            .setShortLabel(getString(R.string.automation_start_label))
            .setLongLabel(getString(R.string.automation_start_description))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(
                Intent(this, link.yggdrasil.yggstack.android.automation.AutomationStartActivity::class.java)
                    .setAction(Intent.ACTION_DEFAULT)
            )
            .build()
        val stopShortcut = ShortcutInfoCompat.Builder(this, "shortcut_stop_yggstack")
            .setShortLabel(getString(R.string.automation_stop_label))
            .setLongLabel(getString(R.string.automation_stop_description))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(
                Intent(this, link.yggdrasil.yggstack.android.automation.AutomationStopActivity::class.java)
                    .setAction(Intent.ACTION_DEFAULT)
            )
            .build()
        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(startShortcut, stopShortcut))
    }

    override fun attachBaseContext(newBase: Context) {
        // Apply saved locale before activity is created
        val repository = ConfigRepository(newBase)
        val language = runBlocking { repository.languageFlow.first() }
        val localeContext = LocaleHelper.applyLocale(newBase, language)
        super.attachBaseContext(localeContext)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-frame layout: content draws behind transparent system bars
        // instead of the system painting a colored status bar strip
        enableEdgeToEdge()
        registerAutomationShortcuts()
        parseDeepLink(intent?.data)?.let { pendingDeepLinkFlow.value = it }

        val repository = ConfigRepository(this)
        // Read the current theme/colors synchronously so the very first frame
        // is rendered with the correct scheme (avoids a visible flash on launch).
        val initialTheme = runBlocking { repository.themeFlow.first() }
        val initialUseSystemColors = runBlocking { repository.useSystemColorsFlow.first() }

        setContent {
            val theme by repository.themeFlow.collectAsState(initial = initialTheme)
            val useSystemColors by repository.useSystemColorsFlow.collectAsState(initial = initialUseSystemColors)
            val systemInDarkTheme = isSystemInDarkTheme()
            
            val darkTheme = when (theme) {
                "light" -> false
                "dark" -> true
                else -> systemInDarkTheme
            }
            
            YggstackAndroidTheme(
                darkTheme = darkTheme,
                dynamicColor = useSystemColors
            ) {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseDeepLink(intent.data)?.let { pendingDeepLinkFlow.value = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val applicationContext = context.applicationContext
    val repository = ConfigRepository(applicationContext)
    val configViewModel: ConfigurationViewModel = viewModel(
        factory = ConfigurationViewModel.Factory(repository, applicationContext)
    )

    // Use temp screen from companion object if available (after recreation), otherwise default to 0
    var selectedScreen by remember { mutableStateOf(MainActivity.tempSelectedScreen ?: 0) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionsChecked by remember { mutableStateOf(false) }
    var versionInfo by remember { mutableStateOf<VersionInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun showAvailableUpdate(update: VersionInfo) {
        versionInfo = update
        showUpdateDialog = true
    }
    
    // Clear temp screen after using it
    LaunchedEffect(Unit) {
        MainActivity.tempSelectedScreen = null
    }

    // Consume any pending deep link and forward it to the ViewModel
    LaunchedEffect(Unit) {
        MainActivity.pendingDeepLinkFlow.collect { link ->
            if (link != null) {
                MainActivity.pendingDeepLinkFlow.value = null
                selectedScreen = 0 // switch to Configuration tab
                configViewModel.setPendingDeepLink(link)
            }
        }
    }
    
    // Save current screen to temp variable when it changes (for language change recreation)
    LaunchedEffect(selectedScreen) {
        MainActivity.tempSelectedScreen = selectedScreen
    }

    // Notification permission launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // After notification permission is handled, check all permissions again
        if (!PermissionHelper.hasAllBackgroundPermissions(context)) {
            showPermissionDialog = true
        }
    }

    // Check permissions and version on startup
    LaunchedEffect(Unit) {
        // First, request notification permission if needed (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
            !PermissionHelper.isNotificationPermissionGranted(context)) {
            notificationPermissionLauncher.launch(PermissionHelper.NOTIFICATION_PERMISSION)
        } else if (!PermissionHelper.hasAllBackgroundPermissions(context)) {
            showPermissionDialog = true
        }
        permissionsChecked = true
        
        // Check and download public peers cache if needed
        coroutineScope.launch {
            val configRepository = ConfigRepository(context)
            if (!configRepository.hasPublicPeersCache()) {
                val peerFetcher = PeerFetcherService()

                // Immediately seed from the bundled asset so the user is never left empty-handed
                val bundledResult = peerFetcher.loadBundledPeers(context)
                if (bundledResult.isSuccess) {
                    val bundledPeers = bundledResult.getOrNull() ?: emptyList()
                    if (bundledPeers.isNotEmpty()) {
                        configRepository.savePublicPeersCache(
                            link.yggdrasil.yggstack.android.data.PublicPeersCache(
                                peers = bundledPeers,
                                downloadedAt = null  // null marks it as the bundled seed
                            )
                        )
                    }
                }

                // Then try to refresh from network and overwrite with up-to-date data
                try {
                    val result = peerFetcher.fetchPublicPeers()
                    if (result.isSuccess) {
                        val peers = result.getOrNull() ?: emptyList()
                        configRepository.savePublicPeersCache(
                            link.yggdrasil.yggstack.android.data.PublicPeersCache(
                                peers = peers,
                                downloadedAt = System.currentTimeMillis()
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Silent fail - bundled peers are already in cache
                }
            }
        }
        
        // Check for updates (only if auto-update is enabled)
        coroutineScope.launch {
            val configRepository = ConfigRepository(context)
            val autoUpdateEnabled = configRepository.autoUpdateFlow.first()
            
            if (autoUpdateEnabled) {
                val versionChecker = VersionChecker(context)
                if (versionChecker.shouldCheckForUpdate()) {
                    versionChecker.checkForUpdate()?.let { update ->
                        versionInfo = update
                        showUpdateDialog = true
                    }
                }
            }
        }
    }

    // Permission dialog
    if (showPermissionDialog) {
        val needsNotification = !PermissionHelper.isNotificationPermissionGranted(context)
        val needsBattery = !PermissionHelper.isBatteryOptimizationDisabled(context)
        val needsBackground = PermissionHelper.isBackgroundRestricted(context)
        
        BackgroundPermissionDialog(
            needsNotification = needsNotification,
            needsBattery = needsBattery,
            needsBackground = needsBackground,
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = {
                showPermissionDialog = false
                // Prioritize based on what's needed
                try {
                    when {
                        needsNotification -> {
                            context.startActivity(PermissionHelper.getNotificationSettingsIntent(context))
                        }
                        needsBattery -> {
                            context.startActivity(PermissionHelper.getBatteryOptimizationIntent(context))
                        }
                        else -> {
                            context.startActivity(PermissionHelper.getAppInfoIntent(context))
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to app info settings
                    context.startActivity(PermissionHelper.getAppInfoIntent(context))
                }
            }
        )
    }
    
    // Update dialog
    if (showUpdateDialog && versionInfo != null) {
        UpdateAvailableDialog(
            versionInfo = versionInfo!!,
            onDismiss = {
                showUpdateDialog = false
                coroutineScope.launch {
                    VersionChecker(context).postponeVersion(versionInfo!!.latestVersion)
                }
            },
            onDownload = {
                showUpdateDialog = false
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(versionInfo!!.downloadUrl))
                context.startActivity(intent)
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_configuration)) },
                    selected = selectedScreen == 0,
                    onClick = { selectedScreen = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_diagnostics)) },
                    selected = selectedScreen == 1,
                    onClick = { selectedScreen = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_info)) },
                    selected = selectedScreen == 2,
                    onClick = { selectedScreen = 2 }
                )
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedScreen) {
                0 -> ConfigurationScreen(viewModel = configViewModel)
                1 -> DiagnosticsScreen()
                2 -> SettingsScreen(
                    onCheckForUpdate = {
                        coroutineScope.launch {
                            val update = VersionChecker(context).checkForUpdate(force = true)
                            if (update != null) {
                                showAvailableUpdate(update)
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.no_updates_available),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun BackgroundPermissionDialog(
    needsNotification: Boolean,
    needsBattery: Boolean,
    needsBackground: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.permission_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.permission_dialog_message))
                Spacer(modifier = Modifier.height(8.dp))
                
                if (needsNotification) {
                    Text(
                        text = "• " + stringResource(R.string.permission_notifications_required),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (needsBattery) {
                    Text(
                        text = "• " + stringResource(R.string.permission_battery_required),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (needsBackground) {
                    Text(
                        text = "• " + stringResource(R.string.permission_background_required),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.permission_dialog_instructions),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.permission_dialog_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.permission_dialog_later))
            }
        }
    )
}

@Composable
fun UpdateAvailableDialog(
    versionInfo: VersionInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text(stringResource(R.string.new_version_available)) },
        text = {
            Box(modifier = Modifier.heightIn(min = 0.dp, max = 320.dp).verticalScroll(rememberScrollState())) {
                Column {
                    Text(
                        text = "Version ${versionInfo.latestVersion} is available",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Current version: ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (versionInfo.releaseNotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Release Notes:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = versionInfo.releaseNotes,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload) {
                Text(stringResource(R.string.download_update))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later))
            }
        }
    )
}

