package com.whispertype.android.platform.updates

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.util.Log
import com.whispertype.android.BuildConfig
import com.whispertype.android.R
import com.whispertype.android.core.updates.AppReleaseInfo
import com.whispertype.android.core.updates.SemanticVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Handles checking GitHub for new app releases, surfacing update notifications,
 * and providing download links directly to the browser for automatic APK download.
 */
object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"
    const val GITHUB_OWNER = "chaosmanage"
    const val GITHUB_REPO = "WhisperType-Android"
    const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    const val NOTIFICATION_CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 2001
    private const val NOTIFICATION_REQUEST_CODE = 3001

    /** Minimum cooldown between automatic background update checks to stay well within GitHub rate limits. */
    private const val AUTO_CHECK_COOLDOWN_MS = 4 * 60 * 60 * 1000L // 4 hours

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val _latestRelease = MutableStateFlow<AppReleaseInfo?>(null)
    val latestRelease: StateFlow<AppReleaseInfo?> = _latestRelease.asStateFlow()

    private var lastCheckTimeMs = 0L
    private var lastNotifiedTag: String? = null

    /**
     * Checks GitHub for the latest release.
     *
     * @param context Android context for resources and notifications.
     * @param force When true, bypasses the automatic rate-limit cooldown.
     * @param notifyUserOnNew When true, raises a system notification if a newer version is found.
     * @return [AppReleaseInfo] if found and parsed, null otherwise.
     */
    suspend fun checkForUpdate(
        context: Context,
        force: Boolean = false,
        notifyUserOnNew: Boolean = true,
    ): AppReleaseInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCheckTimeMs < AUTO_CHECK_COOLDOWN_MS)) {
            return@withContext _latestRelease.value
        }
        lastCheckTimeMs = now

        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "WhisperType-Android/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub releases API returned HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body.string()
                val root = jsonParser.parseToJsonElement(body).jsonObject

                val tagName = root["tag_name"]?.jsonPrimitive?.content ?: return@withContext null
                val releaseName = root["name"]?.jsonPrimitive?.content ?: tagName
                val htmlUrl = root["html_url"]?.jsonPrimitive?.content
                    ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
                val releaseBody = root["body"]?.jsonPrimitive?.content.orEmpty()

                // Find the release APK download URL from assets
                val assets = root["assets"]?.jsonArray
                var apkDownloadUrl: String? = null
                if (assets != null) {
                    for (assetElem in assets) {
                        val assetObj = assetElem.jsonObject
                        val assetName = assetObj["name"]?.jsonPrimitive?.content.orEmpty()
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            val downloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content
                            if (downloadUrl != null) {
                                apkDownloadUrl = downloadUrl
                                if (assetName.contains("release", ignoreCase = true)) {
                                    break // Best match found
                                }
                            }
                        }
                    }
                }

                val finalDownloadUrl = apkDownloadUrl ?: htmlUrl
                val isNewer = SemanticVersion.isNewer(tagName, BuildConfig.VERSION_NAME)
                val cleanVersion = tagName.removePrefix("v").removePrefix("V")

                val releaseInfo = AppReleaseInfo(
                    tagName = tagName,
                    versionName = cleanVersion,
                    releaseTitle = releaseName,
                    releaseNotes = releaseBody,
                    releaseUrl = htmlUrl,
                    downloadUrl = finalDownloadUrl,
                    isNewerThanCurrent = isNewer,
                )

                _latestRelease.value = releaseInfo

                if (isNewer && notifyUserOnNew && lastNotifiedTag != tagName) {
                    lastNotifiedTag = tagName
                    postUpdateNotification(context.applicationContext, releaseInfo)
                }

                releaseInfo
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            null
        }
    }

    /**
     * Posts a notification prompting the user to download the update.
     * When tapped, Android opens the browser directly to the APK download link,
     * which automatically downloads the release APK.
     */
    private fun postUpdateNotification(context: Context, release: AppReleaseInfo) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.update_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)

        val downloadIntent = Intent(Intent.ACTION_VIEW, release.downloadUrl.toUri()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, release.tagName))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
}
