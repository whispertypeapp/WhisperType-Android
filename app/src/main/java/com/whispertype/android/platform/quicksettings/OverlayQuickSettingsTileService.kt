package com.whispertype.android.platform.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.whispertype.android.R
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.platform.runtime.FlowRuntimeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings control for the existing app-enabled kill switch.
 *
 * The tile and Settings write the same DataStore preference, so either surface
 * immediately controls the same overlay runtime. Enabling from the tile also
 * starts the runtime when overlay permission is already granted; if permission
 * is missing, Android's overlay settings are opened instead of pretending the
 * bubble is active. The tile icon ([R.drawable.ic_qs_tile]) is a circular
 * render of the app logo — the white mic + keyboard on the blue-teal gradient
 * — so the button looks like the logo, not a square bitmap.
 */
@Suppress("StartActivityAndCollapseDeprecated")
class OverlayQuickSettingsTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings by lazy { SettingsRepository(this) }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        serviceScope.launch {
            val enabled = settings.appEnabled.first()
            if (enabled) {
                settings.setAppEnabled(false)
                updateTile(false)
            } else if (Settings.canDrawOverlays(this@OverlayQuickSettingsTileService)) {
                settings.setAppEnabled(true)
                ContextCompat.startForegroundService(
                    this@OverlayQuickSettingsTileService,
                    Intent(this@OverlayQuickSettingsTileService, FlowRuntimeService::class.java),
                )
                updateTile(true)
            } else {
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri(),
                )
                val permissionIntent = PendingIntent.getActivity(
                    this@OverlayQuickSettingsTileService,
                    0,
                    overlayIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(permissionIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(overlayIntent)
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        serviceScope.launch {
            updateTile(settings.appEnabled.first())
        }
    }

    private fun updateTile(enabled: Boolean) {
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.quick_settings_overlay_label)
            contentDescription = getString(
                if (enabled) {
                    R.string.quick_settings_overlay_on
                } else {
                    R.string.quick_settings_overlay_off
                },
            )
            updateTile()
        }
    }
}