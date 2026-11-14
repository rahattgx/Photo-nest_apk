package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.Coil
import com.example.client.BackupSyncWorker
import com.example.core.data.GoogleAuthHelper
import com.example.core.data.authStateFlow
import com.example.core.model.OperatingMode
import com.example.core.security.PhotoNestImageLoader
import com.example.server.PhotoNestServerService
import com.example.ui.PhotoNestViewModel
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BackupSettingsScreen
import com.example.ui.screens.CollectionsScreen
import com.example.ui.screens.CreateScreen
import com.example.ui.screens.MediaViewerScreen
import com.example.ui.screens.ModeSelectionScreen
import com.example.ui.screens.PhotosScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.ServerDashboardScreen
import com.example.ui.screens.ServerPairingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.PhotoNestTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth

private object Routes {
    const val MODE_SELECT = "mode_select"
    const val SERVER_DASHBOARD = "server_dashboard"
    const val CLIENT_PHOTOS = "client_photos"
    const val CLIENT_COLLECTIONS = "client_collections"
    const val CLIENT_CREATE = "client_create"
    const val CLIENT_SEARCH = "client_search"
    const val BACKUP_SETTINGS = "backup_settings"
    const val SERVER_PAIRING = "server_pairing"
    const val SETTINGS = "settings"
    const val MEDIA_VIEWER = "media_viewer/{mediaId}"

    fun mediaViewer(mediaId: String) = "media_viewer/$mediaId"
}

private data class ClientNavTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

private val CLIENT_TABS = listOf(
    ClientNavTab(Routes.CLIENT_PHOTOS, R.string.nav_photos, Icons.Rounded.PhotoLibrary),
    ClientNavTab(Routes.CLIENT_COLLECTIONS, R.string.nav_collections, Icons.Rounded.CollectionsBookmark),
    ClientNavTab(Routes.CLIENT_CREATE, R.string.nav_create, Icons.Rounded.AutoAwesome),
    ClientNavTab(Routes.CLIENT_SEARCH, R.string.nav_search, Icons.Rounded.Search)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Coil.setImageLoader(PhotoNestImageLoader.build(applicationContext))
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val credentialManager = remember(context) { CredentialManager.create(context) }
            val currentUser by Firebase.auth.authStateFlow()
                .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

            LaunchedEffect(Unit) {
                GoogleAuthHelper.attemptAutoSignIn(
                    context = context,
                    credentialManager = credentialManager,
                    onAuthSuccess = {},
                    onUnauthenticated = {},
                    scope = scope
                )
            }

            if (currentUser == null) {
                PhotoNestTheme {
                    AuthScreen(onAuthSuccess = {})
                }
            } else {
                val user = currentUser!!
                key(user.uid) {
                    val viewModel: PhotoNestViewModel = viewModel(
                        key = user.uid,
                        factory = PhotoNestViewModel.factory(user.uid)
                    )
                    val settings by viewModel.settings.collectAsStateWithLifecycle()

                    PhotoNestTheme(themePreference = settings.appTheme) {
                        PhotoNestRootApp(
                            user = user,
                            viewModel = viewModel,
                            onSignOut = {
                                PhotoNestServerService.stop(context)
                                BackupSyncWorker.cancelPeriodic(context)
                                GoogleAuthHelper.signOut(credentialManager, scope)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoNestRootApp(
    user: FirebaseUser,
    viewModel: PhotoNestViewModel,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val loadedSettings by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    val settings = loadedSettings ?: run {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val activeMedia by viewModel.activeMedia.collectAsStateWithLifecycle()
    val allNonTrashed by viewModel.allNonTrashedMedia.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val screenshots by viewModel.screenshots.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val archived by viewModel.archived.collectAsStateWithLifecycle()
    val trashed by viewModel.trashed.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val albumCrossRefs by viewModel.albumCrossRefs.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val faceTags by viewModel.faceTags.collectAsStateWithLifecycle()
    val backupQueue by viewModel.backupQueue.collectAsStateWithLifecycle()
    val pairedServers by viewModel.pairedServers.collectAsStateWithLifecycle()
    val firestoreServers by viewModel.firestoreServers.collectAsStateWithLifecycle()
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val serverRuntime by viewModel.serverRuntime.collectAsStateWithLifecycle()
    val lanServers by viewModel.lanServers.collectAsStateWithLifecycle()
    val storageVolumes by viewModel.storageVolumes.collectAsStateWithLifecycle()
    val selectedStorage by viewModel.selectedServerStorage.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()

    val activeServer = remember(pairedServers, settings.activeServerId) {
        pairedServers.firstOrNull { it.serverId == settings.activeServerId } ?: pairedServers.firstOrNull()
    }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    val startDestination = remember(user.uid) {
        when (settings.operatingMode) {
            OperatingMode.UNSET -> Routes.MODE_SELECT
            OperatingMode.SERVER -> Routes.SERVER_DASHBOARD
            OperatingMode.CLIENT -> Routes.CLIENT_PHOTOS
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showClientTabs = currentRoute in CLIENT_TABS.map { it.route }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (showClientTabs && !isWideScreen) {
                    NavigationBar {
                        CLIENT_TABS.forEach { tab ->
                            val selected = currentRoute == tab.route
                            val label = stringResource(tab.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showClientTabs && isWideScreen) {
                    NavigationRail {
                        CLIENT_TABS.forEach { tab ->
                            val selected = currentRoute == tab.route
                            val label = stringResource(tab.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = startDestination
                    ) {
                        composable(Routes.MODE_SELECT) {
                            ModeSelectionScreen(
                                userDisplayName = user.displayName.orEmpty(),
                                onSelectMode = { mode ->
                                    viewModel.selectOperatingMode(mode)
                                    val dest = if (mode == OperatingMode.SERVER) {
                                        Routes.SERVER_DASHBOARD
                                    } else {
                                        Routes.CLIENT_PHOTOS
                                    }
                                    navController.navigate(dest) {
                                        popUpTo(Routes.MODE_SELECT) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Routes.SERVER_DASHBOARD) {
                            ServerDashboardScreen(
                                settings = settings,
                                runtime = serverRuntime,
                                selectedStorage = selectedStorage,
                                availableVolumes = storageVolumes,
                                pairedDevices = pairedDevices,
                                backedUpMedia = allNonTrashed,
                                onStartServer = { viewModel.startServer() },
                                onStopServer = { viewModel.stopServer() },
                                onSelectSafFolder = { viewModel.setServerStorageFolder(it) },
                                onRotatePairingCode = { viewModel.rotateServerPairingCode() },
                                onRevokeDevice = { viewModel.revokePairedDevice(it) },
                                onOpenClientGallery = { navController.navigate(Routes.CLIENT_PHOTOS) },
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onPickTestPhotosToVault = { viewModel.addPickedUrisToBackupAndLibrary(it) },
                                onShowMessage = { viewModel.showMessage(it) }
                            )
                        }

                        composable(Routes.CLIENT_PHOTOS) {
                            PhotosScreen(
                                userPhotoUrl = user.photoUrl?.toString(),
                                userName = user.displayName ?: "PhotoNest User",
                                mediaItems = activeMedia,
                                backupQueue = backupQueue,
                                activeServer = activeServer,
                                settings = settings,
                                onOpenMedia = { media -> navController.navigate(Routes.mediaViewer(media.id)) },
                                onPickMedia = { viewModel.addPickedUrisToBackupAndLibrary(it) },
                                onSyncNow = { viewModel.syncCatalogFromServer(activeServer) },
                                onRetryFailed = { viewModel.retryFailedBackups() },
                                onOpenPairing = { navController.navigate(Routes.SERVER_PAIRING) },
                                onOpenBackupSettings = { navController.navigate(Routes.BACKUP_SETTINGS) },
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onFavoriteSelected = { list -> list.forEach { viewModel.toggleFavorite(it) } },
                                onTrashSelected = { list -> list.forEach { viewModel.moveToTrash(it) } },
                                onDownloadSelected = { list -> list.forEach { viewModel.downloadOriginal(it) } },
                                onCreateAlbumFromSelected = { name, list ->
                                    viewModel.createAlbum(name, list.map { it.id })
                                }
                            )
                        }

                        composable(Routes.CLIENT_COLLECTIONS) {
                            CollectionsScreen(
                                allNonTrashed = allNonTrashed,
                                favorites = favorites,
                                videos = videos,
                                screenshots = screenshots,
                                documents = documents,
                                archived = archived,
                                trashed = trashed,
                                albums = albums,
                                albumCrossRefs = albumCrossRefs,
                                people = people,
                                faceTags = faceTags,
                                activeServer = activeServer,
                                onOpenMedia = { media -> navController.navigate(Routes.mediaViewer(media.id)) },
                                onCreateAlbum = { name -> viewModel.createAlbum(name, emptyList()) },
                                onDeleteAlbum = { albumId -> viewModel.deleteAlbum(albumId) },
                                onRunFaceScan = { viewModel.runOnDeviceFaceScan() },
                                onRenamePerson = { id, name -> viewModel.renamePerson(id, name) },
                                onUpdatePersonCategory = { id, cat -> viewModel.updatePersonCategory(id, cat) },
                                onTagManualPerson = { name, cat, sample ->
                                    viewModel.addManualPersonTag(name, cat, sample)
                                },
                                onRestoreFromTrash = { viewModel.restoreFromTrash(it) },
                                onDeletePermanently = { viewModel.deletePermanently(it) }
                            )
                        }

                        composable(Routes.CLIENT_CREATE) {
                            CreateScreen(
                                mediaItems = allNonTrashed,
                                activeServer = activeServer,
                                onCreateCollage = { viewModel.createAndSaveCollage(it) },
                                onCreateAlbum = { name, ids -> viewModel.createAlbum(name, ids) },
                                onShowMessage = { viewModel.showMessage(it) }
                            )
                        }

                        composable(Routes.CLIENT_SEARCH) {
                            SearchScreen(
                                mediaItems = allNonTrashed,
                                albums = albums,
                                albumCrossRefs = albumCrossRefs,
                                people = people,
                                faceTags = faceTags,
                                activeServer = activeServer,
                                aiSearchEnabled = settings.onDeviceAiSearchEnabled,
                                onToggleAiSearch = { enabled ->
                                    viewModel.updateAppPreferences(aiSearchEnabled = enabled)
                                },
                                onOpenMedia = { media -> navController.navigate(Routes.mediaViewer(media.id)) }
                            )
                        }

                        composable(Routes.BACKUP_SETTINGS) {
                            BackupSettingsScreen(
                                settings = settings,
                                backupQueue = backupQueue,
                                onBack = { navController.popBackStack() },
                                onUpdateSettings = { auto, scope, wifi, mobile, charging, screenOff, paused, quality ->
                                    viewModel.updateBackupSettings(
                                        autoBackupEnabled = auto,
                                        backupScope = scope,
                                        wifiOnly = wifi,
                                        mobileDataAllowed = mobile,
                                        chargingOnly = charging,
                                        screenOffBackup = screenOff,
                                        isPaused = paused,
                                        uploadQuality = quality
                                    )
                                },
                                onAddSourceFolder = { viewModel.addSourceBackupFolder(it) },
                                onRemoveSourceFolder = { viewModel.removeSourceBackupFolder(it) },
                                onAddExcludedFolder = { viewModel.addExcludedFolder(it) },
                                onRemoveExcludedFolder = { viewModel.removeExcludedFolder(it) },
                                onTriggerBackupNow = { viewModel.triggerImmediateBackup() },
                                onRetryFailed = { viewModel.retryFailedBackups() },
                                onClearCompleted = { viewModel.clearCompletedQueue() }
                            )
                        }

                        composable(Routes.SERVER_PAIRING) {
                            ServerPairingScreen(
                                currentUserId = user.uid,
                                serverRuntime = serverRuntime,
                                lanServers = lanServers,
                                firestoreServers = firestoreServers,
                                isBusy = isBusy,
                                onBack = { navController.popBackStack() },
                                onPairWithPayload = { raw, hostOverride ->
                                    viewModel.pairFromQrOrJson(raw, hostOverride)
                                },
                                onPairLocalServer = { viewModel.pairWithLocalRunningServer() }
                            )
                        }

                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                userDisplayName = user.displayName.orEmpty(),
                                userEmail = user.email.orEmpty(),
                                userPhotoUrl = user.photoUrl?.toString(),
                                settings = settings,
                                selectedStorage = selectedStorage,
                                pairedDevices = pairedDevices,
                                tlsFingerprint = serverRuntime.certFingerprint.ifBlank {
                                    viewModel.tlsCertManager.getOrCreateServerTlsMaterial().fingerprintSha256
                                },
                                onBack = { navController.popBackStack() },
                                onSignOut = onSignOut,
                                onSelectMode = { mode ->
                                    viewModel.selectOperatingMode(mode)
                                    val dest = if (mode == OperatingMode.SERVER) {
                                        Routes.SERVER_DASHBOARD
                                    } else {
                                        Routes.CLIENT_PHOTOS
                                    }
                                    navController.navigate(dest) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            inclusive = true
                                        }
                                    }
                                },
                                onUpdateServerConfig = { name, port, relay, endpoint ->
                                    viewModel.updateServerConfig(name, port, relay, endpoint)
                                },
                                onSelectSafFolder = { viewModel.setServerStorageFolder(it) },
                                onOpenBackupSettings = { navController.navigate(Routes.BACKUP_SETTINGS) },
                                onOpenPairing = { navController.navigate(Routes.SERVER_PAIRING) },
                                onUpdateUploadQuality = { q ->
                                    viewModel.updateBackupSettings(uploadQuality = q)
                                },
                                onUpdateNetworkPrefs = { wifi, mobile ->
                                    viewModel.updateBackupSettings(wifiOnly = wifi, mobileDataAllowed = mobile)
                                },
                                onRevokeDevice = { viewModel.revokePairedDevice(it) },
                                onUpdatePrivacyAi = { faces, ai ->
                                    viewModel.updateAppPreferences(faceGroupingEnabled = faces, aiSearchEnabled = ai)
                                },
                                onUpdateTheme = { pref -> viewModel.updateAppPreferences(appTheme = pref) },
                                onToggleNotifications = { enabled ->
                                    viewModel.updateAppPreferences(notificationsEnabled = enabled)
                                }
                            )
                        }

                        composable(
                            route = Routes.MEDIA_VIEWER,
                            arguments = listOf(navArgument("mediaId") { type = NavType.StringType })
                        ) { entry ->
                            val mediaId = entry.arguments?.getString("mediaId").orEmpty()
                            val pool = remember(allNonTrashed, trashed, mediaId) {
                                if (trashed.any { it.id == mediaId }) trashed else allNonTrashed
                            }
                            MediaViewerScreen(
                                items = pool,
                                initialMediaId = mediaId,
                                activeServer = activeServer,
                                onBack = { navController.popBackStack() },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onToggleArchive = { viewModel.toggleArchive(it) },
                                onMoveToTrash = { viewModel.moveToTrash(it) },
                                onDownloadOriginal = { viewModel.downloadOriginal(it) },
                                onShare = { item ->
                                    viewModel.prepareShareIntent(item) { intent ->
                                        context.startActivity(intent)
                                    }
                                },
                                onSaveEditedCopy = { orig, bmp ->
                                    viewModel.saveEditedPhotoCopy(orig, bmp)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
