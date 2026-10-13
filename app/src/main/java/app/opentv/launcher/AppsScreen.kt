/* Nuvix modifications, Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppsScreen(onBack: () -> Unit, onLauncherSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember(context) { InstalledAppsRepository(context) }
    val settings = remember(context) { LauncherSettingsStore(context) }
    val controller = remember(context) { LauncherController(context) }
    val preferences by settings.preferences.collectAsState()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<InstalledApp?>(null) }

    DisposableEffect(settings, lifecycleOwner, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            context.unregisterReceiver(receiver)
            lifecycleOwner.lifecycle.removeObserver(observer)
            settings.close()
        }
    }

    LaunchedEffect(refresh) {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { repository.discover() } }
        result.onSuccess {
            apps = it
            settings.pruneFavorites(it.map { app -> app.packageName }.toSet())
            selected = selected?.let { current -> it.find { app -> app.packageName == current.packageName } }
        }.onFailure { message = "Installed apps could not be refreshed. Try again." }
        loading = false
    }

    fun launch(app: InstalledApp) {
        if (!repository.launch(app)) {
            message = "${app.label} is unavailable or cannot be launched. Refreshing installed apps."
            refresh++
        }
        selected = null
    }

    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val favorites = preferences.favoritePackages.mapNotNull { byPackage[it] }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Apps", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            LauncherAction("Launcher", onLauncherSettings)
            LauncherAction("Device settings", {
                val intent = controller.deviceSettingsIntent()
                if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
                    message = "Device settings are unavailable on this device."
                }
            })
            LauncherAction("Done", onBack)
        }
        Text("Hold OK or press Menu on an app for pinning, ordering, and app information.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (favorites.isNotEmpty()) {
            Text("Favorite apps", style = MaterialTheme.typography.titleLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(favorites, key = { it.packageName }) { app ->
                    AppTile(app, pinned = true, onLaunch = { launch(app) }, onMenu = { selected = app }, modifier = Modifier.width(176.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (loading) "Refreshing apps…" else "All apps · ${apps.size}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            LauncherAction("Refresh", { message = null; refresh++ }, enabled = !loading)
        }
        if (apps.isEmpty() && !loading) {
            Text("No launchable apps were found. Apps installed from your device's store appear here automatically.")
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(176.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(apps, key = { it.packageName }) { app ->
                AppTile(app, app.packageName in preferences.favoritePackages, { launch(app) }, { selected = app })
            }
        }
    }

    selected?.let { app ->
        val favoriteIndex = preferences.favoritePackages.indexOf(app.packageName)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(app.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LauncherAction("Open", { launch(app) }, Modifier.fillMaxWidth())
                    LauncherAction(if (favoriteIndex >= 0) "Unpin from favorites" else "Pin to favorites", {
                        if (favoriteIndex >= 0) settings.unpin(app.packageName) else settings.pin(app.packageName)
                        selected = null
                    }, Modifier.fillMaxWidth())
                    if (favoriteIndex >= 0) {
                        LauncherAction("Move left", { settings.moveFavorite(app.packageName, -1) }, Modifier.fillMaxWidth(), enabled = favoriteIndex > 0)
                        LauncherAction("Move right", { settings.moveFavorite(app.packageName, 1) }, Modifier.fillMaxWidth(), enabled = favoriteIndex < favorites.lastIndex)
                    }
                    LauncherAction("App information", {
                        if (!repository.openAppInfo(app.packageName)) message = "App information is unavailable. The app may have been removed."
                        selected = null
                    }, Modifier.fillMaxWidth())
                }
            },
            confirmButton = { LauncherAction("Close", { selected = null }) },
        )
    }
}

/** Home shelf backed by the same local pins and PackageManager discovery as the Apps page. */
@Composable
fun FavoriteAppsRow(onOpenApps: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings = remember(context) { LauncherSettingsStore(context) }
    val repository = remember(context) { InstalledAppsRepository(context) }
    val preferences by settings.preferences.collectAsState()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(settings, lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            settings.close()
        }
    }
    LaunchedEffect(preferences.favoritePackages, refresh) {
        if (preferences.favoritePackages.isEmpty()) apps = emptyList()
        else withContext(Dispatchers.IO) { runCatching { repository.discover() } }.onSuccess { apps = it }
    }
    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val favorites = preferences.favoritePackages.mapNotNull { byPackage[it] }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Installed apps", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            LauncherAction("All apps", onOpenApps)
        }
        if (favorites.isEmpty()) Text("Open Apps to pin your installed favorites here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(favorites, key = { it.packageName }) { app ->
                AppTile(app, true, {
                    if (!repository.launch(app)) { message = "${app.label} is no longer available. Open Apps to refresh."; refresh++ }
                }, onOpenApps, Modifier.width(176.dp))
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: InstalledApp,
    pinned: Boolean,
    onLaunch: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, label = "appTileFocus")
    val shape = RoundedCornerShape(18.dp)
    val icon = remember(app.icon) { runCatching { app.icon?.toBitmap(80, 80)?.asImageBitmap() }.getOrNull() }
    Column(
        modifier
            .height(148.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                val native = it.nativeKeyEvent
                when {
                    native.keyCode == KeyEvent.KEYCODE_MENU && native.action == KeyEvent.ACTION_UP -> { onMenu(); true }
                    native.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && native.action == KeyEvent.ACTION_DOWN && native.repeatCount > 0 -> {
                        if (!longPressHandled) { longPressHandled = true; onMenu() }
                        true
                    }
                    native.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER) && native.action == KeyEvent.ACTION_UP && longPressHandled -> {
                        longPressHandled = false
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(onClick = onLaunch, onLongClick = onMenu, onLongClickLabel = "App options")
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(66.dp))
        else Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(66.dp), tint = MaterialTheme.colorScheme.primary)
        Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (pinned) "Pinned" else if (app.isTvApp) "TV app" else "App", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
